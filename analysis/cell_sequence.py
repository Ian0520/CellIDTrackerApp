#!/usr/bin/env python3
"""Audit one-cell-per-attempt information using private paired captures.

Outputs contain counts and rates only. Cell IDs, coordinates, phone numbers,
Call-IDs, SIP values, and per-attempt records stay in the ignored captures tree.
"""

from __future__ import annotations

import argparse
import bisect
import collections
import json
import math
import re
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Any


CELL_PARAMETER = re.compile(r"(?:^|;)\s*utran-cell-id-3gpp\s*=\s*\"?([0-9A-Fa-f]+)", re.I)
CELL_HEADERS = ("cellular-network-info", "p-access-network-info")


@dataclass(frozen=True)
class Cell:
    rat: str
    mcc: int
    mnc: int
    area: int
    identity: int

    def key(self) -> tuple[str, int, int, int, int]:
        return self.rat, self.mcc, self.mnc, self.area, self.identity

    def numeric_key(self) -> tuple[int, int, int, int]:
        return self.mcc, self.mnc, self.area, self.identity


def parse_cell_header(value: str) -> Cell | None:
    """Parse the standardized LTE/NR/UTRAN identity without crossing headers."""
    access_type = value.split(";", 1)[0].strip().upper()
    match = CELL_PARAMETER.search(value)
    if not match:
        return None
    encoded = match.group(1)
    if "E-UTRAN" in access_type:
        rat, cell_width = "lte", 7
        widths = {16: (2, 4), 17: (3, 4), 18: (2, 6), 19: (3, 6)}
    elif access_type.startswith("3GPP-NR"):
        rat, cell_width = "nr", 9
        widths = {20: (2, 6), 21: (3, 6)}
    elif "UTRAN" in access_type:
        rat, cell_width = "wcdma", 7
        widths = {16: (2, 4), 17: (3, 4)}
    else:
        return None
    if len(encoded) not in widths:
        return None
    mnc_width, area_width = widths[len(encoded)]
    if len(encoded) != 3 + mnc_width + area_width + cell_width:
        return None
    mcc_text = encoded[:3]
    mnc_text = encoded[3 : 3 + mnc_width]
    if not (mcc_text.isdecimal() and mnc_text.isdecimal()):
        return None
    area_start = 3 + mnc_width
    return Cell(
        rat=rat,
        mcc=int(mcc_text),
        mnc=int(mnc_text),
        area=int(encoded[area_start : area_start + area_width], 16),
        identity=int(encoded[-cell_width:], 16),
    )


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def session_specs(config: dict[str, Any]) -> list[dict[str, Any]]:
    if "sessions" in config:
        return list(config["sessions"])
    sessions: list[dict[str, Any]] = []
    for day in config.get("days", []):
        day_config = load_json(Path(day["config"]))
        for block in day_config["blocks"]:
            sessions.append({
                **block,
                "day": day["label"],
                "campaignId": day_config["campaignId"],
                "phase": "stationary",
            })
    for control in config.get("controls", []):
        sessions.append({**control, "phase": "stationary"})
    return sessions


def load_native_by_call(native_dir: Path) -> dict[str, list[dict[str, Any]]]:
    calls: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    paths = sorted(native_dir.glob("sip_capture.jsonl*"))
    if not paths:
        raise FileNotFoundError(f"No native research capture in {native_dir}")
    for path in paths:
        with path.open(encoding="utf-8", errors="replace") as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError as error:
                    raise ValueError(f"Invalid JSONL in {path}:{line_number}") from error
                if row.get("record_type") == "transport_payload" and row.get("call_id"):
                    calls[row["call_id"]].append(row)
    for rows in calls.values():
        rows.sort(key=lambda row: (row.get("unix_ms", 0), row.get("elapsed_ns", 0), row.get("sequence", 0)))
    return calls


def nearest_truth(
    samples: list[dict[str, Any]], times: list[int], timestamp_ms: int, max_gap_ms: int
) -> dict[str, Any] | None:
    index = bisect.bisect_left(times, timestamp_ms)
    candidates = [i for i in (index - 1, index) if 0 <= i < len(samples)]
    if not candidates:
        return None
    best = min(candidates, key=lambda i: abs(times[i] - timestamp_ms))
    return samples[best] if abs(times[best] - timestamp_ms) <= max_gap_ms else None


