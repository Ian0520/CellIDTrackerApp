#!/usr/bin/env python3
"""
Evaluate whether combinations of recorded cell towers improve Google Geolocation
accuracy compared with the original single-tower results stored in
`probe_history.json`.

Default behavior:
- Load API key from $GOOGLE_API_KEY or local.properties.
- Group entries by victim + MCC + MNC + LAC + radio.
- Build unique towers per group.
- Query Google Geolocation for combinations of those towers.
- For each combination, test multiple orderings (rotate-first by default).
- Compare the best combo accuracy against the best original single-tower
  accuracy observed for towers inside that combination.
- Write a timestamped JSON report so previous outputs are preserved.

Example:
    python3 scripts/evaluate_geolocation_combinations.py \
        --input probe_history.json \
        --min-combo 2 \
        --max-combo 3
"""

from __future__ import annotations

import argparse
import itertools
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable


API_URL = "https://www.googleapis.com/geolocation/v1/geolocate?key={api_key}"


@dataclass(frozen=True)
class Tower:
    mcc: int
    mnc: int
    lac: int
    cid: int
    radio: str = "lte"

    def key(self) -> tuple[int, int, int, int, str]:
        return (self.mcc, self.mnc, self.lac, self.cid, self.radio.lower())

    def as_payload(self) -> dict:
        return {
            "mobileCountryCode": self.mcc,
            "mobileNetworkCode": self.mnc,
            "locationAreaCode": self.lac,
            "cellId": self.cid,
            "radioType": self.radio,
        }

    def as_json(self) -> dict:
        return {
            "mcc": self.mcc,
            "mnc": self.mnc,
            "lac": self.lac,
            "cid": self.cid,
            "radio": self.radio,
        }

    def site_key(self) -> str:
        radio = self.radio.lower()
        if radio == "lte":
            # Google Geolocation docs: LTE uses ECI = eNBId << 8 | sectorId.
            # Towers with the same upper bits therefore belong to the same LTE site.
            return f"lte:{self.mcc}:{self.mnc}:{self.lac}:{self.cid >> 8}"
        return f"{radio}:{self.mcc}:{self.mnc}:{self.lac}:{self.cid}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="probe_history.json", help="Path to exported probe history JSON")
    parser.add_argument("--output-dir", default=".", help="Directory for the generated report JSON")
    parser.add_argument("--api-key", default=None, help="Google API key (defaults to env/local.properties)")
    parser.add_argument("--min-combo", type=int, default=2, help="Minimum combination size to test")
    parser.add_argument("--max-combo", type=int, default=3, help="Maximum combination size to test")
    parser.add_argument(
        "--order-mode",
        choices=("rotate", "all"),
        default="rotate",
        help="Payload ordering strategy: rotate first element only, or test all permutations",
    )
    parser.add_argument(
        "--site-policy",
        choices=("any", "distinct-lte-sites"),
        default="any",
        help="Whether to allow multiple LTE sectors from the same site in one combination",
    )
    parser.add_argument("--sleep-ms", type=int, default=150, help="Delay between API calls in milliseconds")
    parser.add_argument("--limit-requests", type=int, default=0, help="Stop after N API requests (0 = unlimited)")
    parser.add_argument("--max-groups", type=int, default=0, help="Only evaluate the first N groups (0 = all)")
    parser.add_argument("--dry-run", action="store_true", help="Do not call the API; only enumerate candidates")
    return parser.parse_args()


def load_api_key(cli_value: str | None) -> str:
    if cli_value:
        return cli_value

    env_value = os.getenv("GOOGLE_API_KEY")
    if env_value:
        return env_value

    local_props = Path("local.properties")
    if local_props.exists():
        for line in local_props.read_text().splitlines():
            if line.startswith("GOOGLE_API_KEY="):
                return line.split("=", 1)[1].strip()

    raise SystemExit("GOOGLE_API_KEY not found. Set --api-key, export GOOGLE_API_KEY, or put it in local.properties.")


def load_history(path: Path) -> list[dict]:
    data = json.loads(path.read_text())
    if not isinstance(data, list):
        raise SystemExit(f"Expected a JSON array in {path}")
    return data


def tower_from_obj(obj: dict) -> Tower:
    return Tower(
        mcc=int(obj["mcc"]),
        mnc=int(obj["mnc"]),
        lac=int(obj["lac"]),
        cid=int(obj["cid"]),
        radio=str(obj.get("radio", "lte")).lower(),
    )


def tower_from_entry(entry: dict) -> Tower:
    towers = entry.get("towers") or []
    if towers:
        return tower_from_obj(towers[0])
    return Tower(
        mcc=int(entry["mcc"]),
        mnc=int(entry["mnc"]),
        lac=int(entry["lac"]),
        cid=int(entry["cid"]),
        radio="lte",
    )


