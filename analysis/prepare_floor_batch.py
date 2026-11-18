#!/usr/bin/env python3
"""Prepare private, provenance-bearing tables for the November 10 floor pilot.

Uses the accepted user/altitude reference mapping. No target-only measurements
are exposed as prediction features. Outputs must stay in the ignored capture
tree: source identifiers and exact timestamps are private research material.
"""
from __future__ import annotations

import argparse
import bisect
import collections
import hashlib
import json
import math
import statistics
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

TZ = ZoneInfo("Asia/Taipei")
FLOORS = ["B1", "1F", "2F", "3F", "4F"]
CENTERS = [36.8, 42.3, 47.8, 52.8, 57.3]
CUTS = [39.5, 44.8, 50.5, 55.0]
PAIRS = [
    ("afternoon", "20261110_152604_192", "20261110_152606_326"),
    ("evening", "20261110_190941_137", "20261110_190940_260"),
]
POLICIES = {
    "primary": {"guardMs": 10000, "heightDeviationM": 1.25, "locationAgeCapMs": 10000},
    "tight": {"guardMs": 15000, "heightDeviationM": .75, "locationAgeCapMs": 5000},
    "loose": {"guardMs": 5000, "heightDeviationM": 1.5, "locationAgeCapMs": 10000},
}


def ts(clock: str) -> int:
    return int(datetime.fromisoformat("2026-11-10T" + clock).replace(tzinfo=TZ).timestamp() * 1000)


WINDOWS = {
    "excursion1": (ts("15:40:00"), ts("16:15:00")),
    "excursion2": (ts("16:30:00"), ts("17:10:00")),
}


def iso(t: float) -> str:
    return datetime.fromtimestamp(t / 1000, TZ).isoformat()


def opaque(prefix: str, value) -> str:
    return prefix + hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()[:20]


def cell_tuple(cell: dict) -> tuple | None:
    values = [cell.get(k) for k in ("rat", "mcc", "mnc", "areaCode", "cellId")]
    if any(x is None for x in values) or any(not isinstance(x, int) or x < 0 or x >= 2**63 - 1 for x in values[1:]):
        return None
    return tuple(values)


def selected(row: dict) -> dict | None:
    cells = [c for c in (row.get("radio") or {}).get("cells", []) if c.get("registered") is True]
    primary = [c for c in cells if c.get("connectionStatus") == 1]
    return primary[0] if len(primary) == 1 else cells[0] if len(cells) == 1 else None


def radio_acquired_at(row: dict) -> float:
    radio = row.get("radio") or {}
    captured = radio.get("capturedElapsedRealtimeNanos")
    started = row.get("capturedElapsedRealtimeNanos")
    return row["recordedAtMillis"] + (captured - started) / 1e6 if captured is not None and started is not None else row["recordedAtMillis"]


def caller_tuple(row: dict) -> tuple | None:
    if row.get("sampleType") != "cell" or row.get("outcome") != "cell_observed":
        return None
    values = [row.get(k) for k in ("mcc", "mnc", "lac", "cid")]
    return ("LTE", *values) if all(isinstance(v, int) and v >= 0 for v in values) else None


def episode(recording: str, t: float) -> str:
    if recording == "evening":
        return "evening4F"
    return next((e for e, (a, b) in WINDOWS.items() if a <= t < b), "baseline")


def merge_ranges(ranges):
    result = []
    for a, b in sorted(ranges):
        if result and a <= result[-1][1]:
            result[-1][1] = max(result[-1][1], b)
        else:
            result.append([a, b])
    return result