def ground_cell(sample: dict[str, Any] | None, max_age_ms: int) -> tuple[Cell | None, str]:
    if sample is None:
        return None, "no_join"
    if not sample.get("servingCellIsRegistered"):
        return None, "not_registered"
    rat = str(sample.get("servingCellRat") or "").lower()
    rat = {"lte": "lte", "nr": "nr", "wcdma": "wcdma"}.get(rat, rat)
    fields = (
        sample.get("servingCellMcc"), sample.get("servingCellMnc"),
        sample.get("servingCellAreaCode"), sample.get("servingCellId"),
    )
    if not rat or any(value is None for value in fields):
        return None, "no_identity"
    matching_measurements = [
        cell for cell in (sample.get("radio") or {}).get("cells", [])
        if cell.get("registered") and
        (cell.get("mcc"), cell.get("mnc"), cell.get("areaCode"), cell.get("cellId")) == fields
    ]
    if not matching_measurements:
        return None, "no_modem_measurement"
    age = matching_measurements[0].get("ageMillis")
    if age is None:
        return None, "no_modem_age"
    if age > max_age_ms:
        return None, "stale_modem_measurement"
    return Cell(rat, *(int(value) for value in fields)), "fresh"


def app_cell(attempt: dict[str, Any]) -> Cell | None:
    fields = (attempt.get("mcc"), attempt.get("mnc"), attempt.get("lac"), attempt.get("cid"))
    if any(value is None or int(value) < 0 for value in fields):
        return None
    # Current structured export does not retain the access type. Numeric
    # equality is checked separately; raw-header RAT remains authoritative.
    return Cell("unknown", *(int(value) for value in fields))


def first_provisionals(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        row for row in rows
        if row.get("direction") == "incoming"
        and row.get("cseq_method") == "INVITE"
        and row.get("status") in (180, 183)
    ]


def header_cells(rows: list[dict[str, Any]]) -> tuple[bool, Cell | None, int, int]:
    present = False
    cells: list[Cell] = []
    cni = 0
    pani = 0
    for row in rows:
        for header in row.get("headers", []):
            name = str(header.get("name", "")).lower()
            if name not in CELL_HEADERS:
                continue
            present = True
            cni += name == "cellular-network-info"
            pani += name == "p-access-network-info"
            parsed = parse_cell_header(str(header.get("value", "")))
            if parsed is not None:
                cells.append(parsed)
    return present, cells[0] if cells else None, cni, pani


def transitions(cells: list[Cell | None]) -> tuple[int, int, int]:
    observed = [cell.key() for cell in cells if cell is not None]
    changes = sum(left != right for left, right in zip(observed, observed[1:]))
    reversals = sum(
        observed[i] == observed[i - 2] != observed[i - 1]
        for i in range(2, len(observed))
    )
    return changes, max(0, len(observed) - 1), reversals


def persistent_change_times(cells: list[Cell | None], times: list[int],
                            confirmations: int = 3) -> list[int]:
    """Timestamp changes after consecutive cell-bearing attempts agree."""
    if confirmations < 2:
        raise ValueError("At least two confirmations are required")
    if len(cells) != len(times):
        raise ValueError("Cells and times must be aligned")
    stable: tuple[str, int, int, int, int] | None = None
    candidate: tuple[str, int, int, int, int] | None = None
    count = 0
    alerts: list[int] = []
    for cell, timestamp in zip(cells, times):
        if cell is None:
            candidate, count = None, 0
            continue
        key = cell.key()
        if key == stable:
            candidate, count = None, 0
            continue
        if key == candidate:
            count += 1
        else:
            candidate, count = key, 1
        if count == confirmations:
            if stable is not None:
                alerts.append(timestamp)
            stable = key
            candidate, count = None, 0
    return alerts


def persistent_changes(cells: list[Cell | None], confirmations: int = 3) -> int:
    return len(persistent_change_times(cells, list(range(len(cells))), confirmations))


def enodeb_group(cell: Cell | None) -> Cell | None:
    if cell is None or cell.rat != "lte":
        return cell
    # The eNodeB identifier is in the ECI, not in the tracking area code.
    # Retain PLMN to avoid conflating identifiers assigned by different networks.
    return Cell("lte-enodeb", cell.mcc, cell.mnc, 0, cell.identity >> 8)