def entry_group_key(entry: dict) -> tuple[str, int, int, int, str]:
    tower = tower_from_entry(entry)
    return (
        str(entry.get("victim", "")),
        tower.mcc,
        tower.mnc,
        tower.lac,
        tower.radio.lower(),
    )


def build_groups(entries: list[dict]) -> list[dict]:
    groups: list[dict] = []
    current_key: tuple[str, int, int, int, str] | None = None
    current_entries: list[dict] = []

    for entry in entries:
        key = entry_group_key(entry)
        if current_key is None or key == current_key:
            current_key = key
            current_entries.append(entry)
            continue

        groups.append({"key": current_key, "entries": current_entries})
        current_key = key
        current_entries = [entry]

    if current_key is not None:
        groups.append({"key": current_key, "entries": current_entries})

    return groups


def unique_towers(entries: Iterable[dict]) -> list[Tower]:
    seen: dict[tuple[int, int, int, int, str], Tower] = {}
    for entry in entries:
        for t in entry.get("towers") or [tower_from_entry(entry).as_json()]:
            tower = tower_from_obj(t)
            seen.setdefault(tower.key(), tower)
    return list(seen.values())


def best_original_accuracy(entries: Iterable[dict]) -> dict[tuple[int, int, int, int, str], float]:
    best: dict[tuple[int, int, int, int, str], float] = {}
    for entry in entries:
        accuracy = entry.get("accuracy")
        if accuracy is None:
            continue
        try:
            acc = float(accuracy)
        except (TypeError, ValueError):
            continue
        tower = tower_from_entry(entry)
        key = tower.key()
        prev = best.get(key)
        if prev is None or acc < prev:
            best[key] = acc
    return best


def combination_allowed(combo: tuple[Tower, ...], site_policy: str) -> bool:
    if site_policy != "distinct-lte-sites":
        return True
    seen = set()
    for tower in combo:
        key = tower.site_key()
        if key in seen:
            return False
        seen.add(key)
    return True


def ordered_payloads(combo: tuple[Tower, ...], order_mode: str) -> list[list[Tower]]:
    base = sorted(combo, key=lambda t: (t.mcc, t.mnc, t.lac, t.cid, t.radio))
    if order_mode == "all":
        payloads = list(itertools.permutations(base))
    else:
        payloads = [tuple([base[i], *base[:i], *base[i + 1 :]]) for i in range(len(base))]

    deduped = []
    seen = set()
    for payload in payloads:
        signature = tuple(t.key() for t in payload)
        if signature not in seen:
            seen.add(signature)
            deduped.append(list(payload))
    return deduped


