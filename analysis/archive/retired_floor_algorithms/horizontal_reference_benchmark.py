#!/usr/bin/env python3
"""Compare causal cell-position estimates with qualified, noisy fused references.

This benchmark measures discrepancy to Android fused fixes, not actual geographic
accuracy. It uses only training coordinates for model fitting and never writes
cell identifiers, coordinates, fix identifiers, or individual predictions.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Any


METHODS = ("constant_train_median", "cell_train_median", "history_60s", "history_120s")
REFERENCE_CAVEAT = (
    "All metre-valued results are discrepancies to qualified Android fused-location "
    "references, not measured physical-position accuracy. Reported accuracy and age "
    "filters do not establish indoor ground truth. Distinct fix identifiers remove "
    "duplicate fixes but do not prove independent location errors."
)


def _identity(value: Any) -> str | None:
    return None if value is None else json.dumps(value, sort_keys=True, separators=(",", ":"))


def _finite(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def qualified_position(row: dict[str, Any]) -> tuple[float, float] | None:
    if row.get("positionEligible") is not True or row.get("locationFixId") is None:
        return None
    if not _finite(row.get("x_m")) or not _finite(row.get("y_m")):
        return None
    return float(row["x_m"]), float(row["y_m"])


def _median_position(points: list[tuple[float, float]]) -> tuple[float, float] | None:
    if not points:
        return None
    return statistics.median(p[0] for p in points), statistics.median(p[1] for p in points)


def _percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    offset = fraction * (len(ordered) - 1)
    lower = math.floor(offset)
    upper = math.ceil(offset)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (offset - lower)


def _rounded(value: float | None) -> float | None:
    return round(value, 3) if value is not None else None


def _distribution(values: list[float]) -> dict[str, float | None]:
    return {
        "medianM": _rounded(_percentile(values, 0.5)),
        "p90M": _rounded(_percentile(values, 0.9)),
        "p95M": _rounded(_percentile(values, 0.95)),
    }


def _reference_quality(rows: list[dict[str, Any]]) -> dict[str, Any]:
    result = {}
    for field in ("positionAccuracyM", "positionAgeMs"):
        values = [float(row[field]) for row in rows if _finite(row.get(field))]
        result[field] = {
            "count": len(values),
            "median": _rounded(_percentile(values, 0.5)),
            "p90": _rounded(_percentile(values, 0.9)),
            "p95": _rounded(_percentile(values, 0.95)),
        }
    return result


@dataclass
class ReferenceModel:
    constant: tuple[float, float] | None
    cell_centres: dict[str, tuple[float, float]]
    statistics: dict[str, Any]


def fit_model(train_rows: list[dict[str, Any]], test_rows: list[dict[str, Any]]) -> ReferenceModel:
    """Deduplicate training fixes and exclude every fix identifier present in test."""
    test_fixes = {_identity(row.get("locationFixId")) for row in test_rows}
    test_fixes.discard(None)
    unique_positions: dict[str, tuple[float, float]] = {}
    unique_cell_positions: dict[tuple[str, str], tuple[float, float]] = {}
    excluded_rows = 0
    excluded_fixes: set[str] = set()
    qualified_rows = 0
    for row in train_rows:
        position = qualified_position(row)
        if position is None:
            continue
        qualified_rows += 1
        fix = _identity(row["locationFixId"])
        assert fix is not None
        if fix in test_fixes:
            excluded_rows += 1
            excluded_fixes.add(fix)
            continue
        unique_positions.setdefault(fix, position)
        cell = _identity(row.get("cell"))
        if cell is not None:
            unique_cell_positions.setdefault((fix, cell), position)
    grouped: dict[str, list[tuple[float, float]]] = collections.defaultdict(list)
    for (_, cell), position in unique_cell_positions.items():
        grouped[cell].append(position)
    return ReferenceModel(
        constant=_median_position(list(unique_positions.values())),
        cell_centres={cell: _median_position(points) for cell, points in grouped.items()},
        statistics={
            "qualifiedTrainingRowsBeforeLeakageExclusion": qualified_rows,
            "sharedFixRowsExcludedFromTraining": excluded_rows,
            "sharedFixIdsExcludedFromTraining": len(excluded_fixes),
            "distinctTrainingReferenceFixes": len(unique_positions),
            "distinctTrainingFixCellPairs": len(unique_cell_positions),
            "trainedCells": len(grouped),
            "deduplication": "constant: locationFixId; cell centres: locationFixId + cell",
        },
    )


def predict_rows(rows: list[dict[str, Any]], model: ReferenceModel) -> list[dict[str, Any]]:
    """Use current/past radio observations, resetting history at every boundary."""
    ordered = sorted(enumerate(rows), key=lambda pair: (pair[1]["t_ms"], pair[0]))
    history: collections.deque[tuple[float, tuple[float, float]]] = collections.deque()
    seen_measurements: set[str] = set()
    previous_boundary: tuple[Any, ...] | None = None
    output: list[dict[str, Any]] = []
    for original_index, row in ordered:
        timestamp = row["t_ms"]
        if not _finite(timestamp):
            raise ValueError("All stream rows must have finite t_ms timestamps")
        boundary = tuple(_identity(row.get(key)) for key in ("recording", "episode", "segment"))
        if boundary != previous_boundary:
            history.clear()
            seen_measurements.clear()
            previous_boundary = boundary
        while history and history[0][0] < timestamp - 120_000:
            history.popleft()
        cell = _identity(row.get("cell"))
        centre = model.cell_centres.get(cell) if cell is not None else None
        measurement = _identity(row.get("measurementId"))
        # Missing measurement IDs cannot be deduplicated; the preparation normally supplies them.
        measurement = measurement if measurement is not None else f"missing-row-{original_index}"
        if centre is not None and measurement not in seen_measurements:
            history.append((timestamp, centre))
            seen_measurements.add(measurement)
        predictions: dict[str, tuple[float, float] | None] = {
            "constant_train_median": model.constant,
            "cell_train_median": centre,
        }
        for seconds in (60, 120):
            points = [point for observed_at, point in history if observed_at >= timestamp - seconds * 1000]
            predictions[f"history_{seconds}s"] = (
                (statistics.fmean(p[0] for p in points), statistics.fmean(p[1] for p in points))
                if cell is not None and points else None
            )
        output.append({"row": row, "predictions": predictions})
    return output


def _summarize_errors(errors: dict[int, float], test_rows: list[dict[str, Any]], total: int) -> dict[str, Any]:
    by_fix: dict[str, list[float]] = collections.defaultdict(list)
    for index, discrepancy in errors.items():
        by_fix[_identity(test_rows[index]["locationFixId"])].append(discrepancy)
    return {
        "predictedRows": len(errors),
        "abstainedRows": total - len(errors),
        "coverageOfQualifiedReferenceRows": round(len(errors) / total, 6) if total else None,
        "distinctPredictedReferenceFixes": len(by_fix),
        "rowWeightedDiscrepancy": _distribution(list(errors.values())),
        "fixBalancedDiscrepancy": _distribution([statistics.median(group) for group in by_fix.values()]),
    }


def evaluate(rows: list[dict[str, Any]], model: ReferenceModel) -> dict[str, Any]:
    predicted = predict_rows(rows, model)
    ordered_rows = [entry["row"] for entry in predicted]
    eligible = {i for i, row in enumerate(ordered_rows) if qualified_position(row) is not None}
    errors: dict[str, dict[int, float]] = {method: {} for method in METHODS}
    for index in eligible:
        reference = qualified_position(ordered_rows[index])
        assert reference is not None
        for method, prediction in predicted[index]["predictions"].items():
            if prediction is not None:
                errors[method][index] = math.hypot(prediction[0] - reference[0], prediction[1] - reference[1])
    comparisons = {}
    pairs = [(method, "constant_train_median") for method in METHODS[1:]]
    pairs += [(method, "cell_train_median") for method in METHODS[2:]]
    for method, baseline in pairs:
        matched = errors[method].keys() & errors[baseline].keys()
        method_errors = {index: errors[method][index] for index in matched}
        baseline_errors = {index: errors[baseline][index] for index in matched}
        method_median = _percentile(list(method_errors.values()), 0.5)
        baseline_median = _percentile(list(baseline_errors.values()), 0.5)
        paired_reduction = [baseline_errors[index] - method_errors[index] for index in matched]
        by_fix: dict[str, list[int]] = collections.defaultdict(list)
        for index in matched:
            by_fix[_identity(ordered_rows[index]["locationFixId"])].append(index)
        model_fix_errors = [statistics.median(method_errors[i] for i in indices) for indices in by_fix.values()]
        baseline_fix_errors = [statistics.median(baseline_errors[i] for i in indices) for indices in by_fix.values()]
        comparisons[f"{method}_vs_{baseline}"] = {
            "matchedRows": len(matched),
            "matchedDistinctReferenceFixes": len({_identity(ordered_rows[i]["locationFixId"]) for i in matched}),
            "matchedCoverageOfQualifiedReferenceRows": round(len(matched) / len(eligible), 6) if eligible else None,
            "modelDiscrepancy": _distribution(list(method_errors.values())),
            "baselineDiscrepancy": _distribution(list(baseline_errors.values())),
            "modelFixBalancedDiscrepancy": _distribution(model_fix_errors),
            "baselineFixBalancedDiscrepancy": _distribution(baseline_fix_errors),
            "differenceOfMedianDiscrepanciesM": _rounded(
                baseline_median - method_median if baseline_median is not None and method_median is not None else None
            ),
            "medianPairedDiscrepancyReductionM": _rounded(_percentile(paired_reduction, 0.5)),
            "medianFixBalancedPairedDiscrepancyReductionM": _rounded(_percentile(
                [baseline_value - model_value for baseline_value, model_value in zip(baseline_fix_errors, model_fix_errors)], 0.5
            )),
            "interpretation": "Positive reduction means smaller fused-reference discrepancy, not proven true-position gain.",
        }
    current_null = sum(ordered_rows[i].get("cell") is None for i in eligible)
    current_unseen = sum(
        ordered_rows[i].get("cell") is not None
        and _identity(ordered_rows[i]["cell"]) not in model.cell_centres for i in eligible
    )
    positions_by_fix = {}
    for index in eligible:
        positions_by_fix.setdefault(_identity(ordered_rows[index]["locationFixId"]), qualified_position(ordered_rows[index]))
    positions = list(positions_by_fix.values())
    footprint = (
        math.hypot(max(p[0] for p in positions) - min(p[0] for p in positions),
                   max(p[1] for p in positions) - min(p[1] for p in positions))
        if positions else None
    )
    return {
        "testRows": len(rows),
        "currentNullRows": sum(row.get("cell") is None for row in ordered_rows),
        "unseenCurrentCellRows": sum(row.get("cell") is not None and _identity(row["cell"]) not in model.cell_centres for row in ordered_rows),
        "qualifiedReferenceRows": len(eligible),
        "distinctQualifiedReferenceFixes": len(positions_by_fix),
        "qualifiedCurrentNullRows": current_null,
        "qualifiedUnseenCurrentCellRows": current_unseen,
        "qualifiedRowsMissingMeasurementId": sum(ordered_rows[i].get("measurementId") is None for i in eligible),
        "qualifiedReferenceBoundingBoxDiagonalM": _rounded(footprint),
        "qualifiedReferenceQuality": _reference_quality([ordered_rows[i] for i in eligible]),
        "methods": {method: _summarize_errors(values, ordered_rows, len(eligible)) for method, values in errors.items()},
        "sameRowComparisons": comparisons,
    }


def run(prepared: dict[str, Any]) -> dict[str, Any]:
    streams = prepared.get("streams", [])
    by_id = {stream["id"]: stream for stream in streams}
    if len(by_id) != len(streams):
        raise ValueError("Stream IDs must be unique")
    results = []
    for split in prepared.get("splits", []):
        training_episodes = {_identity(value) for value in split["trainEpisodes"]}
        testing_episodes = {_identity(value) for value in split["testEpisodes"]}
        if training_episodes & testing_episodes:
            raise ValueError("Training and testing episodes must be disjoint")
        train_id, test_id = split.get("trainStreamId"), split.get("testStreamId")
        pairs = [(train_id or test_id, test_id or train_id)] if train_id or test_id else [(key, key) for key in by_id]
        for train_id, test_id in pairs:
            if train_id not in by_id or test_id not in by_id:
                raise ValueError("Split references a nonexistent stream")
            train_stream, test_stream = by_id[train_id], by_id[test_id]
            training = [row for row in train_stream["rows"] if _identity(row.get("episode")) in training_episodes]
            testing = [row for row in test_stream["rows"] if _identity(row.get("episode")) in testing_episodes]
            if not training or not testing:
                continue
            model = fit_model(training, testing)
            result = {
                "splitId": split["id"],
                "description": split.get("description", ""),
                "trainStreamId": train_id,
                "testStreamId": test_id,
                "trainRows": len(training),
                "trainStream": {key: train_stream.get(key) for key in ("side", "intervalSeconds", "phaseSeconds", "radioAgeCapMs")},
                "testStream": {key: test_stream.get(key) for key in ("side", "intervalSeconds", "phaseSeconds", "radioAgeCapMs")},
                "training": model.statistics,
                "evaluation": evaluate(testing, model),
            }
            results.append(result)
    return {
        "schemaVersion": 1,
        "benchmark": "exploratory_horizontal_fused_reference",
        "referenceCaveat": REFERENCE_CAVEAT,
        "policy": {
            "trainingPositions": "Qualified TRAIN fused fixes only; shared train/test fix IDs removed from TRAIN.",
            "positionEligibility": "Preparation-supplied positionEligible plus finite coordinates and a locationFixId.",
            "cellModel": "Componentwise TRAIN position median for each cell; no floor labels as features.",
            "constantModel": "Componentwise TRAIN median across distinct reference fixes, including current-null rows.",
            "historyModel": "Average trained cell centres seen in the past 60/120 seconds including current; deduplicated measurementId.",
            "historyReset": "Recording, episode and segment boundary; every split starts empty.",
            "historyAbstention": "Current null always abstains; unseen current cell may use earlier known cells still in the window.",
            "historyMissingMeasurementId": "Fallback treats each row as separate; counts are reported.",
            "fixBalancedSummary": "One median discrepancy per distinct fix, then distribution over those fix medians.",
            "comparison": "Same qualified test rows predicted by both methods; coverage and abstentions remain explicit.",
            "referenceFootprint": "Bounding-box diagonal of qualified test reference fixes; descriptive, not actual travelled distance.",
        },
        "results": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    prepared = json.loads(args.prepared.read_text())
    result = run(prepared)
    destination = args.output or args.prepared.with_name("horizontal_reference_results.json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print(f"Saved aggregate noisy-fused-reference comparisons: {len(result['results'])} split/stream results.")


if __name__ == "__main__":
    main()