class Reference:
    def __init__(self, rows: list[dict]):
        fixes = {}
        for row in rows:
            ident = row.get("locationElapsedRealtimeNanos")
            if ident is not None and row.get("altitudeM") is not None:
                fixes.setdefault(ident, row)
        self.fixes = sorted(fixes.values(), key=lambda r: r["locationElapsedRealtimeNanos"])
        self.crossings = []
        for left, right in zip(self.fixes, self.fixes[1:]):
            if bisect.bisect_right(CUTS, left["altitudeM"]) != bisect.bisect_right(CUTS, right["altitudeM"]):
                self.crossings.append((left["locationFixTimeMillis"], right["locationFixTimeMillis"]))
        self.guards = {
            key: merge_ranges((a - p["guardMs"], b + p["guardMs"]) for a, b in self.crossings)
            for key, p in POLICIES.items()
        }
        self.guard_starts = {key: [a for a, _ in intervals] for key, intervals in self.guards.items()}

    def label(self, recording: str, row: dict | None, t: int, policy="primary"):
        if recording == "evening":
            return "4F", "user_reported_whole_visit", "accepted"
        if row is None:
            return None, "unknown", "missing_target_row"
        age = row.get("locationAgeMillis")
        if age is None or age + t - row["recordedAtMillis"] > POLICIES[policy]["locationAgeCapMs"]:
            return None, "height_derived_user_supported", "missing_or_old_location_fix"
        height = row.get("altitudeM")
        if height is None or row.get("isMockLocation") is not False:
            return None, "height_derived_user_supported", "missing_height_or_mock_flag"
        idx = bisect.bisect_right(CUTS, height)
        if abs(height - CENTERS[idx]) > POLICIES[policy]["heightDeviationM"]:
            return None, "height_derived_user_supported", "between_height_plateaus"
        i = bisect.bisect_right(self.guard_starts[policy], t) - 1
        if i >= 0 and t <= self.guards[policy][i][1]:
            return None, "height_derived_user_supported", "transition_guard"
        return FLOORS[idx], "height_derived_user_supported", "accepted"