def location_error_m(attempt: dict[str, Any], sample: dict[str, Any] | None,
                     max_accuracy_m: float, max_age_ms: int) -> tuple[float, float] | None:
    if sample is None or sample.get("isMockLocation") or attempt.get("geolocationStatus") != "success":
        return None
    if any(attempt.get(field) is None for field in ("estimatedLat", "estimatedLon", "estimatedAccuracyM")):
        return None
    if sample.get("accuracyM") is None or sample["accuracyM"] > max_accuracy_m:
        return None
    if sample.get("locationAgeMillis") is None or sample["locationAgeMillis"] > max_age_ms:
        return None
    lat1, lon1 = math.radians(float(attempt["estimatedLat"])), math.radians(float(attempt["estimatedLon"]))
    lat2, lon2 = math.radians(float(sample["lat"])), math.radians(float(sample["lon"]))
    delta_lat, delta_lon = lat2 - lat1, lon2 - lon1
    a = math.sin(delta_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2
    return 6_371_000 * 2 * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1 - a))), float(attempt["estimatedAccuracyM"])


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def analyze_session(spec: dict[str, Any], captures_root: Path, max_gap_ms: int, max_age_ms: int,
                    max_location_accuracy_m: float, max_location_age_ms: int) -> dict[str, Any]:
    session_id = spec["callerSessionId"]
    root = captures_root / session_id
    caller = load_json(root / "sanitized" / f"{session_id}.json")
    metadata = load_json(root / "raw" / session_id / "session_metadata.json")
    truth = load_json(root / "ground_truth" / spec["groundTruthFile"])
    if metadata.get("campaignId") != spec["campaignId"] or caller.get("campaignId") not in (None, spec["campaignId"]):
        raise ValueError(f"Caller campaign mismatch in {spec['label']}")
    if metadata.get("conditionLabel") != spec["conditionLabel"]:
        raise ValueError(f"Caller condition mismatch in {spec['label']}")
    if truth.get("campaignId") != spec["campaignId"]:
        raise ValueError(f"Target campaign mismatch in {spec['label']}")
    if truth.get("conditionLabel") != spec.get("groundTruthConditionLabel", spec["conditionLabel"]):
        raise ValueError(f"Target condition mismatch in {spec['label']}")
    calls = load_native_by_call(root / "raw" / session_id / "native")
    truth_samples = sorted(truth.get("samples", []), key=lambda sample: sample["recordedAtMillis"])
    truth_times = [sample["recordedAtMillis"] for sample in truth_samples]
    attempts = [attempt for attempt in caller.get("samples", []) if attempt.get("probeId")]
    if len({attempt["probeId"] for attempt in attempts}) != len(attempts):
        raise ValueError(f"Duplicate probe IDs in {spec['label']}")
    attempts.sort(key=lambda attempt: attempt.get("inviteSentAtMillis") or attempt["recordedAtMillis"])

    counts: collections.Counter[str] = collections.Counter()
    statuses: collections.Counter[str] = collections.Counter()
    rats: collections.Counter[str] = collections.Counter()
    raw_sequence: list[Cell | None] = []
    event_times: list[int] = []
    app_sequence: list[Cell | None] = []
    ground_sequence: list[Cell | None] = []
    geo_errors: list[float] = []
    geo_contained = 0
    for attempt in attempts:
        counts["attempts"] += 1
        geo_status = attempt.get("geolocationStatus") or "unknown"
        counts[f"geolocation_{geo_status}"] += 1
        if geo_status == "failure":
            error_code = re.search(r"\bHTTP (\d{3})\b", attempt.get("geolocationError") or "")
            counts[f"geolocation_http_{error_code.group(1) if error_code else 'other'}"] += 1
        rows = first_provisionals(calls.get(attempt["probeId"], []))
        if rows:
            counts["provisional"] += 1
            statuses[str(rows[0]["status"])] += 1
        elif attempt.get("sipStatus") is not None:
            statuses[str(attempt["sipStatus"])] += 1
        present, raw, cni, pani = header_cells(rows)
        counts["header_present"] += present
        counts["cni_headers"] += cni
        counts["pani_headers"] += pani
        counts["raw_cell"] += raw is not None
        counts["app_cell"] += (app := app_cell(attempt)) is not None
        if raw is not None:
            rats[raw.rat] += 1
        if raw is not None and app is not None:
            counts["raw_app_compared"] += 1
            counts["raw_app_match"] += raw.numeric_key() == app.numeric_key()
        event_time = attempt.get("responseReceivedAtMillis") or attempt.get("inviteSentAtMillis") or attempt["recordedAtMillis"]
        event_times.append(event_time)
        sample = nearest_truth(truth_samples, truth_times, event_time, max_gap_ms)
        counts["truth_joined"] += sample is not None
        geo = location_error_m(attempt, sample, max_location_accuracy_m, max_location_age_ms)
        if geo is not None:
            error_m, radius_m = geo
            geo_errors.append(error_m)
            geo_contained += error_m <= radius_m
        target, target_reason = ground_cell(sample, max_age_ms)
        counts[f"truth_{target_reason}"] += 1
        counts["fresh_registered_truth_cell"] += target is not None
        if raw is not None and target is not None:
            counts["raw_truth_compared"] += 1
            counts["raw_truth_match"] += raw.key() == target.key()
            counts["raw_truth_lte_enodeb_match"] += (
                raw.rat == target.rat == "lte"
                and (raw.mcc, raw.mnc, raw.identity >> 8)
                == (target.mcc, target.mnc, target.identity >> 8)
            )
            if raw.key() != target.key():
                if raw.rat != target.rat:
                    counts["raw_truth_rat_mismatch"] += 1
                elif (raw.mcc, raw.mnc) != (target.mcc, target.mnc):
                    counts["raw_truth_plmn_mismatch"] += 1
                elif raw.area != target.area:
                    counts["raw_truth_area_mismatch"] += 1
                else:
                    counts["raw_truth_cell_mismatch"] += 1
        raw_sequence.append(raw)
        app_sequence.append(app)
        ground_sequence.append(target)

    raw_changes, raw_pairs, raw_reversals = transitions(raw_sequence)
    enodeb_sequence = [enodeb_group(cell) for cell in raw_sequence]
    raw_alert_times = persistent_change_times(raw_sequence, event_times)
    enodeb_alert_times = persistent_change_times(enodeb_sequence, event_times)
    enodeb_changes, enodeb_pairs, enodeb_reversals = transitions(enodeb_sequence)
    app_changes, app_pairs, app_reversals = transitions(app_sequence)
    ground_changes, ground_pairs, ground_reversals = transitions(ground_sequence)
    movement: dict[str, Any] | None = None
    if spec.get("phase") == "moving":
        start, end = int(spec["movementStartMillis"]), int(spec["movementEndMillis"])
        if start >= end or not event_times or start < event_times[0] or end > event_times[-1]:
            raise ValueError(f"Movement labels are outside the moving session in {spec['label']}")
        raw_during = [time for time in raw_alert_times if start <= time <= end]
        enodeb_during = [time for time in enodeb_alert_times if start <= time <= end]
        movement = {
            "duration_s": round((end - start) / 1000, 1),
            "raw_cell_alerts_during_movement": len(raw_during),
            "enodeb_alerts_during_movement": len(enodeb_during),
            "raw_cell_alerts_before_movement": sum(time < start for time in raw_alert_times),
            "enodeb_alerts_before_movement": sum(time < start for time in enodeb_alert_times),
            "raw_first_alert_latency_s": round((raw_during[0] - start) / 1000, 1) if raw_during else None,
            "enodeb_first_alert_latency_s": round((enodeb_during[0] - start) / 1000, 1) if enodeb_during else None,
        }
    return {
        "day": spec.get("day", ""),
        "block": spec["label"],
        "phase": spec.get("phase", "unknown"),
        "counts": dict(counts),
        "first_provisional_statuses": dict(statuses),
        "parsed_radio_types": dict(rats),
        "raw_distinct_cells": len({cell.key() for cell in raw_sequence if cell is not None}),
        "app_distinct_cells": len({cell.numeric_key() for cell in app_sequence if cell is not None}),
        "raw_cell_changes": raw_changes,
        "raw_observed_pairs": raw_pairs,
        "raw_aba_reversals": raw_reversals,
        "raw_persistent_changes_3": len(raw_alert_times),
        "lte_enodeb_distinct": len({cell.key() for cell in enodeb_sequence if cell is not None}),
        "lte_enodeb_changes": enodeb_changes,
        "lte_enodeb_observed_pairs": enodeb_pairs,
        "lte_enodeb_aba_reversals": enodeb_reversals,
        "lte_enodeb_persistent_changes_3": len(enodeb_alert_times),
        "movement": movement,
        "app_cell_changes": app_changes,
        "app_observed_pairs": app_pairs,
        "app_aba_reversals": app_reversals,
        "truth_cell_changes": ground_changes,
        "truth_observed_pairs": ground_pairs,
        "truth_aba_reversals": ground_reversals,
        "geolocation_with_quality_truth": len(geo_errors),
        "geolocation_median_error_m": round(statistics.median(geo_errors), 1) if geo_errors else None,
        "geolocation_p90_error_m": round(percentile(geo_errors, 0.9), 1) if geo_errors else None,
        "geolocation_within_reported_radius": geo_contained,
    }