def geolocate(api_key: str, towers: list[Tower]) -> dict:
    payload = {
        "considerIp": False,
        "cellTowers": [tower.as_payload() for tower in towers],
    }
    request = urllib.request.Request(
        API_URL.format(api_key=api_key),
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read().decode("utf-8")
            parsed = json.loads(body)
            return {
                "ok": True,
                "payload": payload,
                "response": parsed,
                "accuracy": parsed.get("accuracy"),
                "lat": parsed.get("location", {}).get("lat"),
                "lon": parsed.get("location", {}).get("lng"),
            }
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        return {
            "ok": False,
            "payload": payload,
            "error": f"HTTP {exc.code}",
            "body": body,
        }
    except Exception as exc:  # pragma: no cover - operational path
        return {
            "ok": False,
            "payload": payload,
            "error": str(exc),
        }


def evaluate_group(
    group_key: tuple[str, int, int, int, str],
    entries: list[dict],
    api_key: str,
    min_combo: int,
    max_combo: int,
    order_mode: str,
    site_policy: str,
    sleep_ms: int,
    limit_requests: int,
    dry_run: bool,
) -> tuple[dict, int]:
    victim, mcc, mnc, lac, radio = group_key
    towers = unique_towers(entries)
    original_best_map = best_original_accuracy(entries)
    requests_made = 0
    combinations_evaluated = 0
    payloads_planned = 0

    timestamps = [int(e.get("timestampMillis", 0) or 0) for e in entries]

    report = {
        "group": {
            "victim": victim,
            "mcc": mcc,
            "mnc": mnc,
            "lac": lac,
            "radio": radio,
            "uniqueTowerCount": len(towers),
            "entryCount": len(entries),
            "startTimestampMillis": min(timestamps) if timestamps else None,
            "endTimestampMillis": max(timestamps) if timestamps else None,
        },
        "summary": {
            "combinationCount": 0,
            "payloadCount": 0,
            "improvedCombinationCount": 0,
            "groupImproved": False,
        },
        "combinations": [],
    }

    if len(towers) < min_combo:
        return report, requests_made

    upper = min(max_combo, len(towers))
    for combo_size in range(min_combo, upper + 1):
        for combo in itertools.combinations(towers, combo_size):
            if not combination_allowed(combo, site_policy):
                continue

            combinations_evaluated += 1
            payloads = ordered_payloads(combo, order_mode)
            payloads_planned += len(payloads)
            baseline_candidates = [original_best_map.get(t.key()) for t in combo]
            baseline_candidates = [v for v in baseline_candidates if v is not None]
            baseline_best = min(baseline_candidates) if baseline_candidates else None

            combo_report = {
                "comboSize": combo_size,
                "comboTowers": [t.as_json() for t in combo],
                "baselineBestSingleAccuracy": baseline_best,
                "payloadCount": len(payloads),
                "bestResult": None,
                "allFailures": [],
            }

            best_result = None
            best_accuracy = None

            for payload in payloads:
                if limit_requests and requests_made >= limit_requests:
                    report["stoppedEarly"] = f"request limit {limit_requests} reached"
                    return report, requests_made

                if dry_run:
                    requests_made += 1
                    continue

                result = geolocate(api_key, payload)
                requests_made += 1

                if result["ok"] and result.get("accuracy") is not None:
                    acc = float(result["accuracy"])
                    if best_accuracy is None or acc < best_accuracy:
                        best_accuracy = acc
                        best_result = {
                            "accuracy": acc,
                            "lat": result.get("lat"),
                            "lon": result.get("lon"),
                            "payload": [t.as_json() for t in payload],
                            "improvedVsBestSingle": (baseline_best is not None and acc < baseline_best),
                            "improvementMeters": (baseline_best - acc) if baseline_best is not None else None,
                        }
                else:
                    combo_report["allFailures"].append(
                        {
                            "payload": [t.as_json() for t in payload],
                            "error": result.get("error"),
                            "body": result.get("body"),
                        }
                    )

                if sleep_ms > 0:
                    time.sleep(sleep_ms / 1000.0)

            combo_report["bestResult"] = best_result
            if best_result and best_result.get("improvedVsBestSingle"):
                report["summary"]["improvedCombinationCount"] += 1
                report["summary"]["groupImproved"] = True
            report["combinations"].append(combo_report)

    report["summary"]["combinationCount"] = combinations_evaluated
    report["summary"]["payloadCount"] = payloads_planned
    return report, requests_made


def main() -> int:
    args = parse_args()
    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    entries = load_history(input_path)
    groups = build_groups(entries)
    api_key = "" if args.dry_run else load_api_key(args.api_key)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    report = {
        "generatedAt": datetime.now().isoformat(),
        "input": str(input_path),
        "dryRun": args.dry_run,
        "settings": {
            "minCombo": args.min_combo,
            "maxCombo": args.max_combo,
            "orderMode": args.order_mode,
            "sitePolicy": args.site_policy,
            "sleepMs": args.sleep_ms,
            "limitRequests": args.limit_requests,
            "maxGroups": args.max_groups,
        },
        "groupReports": [],
    }

    total_requests = 0
    ordered_groups = groups[: args.max_groups] if args.max_groups else groups

    for group in ordered_groups:
        group_key = group["key"]
        group_entries = group["entries"]
        group_report, requests_made = evaluate_group(
            group_key=group_key,
            entries=group_entries,
            api_key=api_key,
            min_combo=args.min_combo,
            max_combo=args.max_combo,
            order_mode=args.order_mode,
            site_policy=args.site_policy,
            sleep_ms=args.sleep_ms,
            limit_requests=max(0, args.limit_requests - total_requests) if args.limit_requests else 0,
            dry_run=args.dry_run,
        )
        total_requests += requests_made
        report["groupReports"].append(group_report)
        group_summary = group_report["summary"]
        group_meta = group_report["group"]
        print(
            "Group"
            f" entries={group_meta['entryCount']}"
            f" victim={group_meta['victim']}"
            f" lac={group_meta['lac']}"
            f" towers={group_meta['uniqueTowerCount']}"
            f" combinations={group_summary['combinationCount']}"
            f" payloads={group_summary['payloadCount']}"
            f" improved={group_summary['groupImproved']}"
        )
        if args.limit_requests and total_requests >= args.limit_requests:
            break

    report["totalRequests"] = total_requests
    report["totalCombinationCount"] = sum(g["summary"]["combinationCount"] for g in report["groupReports"])
    report["totalPayloadCount"] = sum(g["summary"]["payloadCount"] for g in report["groupReports"])
    report["totalImprovedGroupCount"] = sum(1 for g in report["groupReports"] if g["summary"]["groupImproved"])

    output_path = output_dir / f"geolocation_combo_report_{timestamp}.json"
    output_path.write_text(json.dumps(report, indent=2, ensure_ascii=False))

    print(f"Wrote report: {output_path}")
    print(f"Groups evaluated: {len(report['groupReports'])}")
    print(f"Combinations evaluated: {report['totalCombinationCount']}")
    print(f"Payloads generated: {report['totalPayloadCount']}")
    print(f"Groups improved: {report['totalImprovedGroupCount']}")
    print(f"API requests planned/executed: {total_requests}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
