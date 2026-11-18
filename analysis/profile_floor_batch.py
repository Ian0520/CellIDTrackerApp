#!/usr/bin/env python3
"""Describe anonymous cell/floor support and plot the accepted reference timeline.

Profiles are descriptive, not fitted predictors. The separate candidate-set
evaluation uses TRAIN-only floor sets and explicit held-out episodes. Altitude
references are not independent floor truth; fused coordinates are not used here.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import math
import statistics
from pathlib import Path
from typing import Any


PRIMARY_STREAMS = ("local_updates_age5", "caller_actual")
PRIMARY_SPLITS = ("excursion1_to_excursion2", "excursion2_to_excursion1", "afternoon_to_evening4F")


def _hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _index(rows: list[dict[str, Any]], floors: list[str]) -> tuple[dict[str, list[dict[str, Any]]], set[str]]:
    groups: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        if row.get("measurementId") is not None:
            groups[str(row["measurementId"])].append(row)
    conflicts = {
        measurement for measurement, entries in groups.items()
        if len({row["floor"] for row in entries if row.get("floor") in floors}) > 1
        or len({row["cell"] for row in entries if row.get("cell") is not None}) > 1
    }
    return groups, conflicts


def _labelled_unique(rows: list[dict[str, Any]], floors: list[str], excluded: set[str]) -> list[dict[str, Any]]:
    unique = {}
    for row in sorted(rows, key=lambda item: item["t_ms"]):
        measurement = row.get("measurementId")
        if measurement is None or str(measurement) in excluded:
            continue
        if row.get("floor") in floors and row.get("cell") is not None:
            unique.setdefault(str(measurement), row)
    return list(unique.values())


def profile_stream(stream: dict[str, Any], floors: list[str]) -> dict[str, Any]:
    rows = stream["rows"]
    groups, conflicts = _index(rows, floors)
    labelled = _labelled_unique(rows, floors, conflicts)
    counts: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    by_episode: dict[str, dict[str, collections.Counter]] = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
    for row in labelled:
        counts[row["cell"]][row["floor"]] += 1
    for episode in sorted({row["episode"] for row in rows}):
        for row in _labelled_unique([r for r in rows if r["episode"] == episode], floors, conflicts):
            by_episode[episode][row["cell"]][row["floor"]] += 1
    floor_totals = {floor: sum(counter[floor] for counter in counts.values()) for floor in floors}
    grouped_rows: dict[tuple[str, str, str], list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        grouped_rows[(row["recording"], row["episode"], row.get("floor") or "unknown")].append(row)
    cohorts = []
    for (recording, episode, floor), members in sorted(grouped_rows.items()):
        accepted = [row for row in members if row.get("measurementId") is not None and str(row["measurementId"]) not in conflicts]
        observed = [row for row in accepted if row.get("cell") is not None]
        cohorts.append({
            "recording": recording, "episode": episode, "floor": floor,
            "rows": len(members), "currentNullRows": sum(row.get("cell") is None for row in members),
            "distinctMeasurementIds": len({row["measurementId"] for row in accepted}),
            "distinctCellObservedMeasurementIds": len({row["measurementId"] for row in observed}),
            "distinctCells": len({row["cell"] for row in observed}),
            "rowsMissingMeasurementId": sum(row.get("measurementId") is None for row in members),
            "rowsWithConflictingMeasurementIdExcluded": sum(str(row.get("measurementId")) in conflicts for row in members),
        })
    cells = []
    aliases = sorted({row["cell"] for row in rows if row.get("cell") is not None})
    for cell in aliases:
        counter = counts[cell]
        total = sum(counter.values())
        cells.append({
            "cellAlias": cell,
            "knownLabelDistinctMeasurements": total,
            "floorCounts": {floor: counter[floor] for floor in floors},
            "floorGivenCell": {floor: round(counter[floor] / total, 6) if total else None for floor in floors},
            "cellGivenFloor": {floor: round(counter[floor] / floor_totals[floor], 6) if floor_totals[floor] else None for floor in floors},
            "observedFloorSet": [floor for floor in floors if counter[floor]],
            "ambiguousAcrossFloors": sum(bool(counter[floor]) for floor in floors) > 1,
        })
    recurrence = []
    first, second = by_episode.get("excursion1", {}), by_episode.get("excursion2", {})
    for cell in sorted(set(first) | set(second)):
        a, b = set(first.get(cell, {})), set(second.get(cell, {}))
        recurrence.append({
            "cellAlias": cell, "excursion1Floors": sorted(a), "excursion2Floors": sorted(b),
            "sharedFloors": sorted(a & b), "sameCellSeenInBoth": bool(a and b),
            "exactFloorSetRecurrence": bool(a and b and a == b),
            "floorSetJaccard": round(len(a & b) / len(a | b), 6) if a | b else None,
            "excursion1DistinctMeasurements": sum(first.get(cell, {}).values()),
            "excursion2DistinctMeasurements": sum(second.get(cell, {}).values()),
        })
    a_pairs = {(cell, floor) for cell, counter in first.items() for floor in counter}
    b_pairs = {(cell, floor) for cell, counter in second.items() for floor in counter}
    return {
        "streamId": stream["id"], "side": stream["side"], "rows": len(rows),
        "distinctMeasurementIds": len(groups),
        "conflictingMeasurementIdsExcluded": sorted(conflicts),
        "conflictingMeasurementCount": len(conflicts),
        "knownLabelDistinctCellMeasurements": len(labelled),
        "unknownOnlyMeasurementCount": sum(not any(row.get("floor") in floors for row in entries) for entries in groups.values()),
        "currentNullRows": sum(row.get("cell") is None for row in rows),
        "referenceUnknownRows": sum(row.get("floor") not in floors for row in rows),
        "floorTotals": floor_totals, "cohorts": cohorts, "cells": cells,
        "ambiguousCells": sum(cell["ambiguousAcrossFloors"] for cell in cells),
        "cellsWithKnownFloorSupport": sum(cell["knownLabelDistinctMeasurements"] > 0 for cell in cells),
        "sameCellFloorRecurrence": {
            "excursion1CellFloorPairs": len(a_pairs), "excursion2CellFloorPairs": len(b_pairs),
            "sharedCellFloorPairs": len(a_pairs & b_pairs),
            "sharedFractionOfExcursion1Pairs": round(len(a_pairs & b_pairs) / len(a_pairs), 6) if a_pairs else None,
            "sharedFractionOfExcursion2Pairs": round(len(a_pairs & b_pairs) / len(b_pairs), 6) if b_pairs else None,
            "cells": recurrence,
        },
    }


def _set_metrics(rows: list[dict[str, Any]], floor_sets: dict[str, set[str]], floors: list[str]) -> dict[str, Any]:
    known = [row for row in rows if row.get("floor") in floors]
    sizes, retained_sizes = [], []
    retained = null = unseen = singleton = singleton_retained = 0
    by_floor: dict[str, collections.Counter] = {floor: collections.Counter() for floor in floors}
    for row in known:
        floor = row["floor"]
        by_floor[floor]["references"] += 1
        cell = row.get("cell")
        candidates = floor_sets.get(cell, set()) if cell is not None else set()
        if not candidates:
            null += cell is None
            unseen += cell is not None
            by_floor[floor]["unknown"] += 1
            continue
        size = len(candidates)
        sizes.append(size)
        by_floor[floor]["predicted"] += 1
        if size == 1:
            singleton += 1
        if floor in candidates:
            retained += 1
            retained_sizes.append(size)
            singleton_retained += size == 1
            by_floor[floor]["retained"] += 1
    return {
        "knownReferenceCount": len(known), "predictedCount": len(sizes),
        "unknownCurrentNullCount": null, "unknownUnseenCellCount": unseen,
        "coverage": round(len(sizes) / len(known), 6) if known else None,
        "trueFloorRetentionGivenPrediction": round(retained / len(sizes), 6) if sizes else None,
        "trueFloorRetentionAcrossAllKnownReferences": round(retained / len(known), 6) if known else None,
        "candidateFloorSetSize": {
            "mean": round(statistics.fmean(sizes), 6) if sizes else None,
            "median": statistics.median(sizes) if sizes else None,
            "histogram": {str(size): sizes.count(size) for size in range(1, len(floors) + 1)},
        },
        "retainedCandidateSetMeanSize": round(statistics.fmean(retained_sizes), 6) if retained_sizes else None,
        "singletonPredictionCount": singleton,
        "singletonTrueFloorRetention": round(singleton_retained / singleton, 6) if singleton else None,
        "byFloor": {floor: {key: counts[key] for key in ("references", "predicted", "retained", "unknown")} for floor, counts in by_floor.items()},
    }


def candidate_sets(prepared: dict[str, Any]) -> list[dict[str, Any]]:
    floors = prepared["floors"]
    streams = {stream["id"]: stream for stream in prepared["streams"]}
    results = []
    for split in prepared["splits"]:
        if split["id"] not in PRIMARY_SPLITS and not split.get("trainStreamId"):
            continue
        pairs = [(key, key) for key in PRIMARY_STREAMS] if not split.get("trainStreamId") else [(split["trainStreamId"], split["testStreamId"])]
        for train_id, test_id in pairs:
            train = [row for row in streams[train_id]["rows"] if row["episode"] in split["trainEpisodes"]]
            test = [row for row in streams[test_id]["rows"] if row["episode"] in split["testEpisodes"]]
            train_groups, train_conflicts = _index(train, floors)
            test_groups, test_conflicts = _index(test, floors)
            shared = set(train_groups) & set(test_groups)
            training = _labelled_unique(train, floors, train_conflicts | shared)
            floor_sets: dict[str, set[str]] = collections.defaultdict(set)
            for row in training:
                floor_sets[row["cell"]].add(row["floor"])
            test_clean = [row for row in test if str(row.get("measurementId")) not in test_conflicts]
            unique_test = {}
            for row in sorted(test_clean, key=lambda entry: entry["t_ms"]):
                if row.get("measurementId") is not None and row.get("floor") in floors:
                    unique_test.setdefault(str(row["measurementId"]), row)
            results.append({
                "splitId": split["id"], "trainStreamId": train_id, "testStreamId": test_id,
                "trainingDistinctLabelledCellMeasurements": len(training),
                "trainingConflictingMeasurementCount": len(train_conflicts),
                "testConflictingMeasurementCountExcluded": len(test_conflicts),
                "sharedMeasurementIdsExcludedFromTraining": len(shared),
                "trainingSupportedFloors": [floor for floor in floors if any(floor in values for values in floor_sets.values())],
                "testKnownReferenceRowsMissingMeasurementId": sum(row.get("floor") in floors and row.get("measurementId") is None for row in test_clean),
                "trainingFloorSetsByCell": {cell: sorted(values) for cell, values in sorted(floor_sets.items())},
                "rowWeighted": _set_metrics(test_clean, floor_sets, floors),
                "distinctMeasurementWeighted": _set_metrics(list(unique_test.values()), floor_sets, floors),
            })
    return results


def _unknown_ranges(rows: list[dict[str, Any]], floors: list[str]) -> list[tuple[int, int]]:
    ordered = sorted(rows, key=lambda row: row["t_ms"])
    ranges = []
    start = None
    for index, row in enumerate(ordered):
        if row.get("floor") not in floors and start is None:
            start = row["t_ms"]
        if start is not None and (row.get("floor") in floors or index == len(ordered) - 1):
            ranges.append((start, row["t_ms"]))
            start = None
    return ranges


def plot_heatmaps(profiles: list[dict[str, Any]], floors: list[str], destination: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np

    aliases = sorted({entry["cellAlias"] for profile in profiles for entry in profile["cells"]})
    figure, axes = plt.subplots(1, len(profiles), figsize=(13, max(8, len(aliases) * .28 + 2)), layout="constrained")
    for axis, profile in zip(axes, profiles):
        indexed = {entry["cellAlias"]: entry for entry in profile["cells"]}
        matrix = np.zeros((len(aliases), len(floors)))
        counts = np.zeros_like(matrix, dtype=int)
        for i, alias in enumerate(aliases):
            entry = indexed.get(alias, {})
            for j, floor in enumerate(floors):
                matrix[i, j] = (entry.get("floorGivenCell", {}).get(floor) or 0)
                counts[i, j] = entry.get("floorCounts", {}).get(floor, 0)
        image = axis.imshow(matrix, vmin=0, vmax=1, cmap="Blues", aspect="auto")
        axis.set_xticks(range(len(floors)), [f"{floor}\nN={profile['floorTotals'][floor]}" for floor in floors])
        axis.set_yticks(range(len(aliases)), [f"{alias}  N={sum(counts[i])}" for i, alias in enumerate(aliases)], fontsize=8)
        for i in range(len(aliases)):
            for j in range(len(floors)):
                if counts[i, j]:
                    axis.text(j, i, str(counts[i, j]), ha="center", va="center", color="white" if matrix[i, j] > .55 else "black", fontsize=8)
        axis.set_title(f"{profile['side'].capitalize()} observations\n{profile['knownLabelDistinctCellMeasurements']} distinct labelled measurements; {profile['conflictingMeasurementCount']} conflicts excluded")
        axis.set_xlabel("Accepted reference floor")
        axis.set_ylabel("Anonymous full-cell tuple alias; N = known-floor measurements")
    figure.colorbar(image, ax=list(axes), label="Within-cell floor fraction P(reference floor | cell); numbers are N", shrink=.65)
    figure.suptitle("Descriptive cell/floor profiles — all visits combined\nNot held-out accuracy; unknown floor references omitted", fontsize=14)
    figure.savefig(destination, dpi=170)
    plt.close(figure)


def plot_timeline(prepared: dict[str, Any], analysis_root: Path, destination: Path) -> dict[str, Any]:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    floors = prepared["floors"]
    target_path = analysis_root / "target_samples.jsonl"
    target_rows = [json.loads(line) for line in target_path.read_text().splitlines() if line.strip()]
    caller = next(stream for stream in prepared["streams"] if stream["id"] == "caller_actual")["rows"]
    recordings = sorted({row["recording"] for row in target_rows}, key=lambda recording: min(row["t_ms"] for row in target_rows if row["recording"] == recording))
    raw_sources = []
    for source in (analysis_root.parent / "target").glob("*.json"):
        document = json.loads(source.read_text())
        samples = document.get("samples", [])
        if samples:
            raw_sources.append((source, samples, min(row["recordedAtMillis"] for row in samples), max(row["recordedAtMillis"] for row in samples)))
    figure, axes = plt.subplots(3, len(recordings), figsize=(16, 9), sharex="col", layout="constrained", gridspec_kw={"height_ratios": [2.2, 1.2, 1]})
    provenance = {"targetPreparedRowsSha256": _hash(target_path), "rawAltitudeSourcesSha256": []}
    colours = plt.get_cmap("tab10")
    for column, recording in enumerate(recordings):
        refs = sorted([row for row in target_rows if row["recording"] == recording], key=lambda row: row["t_ms"])
        start, end = refs[0]["t_ms"], refs[-1]["t_ms"]
        raw = max(raw_sources, key=lambda source: max(0, min(end, source[3]) - max(start, source[2])))
        provenance["rawAltitudeSourcesSha256"].append(_hash(raw[0]))
        fixes = {}
        for sample in raw[1]:
            identity = sample.get("locationElapsedRealtimeNanos")
            height = sample.get("altitudeM")
            timestamp = sample.get("locationFixTimeMillis")
            if identity is not None and height is not None and timestamp is not None and sample.get("isMockLocation") is False:
                fixes.setdefault(identity, (timestamp, height))
        points = sorted(fixes.values())
        top, middle, bottom = axes[:, column]
        top.plot([(timestamp - start) / 60000 for timestamp, _ in points], [height for _, height in points], color="black", linewidth=.8, label="Distinct fused altitude fixes")
        for floor, height in prepared["referenceCentersM"].items():
            top.axhline(height, color="steelblue", linewidth=.5, linestyle="--", alpha=.65)
            top.text(.99, height, floor, transform=top.get_yaxis_transform(), ha="right", va="bottom", fontsize=8)
        top.set_title(f"{recording.capitalize()} — split units and reference availability")
        top.set_ylabel("Fused altitude (m)\nDiagnostic, not independent floor truth")
        top.grid(alpha=.2)
        for index, floor in enumerate(floors):
            times = [(row["t_ms"] - start) / 60000 for row in refs if row.get("floor") == floor]
            middle.scatter(times, [index] * len(times), color=colours(index), s=3, rasterized=True)
        middle.set_yticks(range(len(floors)), floors)
        middle.set_ylim(-.6, len(floors) - .4)
        middle.set_ylabel("Accepted floor reference")
        middle.grid(alpha=.2)
        probes = [row for row in caller if row["recording"] == recording]
        for status, colour, name in [(1, "forestgreen", "CNI observed"), (0, "firebrick", "No CNI")]:
            times = [(row["t_ms"] - start) / 60000 for row in probes if int(row.get("cell") is not None) == status]
            bottom.scatter(times, [status] * len(times), color=colour, s=12, alpha=.7, marker="|", label=f"{name}: N={len(times)}")
        bottom.set_yticks([0, 1], ["No CNI", "CNI"])
        bottom.set_ylim(-.6, 1.6)
        bottom.set_ylabel("Actual caller attempts")
        bottom.set_xlabel("Minutes from recording start")
        bottom.legend(loc="upper right", fontsize=8)
        for low, high in _unknown_ranges(refs, floors):
            for axis in (top, middle, bottom):
                axis.axvspan((low - start) / 60000, (high - start) / 60000, color="gray", alpha=.2, linewidth=0)
        for index, (name, bounds) in enumerate(prepared["episodeWindows"].items()):
            low, high = max(start, bounds["start_ms"]), min(end, bounds["end_ms"])
            if low < high:
                top.axvspan((low - start) / 60000, (high - start) / 60000, color=["royalblue", "darkorange"][index % 2], alpha=.09)
                top.text((low + high - 2 * start) / 120000, .98, name.replace("excursion", "E"), transform=top.get_xaxis_transform(), ha="center", va="top", fontsize=10)
        if recording == "evening":
            middle.text(.01, .98, "4F: user-reported whole visit", transform=middle.transAxes, ha="left", va="top", fontsize=9)
        for axis in (top, middle, bottom):
            axis.set_xlim(0, (end - start) / 60000)
    figure.suptitle("Height-derived/user-supported floor reference and caller availability\nGray = unknown/guard reference; E1/E2 are whole-excursion split units, not independent dates", fontsize=14)
    figure.savefig(destination, dpi=170)
    plt.close(figure)
    return provenance


def run(prepared_path: Path, output_root: Path) -> dict[str, Any]:
    prepared = json.loads(prepared_path.read_text())
    indexed = {stream["id"]: stream for stream in prepared["streams"]}
    profiles = [profile_stream(indexed[key], prepared["floors"]) for key in PRIMARY_STREAMS]
    output_root.mkdir(parents=True, exist_ok=True)
    heatmap = output_root / "CELL_FLOOR_HEATMAP.png"
    timeline = output_root / "HEIGHT_FLOOR_CALLER_TIMELINE.png"
    plot_heatmaps(profiles, prepared["floors"], heatmap)
    timeline_provenance = plot_timeline(prepared, prepared_path.parent, timeline)
    result = {
        "schemaVersion": 1, "preparedInputSha256": _hash(prepared_path),
        "provenance": timeline_provenance,
        "referenceCaveat": "Afternoon floors are altitude-derived and user-supported, with transition guards; evening 4F is user-reported. These are not independent manual floor labels.",
        "profilePolicy": "Distinct measurementId per known label; any ID with conflicting known floors or cells is excluded from descriptive labelled profiles. Unknown-only IDs and missing IDs remain reported.",
        "candidateSetPolicy": "TRAIN-only current-cell observed floor sets. Training conflicts are determined using TRAIN labels only. Test conflicts affect evaluation only; shared train/test measurement IDs are removed from TRAIN. Current null/unseen returns unknown, not an empty confident prediction.",
        "profiles": profiles, "candidateFloorSets": candidate_sets(prepared),
        "plots": {"cellFloorHeatmap": heatmap.name, "heightFloorCallerTimeline": timeline.name},
        "scope": "One building, one date. Descriptive cell/floor associations are not held-out accuracy or building discrimination evidence.",
    }
    (output_root / "CELL_FLOOR_PROFILES.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    result = run(args.prepared, args.output_dir or args.prepared.parent)
    print(f"Saved {len(result['profiles'])} descriptive profiles, {len(result['candidateFloorSets'])} TRAIN-only candidate-set comparisons and two scientific plots.")


if __name__ == "__main__":
    main()