def render_report(results: list[dict[str, Any]], max_cell_age_ms: int) -> str:
    lines = [
        "# Cell Sequence Information Audit", "",
        f"Phases come from operator labels. Values are aggregate only. Target modem age cutoff: {max_cell_age_ms} ms.", "",
        "| Day / block | Phase | Attempts | Provisional | Header | Parsed raw cell | Distinct cells / eNodeB groups | Cell changes / pairs | eNodeB changes | 3-probe cell / eNodeB alerts |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in results:
        c = row["counts"]
        lines.append(
            f"| {row['day']} {row['block']} | {row['phase']} | {c.get('attempts', 0)} | {c.get('provisional', 0)} | "
            f"{c.get('header_present', 0)} | {c.get('raw_cell', 0)} | "
            f"{row['raw_distinct_cells']} / {row['lte_enodeb_distinct']} | "
            f"{row['raw_cell_changes']} / {row['raw_observed_pairs']} | "
            f"{row['lte_enodeb_changes']} | "
            f"{row['raw_persistent_changes_3']} / {row['lte_enodeb_persistent_changes_3']} |"
        )
    totals = collections.Counter()
    for row in results:
        totals.update(row["counts"])
    lines += [
        "", "## Pooled descriptive counts", "",
        f"- Fresh structured attempts: {totals['attempts']}.",
        f"- Attempts with a captured 180/183: {totals['provisional']}.",
        f"- Attempts with a cell-bearing header name: {totals['header_present']}.",
        f"- Attempts with a parseable raw cell: {totals['raw_cell']}.",
        f"- Three-consecutive-cell alerts in stationary blocks: {sum(row['raw_persistent_changes_3'] for row in results if row['phase'] == 'stationary')}.",
        f"- Three-consecutive-eNodeB alerts in stationary blocks: {sum(row['lte_enodeb_persistent_changes_3'] for row in results if row['phase'] == 'stationary')}.",
        f"- Raw/app numeric identity agreement: {totals['raw_app_match']} / {totals['raw_app_compared']} comparable attempts.",
        f"- Raw/recent registered target-cell agreement: {totals['raw_truth_match']} / {totals['raw_truth_compared']} comparable attempts.",
        f"- Raw/recent registered LTE eNodeB-group agreement: {totals['raw_truth_lte_enodeb_match']} / {totals['raw_truth_compared']} comparable attempts.",
        f"- Target modem readings excluded as stale: {totals['truth_stale_modem_measurement']} / {totals['attempts']} attempts.",
        f"- Among comparable raw/target cells, mismatches by first differing field: RAT {totals['raw_truth_rat_mismatch']}, PLMN {totals['raw_truth_plmn_mismatch']}, area {totals['raw_truth_area_mismatch']}, cell {totals['raw_truth_cell_mismatch']}.",
        f"- Paired single-cell geolocation estimates with quality-filtered target location: {sum(row['geolocation_with_quality_truth'] for row in results)} / {totals['app_cell']} app cell attempts.",
        f"- Geolocation failures: {totals['geolocation_failure']} (HTTP 404: {totals['geolocation_http_404']}); not requested: {totals['geolocation_not_requested']}.",
        "", "## Interpretation", "",
        "A cell change in an operator-labeled stationary block is a false movement cue. The raw header's cell can describe a recently camped cell rather than the current registered cell. Target comparisons use only registered modem readings no older than the configured age limit. Movement detection needs labeled moving blocks; geographic accuracy needs quality-filtered held-out locations across sites.",
        "",
    ]
    if not any(row["geolocation_with_quality_truth"] for row in results):
        lines += ["No successful single-cell geolocation estimate with quality-filtered target location is available in these sessions.", ""]
    moving = [row for row in results if row["phase"] == "moving"]
    if moving:
        lines += [
            "## Labeled moving blocks", "",
            "| Day / block | Movement duration (s) | Raw / eNodeB alerts during movement | Raw / eNodeB pre-movement alerts | First raw / eNodeB alert latency (s) |",
            "|---|---:|---:|---:|---:|",
        ]
        for row in moving:
            movement = row["movement"]
            lines.append(
                f"| {row['day']} {row['block']} | {movement['duration_s']} | "
                f"{movement['raw_cell_alerts_during_movement']} / {movement['enodeb_alerts_during_movement']} | "
                f"{movement['raw_cell_alerts_before_movement']} / {movement['enodeb_alerts_before_movement']} | "
                f"{movement['raw_first_alert_latency_s']} / {movement['enodeb_first_alert_latency_s']} |"
            )
        lines += ["", f"Moving blocks with an eNodeB alert: {sum(bool(row['movement']['enodeb_alerts_during_movement']) for row in moving)} / {len(moving)}.", ""]
    geo_errors = [row["geolocation_median_error_m"] for row in results if row["geolocation_median_error_m"] is not None]
    if geo_errors:
        lines += [
            "## Existing geolocation outputs by block", "",
            "| Day / block | Quality-filtered pairs | Median error (m) | P90 error (m) | Within API radius |",
            "|---|---:|---:|---:|---:|",
        ]
        for row in results:
            if row["geolocation_with_quality_truth"]:
                lines.append(
                    f"| {row['day']} {row['block']} | {row['geolocation_with_quality_truth']} | "
                    f"{row['geolocation_median_error_m']} | {row['geolocation_p90_error_m']} | "
                    f"{row['geolocation_within_reported_radius']} / {row['geolocation_with_quality_truth']} |"
                )
        lines += ["", "These are historical app estimates; the request used the earlier radioType placement. Repeated probes at one site are not independent geographic validation.", ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--captures-root", type=Path, default=Path("captures"))
    parser.add_argument("--output", type=Path, default=Path("captures/cell_sequence_analysis"))
    parser.add_argument("--max-ground-cell-age-ms", type=int, default=None,
                        help="Override target modem reading freshness cutoff")
    args = parser.parse_args()
    config = load_json(args.config)
    max_cell_age_ms = args.max_ground_cell_age_ms
    if max_cell_age_ms is None:
        max_cell_age_ms = int(config.get("maxGroundCellAgeMs", 5000))
    results = [
        analyze_session(
            spec, args.captures_root,
            int(config.get("maxGroundTruthGapMs", 5000)),
            max_cell_age_ms,
            float(config.get("maxGroundLocationAccuracyM", 30)),
            int(config.get("maxGroundLocationAgeMs", 5000)),
        )
        for spec in session_specs(config)
    ]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "summary.json").write_text(
        json.dumps({"maxGroundTruthGapMs": int(config.get("maxGroundTruthGapMs", 5000)),
                    "maxGroundCellAgeMs": max_cell_age_ms,
                    "maxGroundLocationAccuracyM": float(config.get("maxGroundLocationAccuracyM", 30)),
                    "maxGroundLocationAgeMs": int(config.get("maxGroundLocationAgeMs", 5000)),
                    "sessions": results}, indent=2) + "\n", encoding="utf-8"
    )
    (args.output / "report.md").write_text(render_report(results, max_cell_age_ms), encoding="utf-8")
    print(f"Analyzed {len(results)} sessions and {sum(row['counts']['attempts'] for row in results)} attempts")
    print(f"Aggregate report: {args.output / 'report.md'}")


if __name__ == "__main__":
    main()