def dump(path: Path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def dump_lines(path: Path, rows):
    with path.open("w") as output:
        for row in rows:
            output.write(json.dumps(row, separators=(",", ":")) + "\n")


def make_segments(rows: list[dict]):
    previous = None
    sequence = 0
    for row in rows:
        group = row["recording"], row["episode"]
        if previous is None or group != previous[0] or row["t_ms"] - previous[1] > 60000:
            sequence += 1
        row["segment"] = "/".join([*group, str(sequence)])
        previous = group, row["t_ms"]


def run(captures_root: Path, batch_root: Path, output: Path):
    output.mkdir(parents=True, exist_ok=True)
    inventory = json.loads((batch_root / "INVENTORY.json").read_text())
    for source in inventory["sourceFiles"]:
        path = captures_root.parent / source["path"]
        raw = path.read_bytes()
        if len(raw) != source["bytes"] or hashlib.sha256(raw).hexdigest() != source["sha256"]:
            raise ValueError("Source hash/size mismatch: " + str(path))
    sources = {}
    all_cells = set()
    timing_path = output / "primary_cni_native_timing.json"
    timing_data = json.loads(timing_path.read_text())
    timing = {(r["session_id"], r["probe_id"]): r for r in timing_data["observations"]}
    for recording, caller_sid, target_sid in PAIRS:
        target_path = batch_root / "target" / (target_sid + ".json")
        caller_path = captures_root / caller_sid / "sanitized" / (caller_sid + ".json")
        target = json.loads(target_path.read_text())
        caller = json.loads(caller_path.read_text())
        rows = target["samples"]
        sources[recording] = {
            "target": target, "caller": caller, "rows": rows,
            "times": [r["recordedAtMillis"] for r in rows], "reference": Reference(rows),
            "radioTimes": [radio_acquired_at(r) for r in rows],
            "targetPath": str(target_path), "callerPath": str(caller_path),
        }
        if any(a > b for a, b in zip(sources[recording]["radioTimes"], sources[recording]["radioTimes"][1:])):
            raise ValueError("Out-of-order radio acquisition timestamps")
        all_cells.update(cell_tuple(c) for r in rows if (c := selected(r)) is not None)
        all_cells.update(caller_tuple(r) for r in caller["samples"])
    recovered_path = output / "recovered_attempts.json"
    recovered = json.loads(recovered_path.read_text()) if recovered_path.exists() else {}
    for row in recovered.get("attempts", []):
        if row.get("cell") is not None and row.get("t_ms") is not None:
            all_cells.add(tuple(row["cell"]))
    all_cells.discard(None)
    aliases = {key: f"C{i:03d}" for i, key in enumerate(sorted(all_cells), 1)}
    dump(output / "private_cell_mapping.json", [{"cell": v, "tuple": list(k)} for k, v in aliases.items()])
    # Origin is fixed from the first acquired coordinate, not learned from test labels.
    origin_row = next(r for r in sources["afternoon"]["rows"] if r.get("lat") is not None and r.get("lon") is not None)
    origin = origin_row["lat"], origin_row["lon"]
    dump(output / "private_coordinate_origin.json", {"lat": origin[0], "lon": origin[1]})

    def observation(recording, t, source_row, cap, policy="primary"):
        source = sources[recording]
        index = bisect.bisect_right(source["times"], t) - 1
        target = source["rows"][index] if index >= 0 and t - source["times"][index] <= 2000 else None
        floor, ref_source, ref_status = source["reference"].label(recording, target, t, policy)
        radio_index = bisect.bisect_right(source["radioTimes"], t) - 1
        radio_target = source["rows"][radio_index] if radio_index >= 0 and t - source["times"][radio_index] <= 2000 else None
        local = selected(radio_target) if radio_target is not None else None
        tuple_ = cell_tuple(local) if local else None
        acquisition_time = radio_acquired_at(radio_target) if radio_target is not None else None
        age = local["ageMillis"] + t - acquisition_time if local and local.get("ageMillis") is not None else None
        if age is not None and age < 0:
            raise ValueError("Future radio measurement cannot become a past observation")
        fix_id = opaque("f", target["locationElapsedRealtimeNanos"]) if target and target.get("locationElapsedRealtimeNanos") is not None else None
        position_age = target["locationAgeMillis"] + t - target["recordedAtMillis"] if target and target.get("locationAgeMillis") is not None else None
        position_ok = bool(target and target.get("lat") is not None and target.get("lon") is not None and target.get("isMockLocation") is False and target.get("accuracyM") is not None and target["accuracyM"] <= 30 and position_age is not None and position_age <= 5000)
        x = 6371000 * math.cos(math.radians(origin[0])) * math.radians(target["lon"] - origin[1]) if position_ok else None
        y = 6371000 * math.radians(target["lat"] - origin[0]) if position_ok else None
        measurement = opaque("m", [tuple_, local.get("timestampMillis")]) if tuple_ and local.get("timestampMillis") is not None else None
        eligible_local = tuple_ is not None and age is not None and age <= cap
        return {
            "t_ms": int(t), "recording": recording, "episode": episode(recording, t),
            "cell": aliases.get(tuple_) if eligible_local else None,
            "floor": floor, "referenceSource": ref_source, "referenceStatus": ref_status,
            "referencePolicy": policy, "measurementId": measurement if eligible_local else None,
            "locationFixId": fix_id, "radioAgeMs": age, "radioAgeCapMs": cap,
            "outcome": "cell_observed" if eligible_local else "local_cell_missing_or_old",
            "localCell": aliases.get(tuple_), "localMeasurementId": measurement,
            "radioRowIndex": radio_index if radio_target is not None else None,
            "radioAcquiredAtMillis": acquisition_time,
            "radioAvailabilityLimit": "acquisition start; read completion timestamp is not exported",
            "targetRowIndex": index if target is not None else None,
            "targetRowGapMs": t - target["recordedAtMillis"] if target else None,
            "positionEligible": position_ok, "x_m": x, "y_m": y,
            "positionAccuracyM": target.get("accuracyM") if target else None, "positionAgeMs": position_age,
            "targetTransport": (target.get("deviceContext") or {}).get("activeTransport") if target else None,
            "sourceRowIndex": source_row,
        }

    target_table = []
    floor_runs = []
    target_summaries = []
    for recording, source in sources.items():
        refs = [observation(recording, math.ceil(radio_acquired_at(r)), i, 30000) for i, r in enumerate(source["rows"])]
        make_segments(refs)
        target_table.extend(refs)
        runs = []
        for row in refs:
            group = row["floor"], row["referenceStatus"], row["referenceSource"]
            if not runs or runs[-1]["group"] != group or row["t_ms"] - runs[-1]["end_ms"] > 5000:
                runs.append({"group": group, "start_ms": row["t_ms"], "end_ms": row["t_ms"], "rows": 1})
            else:
                runs[-1]["end_ms"] = row["t_ms"]
                runs[-1]["rows"] += 1
        floor_runs.extend({"recording": recording, "floor": r["group"][0], "status": r["group"][1], "referenceSource": r["group"][2], "start_ms": r["start_ms"], "end_ms": r["end_ms"], "start": iso(r["start_ms"]), "end": iso(r["end_ms"]), "rows": r["rows"]} for r in runs)
        target_summaries.append({
            "recording": recording, "rows": len(refs),
            "referenceFloors": dict(collections.Counter(r["floor"] or "unknown" for r in refs)),
            "referenceStatus": dict(collections.Counter(r["referenceStatus"] for r in refs)),
            "distinctLocationFixes": len({r["locationFixId"] for r in refs} - {None}),
            "distinctLocalMeasurements": len({r["localMeasurementId"] for r in refs} - {None}),
        })
    dump_lines(output / "target_samples.jsonl", target_table)
    dump(output / "floor_intervals.json", {"policy": POLICIES["primary"], "mapping": dict(zip([f"H{i}" for i in range(5)], FLOORS)), "intervals": floor_runs})

    caller_table = []
    for recording, source in sources.items():
        for index, raw in enumerate(source["caller"]["samples"]):
            tuple_ = caller_tuple(raw)
            packet = timing.get((source["caller"]["sessionId"], raw.get("probeId"))) if tuple_ else None
            if tuple_ and packet is None:
                raise ValueError("CNI observation lacks direct native timing evidence")
            t = packet["t_ms"] if packet else raw.get("finishedAtMillis") or raw["recordedAtMillis"]
            row = observation(recording, t, index, 5000)
            row.update({
                "cell": aliases.get(tuple_), "outcome": raw.get("outcome", "unknown"),
                "measurementId": opaque("p", [source["caller"]["sessionId"], raw.get("probeId") or index]),
                "invite_t_ms": packet["invite_t_ms"] if packet else raw.get("inviteSentAtMillis"),
                "response_t_ms": packet["t_ms"] if packet else None,
                "ordinary_response_t_ms": raw.get("responseReceivedAtMillis") if tuple_ else None,
                "responseTimingSource": "first cell-bearing native provisional response" if packet else "exported attempt completion",
                "nativeResponseSource": packet.get("native_response_source") if packet else None,
                "cohort": "app_export",
            })
            caller_table.append(row)
    caller_table.sort(key=lambda r: r["t_ms"])
    make_segments(caller_table)
    dump_lines(output / "caller_attempts.jsonl", caller_table)

    streams = []
    def add_stream(sid, side, rows, cap=None, interval=None, phase=None, policy="primary", **extra):
        rows.sort(key=lambda r: r["t_ms"])
        make_segments(rows)
        streams.append({"id": sid, "side": side, "recording": "combined", "recordings": list(sources), "intervalSeconds": interval, "phaseSeconds": phase, "radioAgeCapMs": cap, "referencePolicy": policy, "rows": rows, **extra})

    for policy in POLICIES:
        suffix = "" if policy == "primary" else "_reference_" + policy
        callers = []
        for row in caller_table:
            cloned = dict(row)
            source = sources[row["recording"]]
            idx = row["targetRowIndex"]
            cloned["floor"], cloned["referenceSource"], cloned["referenceStatus"] = source["reference"].label(row["recording"], source["rows"][idx] if idx is not None else None, row["t_ms"], policy)
            cloned["referencePolicy"] = policy
            callers.append(cloned)
        add_stream("caller_actual" + suffix, "caller", callers, policy=policy)
        for cap in (5000, 30000) if policy == "primary" else (5000,):
            rows = []
            for recording, source in sources.items():
                seen = set()
                for i, raw in enumerate(source["rows"]):
                    local = selected(raw)
                    ident = (cell_tuple(local), local.get("timestampMillis")) if local else None
                    if ident in seen:
                        continue
                    seen.add(ident)
                    rows.append(observation(recording, math.ceil(radio_acquired_at(raw)), i, cap, policy))
            add_stream(f"local_updates_age{cap // 1000}" + suffix, "local", rows, cap, policy=policy, sampling="first_available_distinct_selected_modem_measurement")
    for cap in (5000, 30000):
        for interval in (10, 30, 60):
            for phase_num in range(4):
                phase = interval * phase_num / 4
                rows = []
                for recording, source in sources.items():
                    start = source["target"]["startedAtMillis"] + phase * 1000
                    end = source["target"]["endedAtMillis"]
                    t = start
                    while t <= end:
                        rows.append(observation(recording, round(t), None, cap))
                        t += interval * 1000
                add_stream(f"local_{interval}s_p{phase_num}_age{cap // 1000}", "local", rows, cap, interval, phase, sampling="latest_past_tick_within_2s")
    recovered_rows = []
    for raw in recovered.get("attempts", []):
        if raw.get("cell") is None or raw.get("t_ms") is None:
            continue
        row = observation("afternoon", raw["t_ms"], None, 5000)
        row.update({"cell": aliases.get(tuple(raw["cell"])), "outcome": "cell_observed", "measurementId": opaque("p", ["recovered", raw["probe_id"]]), "cohort": "native_only_recovered", "invite_t_ms": raw["invite_t_ms"], "response_t_ms": raw["response_t_ms"], "sourceRowIndex": raw.get("response_source")})
        recovered_rows.append(row)
    if recovered_rows:
        add_stream("caller_with_recovered_cni", "caller", [dict(r) for r in caller_table] + recovered_rows, recoveredExtraCni=len(recovered_rows), missingNativeAttemptCompletionTimesNotFabricated=True)
    splits = [
        {"id": "excursion1_to_excursion2", "trainEpisodes": ["excursion1"], "testEpisodes": ["excursion2"], "description": "First excursion trains; second holds out the whole excursion, including unseen higher floors."},
        {"id": "excursion2_to_excursion1", "trainEpisodes": ["excursion2"], "testEpisodes": ["excursion1"], "description": "Second excursion trains; first is held out. Same-day, three-floor test, not independent-date validation."},
        {"id": "afternoon_to_evening4F", "trainEpisodes": ["excursion1", "excursion2"], "testEpisodes": ["evening4F"], "description": "Single-positive-floor stability check; a constant 4F prediction is 100%, so no multi-floor discrimination claim."},
        {"id": "local_to_caller_excursion2_to_excursion1", "trainEpisodes": ["excursion2"], "testEpisodes": ["excursion1"], "trainStreamId": "local_updates_age5", "testStreamId": "caller_actual", "description": "Direct local-to-caller transfer without learning a Cell mapping; whole excursion held out."},
        {"id": "local_to_caller_afternoon_to_evening4F", "trainEpisodes": ["excursion1", "excursion2"], "testEpisodes": ["evening4F"], "trainStreamId": "local_updates_age5", "testStreamId": "caller_actual", "description": "Direct local-to-caller transfer to the single confirmed 4F visit; no multi-floor claim."},
    ]
    metadata = {
        "schemaVersion": 1, "floors": FLOORS, "streams": streams, "splits": splits,
        "referencePolicies": POLICIES, "referenceCentersM": dict(zip(FLOORS, CENTERS)), "referenceCutsM": CUTS,
        "episodeWindows": {e: {"start_ms": a, "end_ms": b, "start": iso(a), "end": iso(b)} for e, (a, b) in WINDOWS.items()},
        "notes": [
            "Floor mapping accepted by user; afternoon timing is altitude-derived. Evening 4F is user-reported.",
            "Floor reference uses offline transition guards; those labels are target-side references, never causal prediction inputs.",
            "Past-only features; reference eligibility is separate from local radio freshness.",
            "All data is one building and one date; no building classification or independently validated floor/metre accuracy claim.",
            "Baseline episode rows outside the declared excursion windows are inventoried but not used by default splits.",
            "Native-only CNI sensitivity adds only observations with known response times, not invented completions for missing attempts.",
            "Primary CNI timestamps come from direct same-transaction native capture; app-derived provisional timestamps are retained separately.",
            "Local causal inputs require radio acquisition start <= query time; read completion is unrecorded, so availability remains approximate.",
        ],
    }
    dump(output / "prepared.json", metadata)
    summaries = []
    for stream in streams:
        rows = stream["rows"]
        by_floor = {}
        for floor in [*FLOORS, None]:
            cohort = [r for r in rows if r["floor"] == floor]
            by_floor[floor or "unknown"] = {
                "rows": len(cohort), "cellRows": sum(r["cell"] is not None for r in cohort),
                "distinctCells": len({r["cell"] for r in cohort} - {None}),
                "distinctMeasurements": len({r["measurementId"] for r in cohort if r["cell"] is not None} - {None}),
                "distinctLocationFixes": len({r["locationFixId"] for r in cohort} - {None}),
            }
        summaries.append({"stream": stream["id"], "side": stream["side"], "rows": len(rows), "byFloor": by_floor})
    dump(output / "preparation_summary.json", {"sourceHashesVerified": len(inventory["sourceFiles"]), "sources": target_summaries, "streams": summaries, "recoveredExtraCni": len(recovered_rows), "acceptedFloorMapping": dict(zip([f"H{i}" for i in range(5)], FLOORS))})
    print(json.dumps({"sourceFilesVerified": len(inventory["sourceFiles"]), "targetSamples": len(target_table), "callerAttempts": len(caller_table), "callerCni": sum(r["cell"] is not None for r in caller_table), "streams": len(streams), "recoveredExtraCni": len(recovered_rows), "targetReferences": target_summaries}, indent=2))
    return metadata


def write_coarse_input(prepared: dict, output: Path):
    """Keep the five-floor task intact and explicitly label the extra diagnostic."""
    coarse = json.loads(json.dumps(prepared))
    coarse["floors"] = ["B1", "upper"]
    coarse["targetDescription"] = "Exploratory coarse vertical region: B1 versus 1F through 4F. Report alongside five-floor task; not a replacement."
    for stream in coarse["streams"]:
        for row in stream["rows"]:
            if row["floor"] is not None:
                row["originalFloor"] = row["floor"]
                row["floor"] = "B1" if row["floor"] == "B1" else "upper"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(coarse, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--captures-root", type=Path, default=Path("captures"))
    parser.add_argument("--batch-root", type=Path, default=Path("captures/batch_20261112"))
    parser.add_argument("--output", type=Path, default=Path("captures/batch_20261112/analysis"))
    parser.add_argument("--coarse-output", type=Path, help="Optional explicit B1-versus-upper diagnostic input")
    args = parser.parse_args()
    prepared = run(args.captures_root, args.batch_root, args.output)
    if args.coarse_output:
        write_coarse_input(prepared, args.coarse_output)
