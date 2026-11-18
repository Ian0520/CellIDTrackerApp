#!/usr/bin/env python3
"""Frozen, causal cell-history baselines for an explicitly prepared floor audit.

This module consumes prepared observations; it neither derives floor references
nor repairs gaps. Results are descriptive row-weighted scores, without confidence
intervals or claims that neighboring rows are independent experiments.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict, deque
from dataclasses import dataclass
import json
from pathlib import Path
import statistics
from typing import Any, Iterable


LOOKBACK_MS = (60_000, 120_000)
BASE_METHODS = ("majority_all_opportunities", "elapsed_time_prior", "majority", "single_cell_lookup")
Row = dict[str, Any]


def method_names() -> list[str]:
    return list(BASE_METHODS) + [
        name
        for window in LOOKBACK_MS
        for name in (f"window_counts_{window // 1000}s", f"ordered_transition_{window // 1000}s")
    ]


def ordered_rows(rows: Iterable[Row]) -> list[Row]:
    return sorted(rows, key=lambda row: row["t_ms"])


def episode_start_times(rows: list[Row]) -> dict[str, int]:
    starts: dict[str, int] = {}
    for row in rows:
        starts[row["episode"]] = min(starts.get(row["episode"], row["t_ms"]), row["t_ms"])
    return starts


def unit_counts(rows: list[Row]) -> dict[str, int]:
    result = {"rows": len(rows)}
    for field, name in (("measurementId", "measurement"), ("locationFixId", "location_fix")):
        result[f"unique_{name}_ids"] = len({row[field] for row in rows if row.get(field) is not None})
        result[f"rows_without_{name}_id"] = sum(row.get(field) is None for row in rows)
    return result


def deduplicate_training(rows: list[Row]) -> tuple[list[int], dict[str, Any]]:
    """Exclude conflicting IDs, then retain the earliest labeled occurrence.

    A missing measurement ID is not evidence that two rows share a measurement:
    those rows remain separate and their missing provenance is reported.
    """
    grouped: dict[tuple[str, Any], list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        identity = row.get("measurementId")
        grouped[("id", identity) if identity is not None else ("row", index)].append(index)
    selected: list[int] = []
    conflicts = Counter()
    excluded_rows = 0
    for key, indices in grouped.items():
        floors = {rows[i]["floor"] for i in indices if rows[i].get("floor") is not None}
        cells = {rows[i]["cell"] for i in indices if rows[i].get("cell") is not None}
        floor_conflict = len(floors) > 1
        cell_conflict = len(cells) > 1
        if key[0] == "id" and (floor_conflict or cell_conflict):
            conflicts["measurement_ids"] += 1
            conflicts["floor_conflict_ids"] += floor_conflict
            conflicts["cell_conflict_ids"] += cell_conflict
            excluded_rows += len(indices)
            continue
        labeled = [i for i in indices if rows[i].get("floor") is not None]
        if labeled:
            selected.append(labeled[0])
    selected.sort()
    return selected, {
        "input": unit_counts(rows),
        "reference_eligible_rows": sum(row.get("floor") is not None for row in rows),
        "retained_unique_labeled_evidence": unit_counts([rows[i] for i in selected]),
        "excluded_conflicting_measurement_ids": conflicts["measurement_ids"],
        "excluded_floor_conflict_ids": conflicts["floor_conflict_ids"],
        "excluded_cell_conflict_ids": conflicts["cell_conflict_ids"],
        "excluded_conflicting_rows": excluded_rows,
        "labeled_rows_not_retained": sum(row.get("floor") is not None for row in rows) - len(selected),
    }


@dataclass
class HistoryContext:
    elapsed_ms: int
    points: list[Row]

    def previous_different_cell(self, current_cell: str) -> str | None:
        for point in reversed(self.points):
            if point["cell"] != current_cell:
                return point["cell"]
        return None


def history_contexts(rows: list[Row], window_ms: int) -> list[HistoryContext]:
    """Only preceding/current rows from this partition and reset segment enter.

    Repeated non-null measurement IDs contribute at most one history point per
    window. Its latest available occurrence is retained. Null-cell opportunities
    advance elapsed time but are not fabricated into cell observations.
    """
    queues: dict[tuple[str, str], deque[tuple[int, Row]]] = defaultdict(deque)
    starts: dict[tuple[str, str], int] = {}
    contexts = []
    for index, row in enumerate(rows):
        key = (row["episode"], row["segment"])
        starts.setdefault(key, row["t_ms"])
        queue = queues[key]
        while queue and queue[0][1]["t_ms"] < row["t_ms"] - window_ms:
            queue.popleft()
        if row.get("cell") is not None:
            queue.append((index, row))
        seen: set[tuple[str, Any]] = set()
        points = []
        for previous_index, previous in reversed(queue):
            identity = previous.get("measurementId")
            unique = ("id", identity) if identity is not None else ("row", previous_index)
            if unique not in seen:
                seen.add(unique)
                points.append(previous)
        contexts.append(HistoryContext(row["t_ms"] - starts[key], list(reversed(points))))
    return contexts


@dataclass
class Model:
    floors: list[str]
    floor_counts: Counter
    cell_counts: dict[str, Counter]
    pair_counts: dict[int, dict[tuple[str, str], Counter]]
    training_audit: dict[str, Any]
    elapsed_time_counts: dict[int, Counter]

    def choose(self, scores: dict[str, float] | Counter) -> str | None:
        if not scores or max(scores.values(), default=0) <= 0:
            return None
        peak = max(scores.values())
        ties = [floor for floor in self.floors if abs(scores.get(floor, 0) - peak) < 1e-12]
        return max(ties, key=lambda floor: (self.floor_counts[floor], -self.floors.index(floor)))

    def single(self, cell: str) -> str | None:
        return self.choose(self.cell_counts.get(cell, Counter()))

    def predict(
        self, row: Row, contexts: dict[int, HistoryContext], elapsed_episode_ms: int = 0,
    ) -> dict[str, str | None]:
        predictions: dict[str, str | None] = dict.fromkeys(method_names())
        # The unconditional opportunity prior is explicitly separate from methods
        # that require a current cell observation.
        predictions["majority_all_opportunities"] = self.choose(self.floor_counts)
        if self.elapsed_time_counts:
            target_bin = elapsed_episode_ms // 60_000
            nearest_bin = min(self.elapsed_time_counts, key=lambda k: (abs(k - target_bin), k))
            predictions["elapsed_time_prior"] = self.choose(self.elapsed_time_counts[nearest_bin])
        cell = row.get("cell")
        if cell is None:
            return predictions
        predictions["majority"] = self.choose(self.floor_counts)
        predictions["single_cell_lookup"] = self.single(cell)
        for window_ms in LOOKBACK_MS:
            context = contexts[window_ms]
            scores: dict[str, float] = defaultdict(float)
            contributing_points = 0
            for point in context.points:
                counts = self.cell_counts.get(point["cell"])
                if counts:
                    contributing_points += 1
                    total = sum(counts.values())
                    for floor, count in counts.items():
                        scores[floor] += count / total
            if contributing_points:
                scores = {floor: value / contributing_points for floor, value in scores.items()}
            predictions[f"window_counts_{window_ms // 1000}s"] = self.choose(scores)
            previous = context.previous_different_cell(cell)
            pairs = self.pair_counts[window_ms].get((previous, cell)) if previous is not None else None
            predictions[f"ordered_transition_{window_ms // 1000}s"] = (
                self.choose(pairs) if pairs else self.single(cell)
            )
        return predictions


def fit(rows: list[Row], floors: list[str], episode_starts: dict[str, int] | None = None) -> Model:
    indices, audit = deduplicate_training(rows)
    floor_counts: Counter = Counter()
    cells: dict[str, Counter] = defaultdict(Counter)
    pairs: dict[int, dict[tuple[str, str], Counter]] = {
        window: defaultdict(Counter) for window in LOOKBACK_MS
    }
    contexts = {window: history_contexts(rows, window) for window in LOOKBACK_MS}
    starts = episode_starts if episode_starts is not None else episode_start_times(rows)
    elapsed_counts: dict[int, Counter] = defaultdict(Counter)
    for index in indices:
        row = rows[index]
        floor_counts[row["floor"]] += 1
        elapsed_counts[(row["t_ms"] - starts[row["episode"]]) // 60_000][row["floor"]] += 1
        if row.get("cell") is None:
            continue
        cells[row["cell"]][row["floor"]] += 1
        for window in LOOKBACK_MS:
            previous = contexts[window][index].previous_different_cell(row["cell"])
            if previous is not None:
                pairs[window][(previous, row["cell"])][row["floor"]] += 1
    audit["floor_evidence_counts"] = dict(floor_counts)
    audit["cell_floor_evidence_rows"] = sum(sum(v.values()) for v in cells.values())
    audit["cell_floor_evidence_counts"] = dict(Counter(
        floor for index in indices if rows[index].get("cell") is not None
        for floor in [rows[index]["floor"]]
    ))
    audit["unique_cells_with_labeled_training_evidence"] = len(cells)
    audit["ordered_pair_contexts"] = {f"{w // 1000}s": len(pairs[w]) for w in LOOKBACK_MS}
    audit["known_episode_elapsed_time_bins"] = len(elapsed_counts)
    return Model(floors, floor_counts, dict(cells), pairs, audit, dict(elapsed_counts))


def fraction(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def score_rows(rows: list[Row], predictions: list[str | None], floors: list[str]) -> dict[str, Any]:
    """All input rows must have references; abstention is a miss overall."""
    confusion = {floor: {other: 0 for other in floors + ["UNKNOWN"]} for floor in floors}
    actual = Counter(row["floor"] for row in rows)
    predicted_by_class: Counter = Counter()
    correct_by_class: Counter = Counter()
    for row, prediction in zip(rows, predictions):
        confusion[row["floor"]][prediction if prediction is not None else "UNKNOWN"] += 1
        if prediction is not None:
            predicted_by_class[row["floor"]] += 1
        if prediction == row["floor"]:
            correct_by_class[row["floor"]] += 1
    covered = [row for row, prediction in zip(rows, predictions) if prediction is not None]
    n_correct = sum(correct_by_class.values())
    recalls = {floor: correct_by_class[floor] / count for floor, count in actual.items()}
    conditional_recalls = {
        floor: correct_by_class[floor] / count for floor, count in predicted_by_class.items()
    }
    return {
        "n_reference_eligible": len(rows),
        "n_predicted": len(covered),
        "n_abstained": len(rows) - len(covered),
        "n_correct": n_correct,
        "accuracy_overall": fraction(n_correct, len(rows)),
        "balanced_accuracy_overall": fraction(sum(recalls.values()), len(recalls)),
        "coverage": fraction(len(covered), len(rows)),
        "accuracy_conditional": fraction(n_correct, len(covered)),
        "balanced_accuracy_conditional": fraction(sum(conditional_recalls.values()), len(conditional_recalls)),
        "per_floor_reference_counts": dict(actual),
        "per_floor_recall_overall": recalls,
        "per_floor_recall_conditional": conditional_recalls,
        "confusion": confusion,
        "independent_id_counts": unit_counts(rows),
        "predicted_independent_id_counts": unit_counts(covered),
    }


def evaluate(
    train_stream: Row, test_stream: Row, split: Row, floors: list[str],
    prediction_sink: list[Row] | None = None,
) -> dict[str, Any]:
    train_episodes = set(split["trainEpisodes"])
    test_episodes = set(split["testEpisodes"])
    if train_stream["id"] == test_stream["id"] and train_episodes & test_episodes:
        raise ValueError(f"Split {split['id']} overlaps train/test episodes in one stream")
    raw_train = ordered_rows(row for row in train_stream["rows"] if row["episode"] in train_episodes)
    test = ordered_rows(row for row in test_stream["rows"] if row["episode"] in test_episodes)
    shared_ids = {}
    for field in ("measurementId", "locationFixId"):
        shared_ids[field] = (
            {row[field] for row in raw_train if row.get(field) is not None}
            & {row[field] for row in test if row.get(field) is not None}
        )
    # Remove contaminated training rows, never test opportunities, without using
    # the test labels. Reusing a location fix can also reuse a floor reference.
    train = [row for row in raw_train if not any(
        row.get(field) is not None and row[field] in shared_ids[field]
        for field in shared_ids
    )]
    model = fit(train, floors, episode_start_times(raw_train))
    model.training_audit["identity_overlap_purge"] = {
        "training_rows_before_purge": len(raw_train),
        "shared_measurement_ids_before_purge": len(shared_ids["measurementId"]),
        "shared_location_fix_ids_before_purge": len(shared_ids["locationFixId"]),
        "purged_training_rows": len(raw_train) - len(train),
        "policy": "Remove every training row sharing a nonnull measurementId or locationFixId with any test row; keep test opportunities unchanged.",
    }
    contexts = {window: history_contexts(test, window) for window in LOOKBACK_MS}
    test_starts = episode_start_times(test)
    predictions = [
        model.predict(row, {window: contexts[window][index] for window in LOOKBACK_MS}, row["t_ms"] - test_starts[row["episode"]])
        for index, row in enumerate(test)
    ]
    if prediction_sink is not None:
        for index, row in enumerate(test):
            prediction_sink.append({
                "splitId": split["id"],
                "trainStreamId": train_stream["id"],
                "testStreamId": test_stream["id"],
                "t_ms": row["t_ms"],
                "floor": row.get("floor"),
                "referenceEligible": row.get("floor") is not None,
                "referenceSource": row.get("referenceSource"),
                "episode": row["episode"],
                "segment": row["segment"],
                "knownEpisodeElapsedMs": row["t_ms"] - test_starts[row["episode"]],
                "outcome": row.get("outcome"),
                "measurementId": row.get("measurementId"),
                "locationFixId": row.get("locationFixId"),
                "currentCellMissing": row.get("cell") is None,
                "currentCellUnseen": row.get("cell") is not None and row["cell"] not in model.cell_counts,
                "history": {str(w // 1000): {
                    "fullWindow": contexts[w][index].elapsed_ms >= w,
                    "observedMeasurements": len(contexts[w][index].points),
                } for w in LOOKBACK_MS},
                "predictions": predictions[index],
            })
    eligible = [index for index, row in enumerate(test) if row.get("floor") is not None]
    eligible_rows = [test[i] for i in eligible]
    supported = [i for i in eligible if model.floor_counts[test[i]["floor"]] > 0]
    unsupported = [i for i in eligible if model.floor_counts[test[i]["floor"]] == 0]
    unseen = [test[i] for i in eligible if test[i].get("cell") is not None and test[i]["cell"] not in model.cell_counts]
    current_null = [test[i] for i in eligible if test[i].get("cell") is None]
    methods = {}
    for method in method_names():
        values = [predictions[i][method] for i in eligible]
        result = score_rows(eligible_rows, values, floors)
        result["supported_test_classes"] = score_rows(
            [test[i] for i in supported], [predictions[i][method] for i in supported], floors
        )
        result["unseen_test_classes"] = score_rows(
            [test[i] for i in unsupported], [predictions[i][method] for i in unsupported], floors
        )
        result["n_predictions_with_unseen_current_cell"] = sum(
            predictions[i][method] is not None
            and test[i].get("cell") is not None
            and test[i]["cell"] not in model.cell_counts
            for i in eligible
        )
        result["per_episode_scores"] = {
            episode: score_rows(
                [test[i] for i in eligible if test[i]["episode"] == episode],
                [predictions[i][method] for i in eligible if test[i]["episode"] == episode], floors,
            ) for episode in sorted(test_episodes)
        }
        if method not in BASE_METHODS:
            window = 60_000 if method.endswith("_60s") else 120_000
            full = [i for i in eligible if contexts[window][i].elapsed_ms >= window]
            partial = [i for i in eligible if contexts[window][i].elapsed_ms < window]
            result["history"] = {
                "lookback_seconds": window // 1000,
                "n_full_window_opportunities": len(full),
                "n_partial_window_opportunities": len(partial),
                "n_predicted_full_window": sum(predictions[i][method] is not None for i in full),
                "n_predicted_partial_window": sum(predictions[i][method] is not None for i in partial),
                "n_opportunities_with_one_history_measurement": sum(len(contexts[window][i].points) == 1 for i in eligible),
                "n_opportunities_with_no_history_measurement": sum(not contexts[window][i].points for i in eligible),
                "median_history_measurements": statistics.median(len(contexts[window][i].points) for i in eligible) if eligible else None,
                "full_window_scores": score_rows([test[i] for i in full], [predictions[i][method] for i in full], floors),
                "partial_window_scores": score_rows([test[i] for i in partial], [predictions[i][method] for i in partial], floors),
            }
        methods[method] = result
    comparisons = []
    for window in LOOKBACK_MS:
        compared = list(BASE_METHODS) + [
            f"window_counts_{window // 1000}s", f"ordered_transition_{window // 1000}s"
        ]
        for full in (False, True):
            available = [i for i in eligible if (contexts[window][i].elapsed_ms >= window) == full]
            matched = [i for i in available if all(predictions[i][method] is not None for method in compared)]
            comparisons.append({
                "lookback_seconds": window // 1000,
                "history_state": "full_window" if full else "partial_window",
                "methods": compared,
                "n_reference_eligible_same_history_state": len(available),
                "n_all_methods_predicted_same_rows": len(matched),
                "matched_coverage": fraction(len(matched), len(available)),
                "scores": {
                    method: score_rows([test[i] for i in matched], [predictions[i][method] for i in matched], floors)
                    for method in compared
                },
            })
    return {
        "split_id": split["id"],
        "split_description": split.get("description"),
        "train_episodes": sorted(train_episodes),
        "test_episodes": sorted(test_episodes),
        "train_stream": {k: v for k, v in train_stream.items() if k != "rows"},
        "test_stream": {k: v for k, v in test_stream.items() if k != "rows"},
        "transfer": train_stream["id"] != test_stream["id"],
        "episode_overlap_across_transfer_streams": sorted(train_episodes & test_episodes),
        "training": model.training_audit,
        "test": {
            "all_rows": unit_counts(test),
            "reference_eligible": unit_counts(eligible_rows),
            "unknown_reference_rows": len(test) - len(eligible),
            "current_cell_missing": unit_counts(current_null),
            "current_cell_unseen_in_training": unit_counts(unseen),
            "unique_unseen_current_cells": len({row["cell"] for row in unseen}),
            "measurement_ids_also_present_in_training": len(
                {row["measurementId"] for row in eligible_rows if row.get("measurementId") is not None}
                & {row["measurementId"] for row in train if row.get("measurementId") is not None}
            ),
            "location_fix_ids_also_present_in_training": len(
                {row["locationFixId"] for row in eligible_rows if row.get("locationFixId") is not None}
                & {row["locationFixId"] for row in train if row.get("locationFixId") is not None}
            ),
            "supported_class_floors": [floor for floor in floors if model.floor_counts[floor] > 0],
            "unseen_test_class_floors": [floor for floor in floors if any(test[i]["floor"] == floor for i in unsupported)],
            "reference_source_counts": dict(Counter(row.get("referenceSource", "unknown") for row in eligible_rows)),
            "outcome_counts_all_rows": dict(Counter(row.get("outcome", "unknown") for row in test)),
            "episode_counts": {episode: {
                "all_rows": unit_counts([row for row in test if row["episode"] == episode]),
                "reference_eligible": unit_counts([row for row in eligible_rows if row["episode"] == episode]),
            } for episode in sorted(test_episodes)},
        },
        "methods": methods,
        "matched_coverage_comparisons": comparisons,
    }


def validate_prepared(data: Row) -> None:
    if data.get("schemaVersion") != 1:
        raise ValueError("Expected prepared schemaVersion=1")
    floors = data.get("floors", [])
    if not floors or len(set(floors)) != len(floors) or "UNKNOWN" in floors:
        raise ValueError("Floors must be nonempty, unique, and cannot include reserved UNKNOWN")
    stream_ids = [stream["id"] for stream in data.get("streams", [])]
    if len(set(stream_ids)) != len(stream_ids):
        raise ValueError("Stream IDs must be unique")
    for stream in data.get("streams", []):
        for row in stream["rows"]:
            if not isinstance(row.get("t_ms"), int):
                raise ValueError(f"Stream {stream['id']} has invalid t_ms")
            if row.get("floor") is not None and row["floor"] not in floors:
                raise ValueError(f"Stream {stream['id']} has a floor outside the declared vocabulary")
            if not isinstance(row.get("episode"), str) or not isinstance(row.get("segment"), str):
                raise ValueError(f"Stream {stream['id']} requires explicit episode and history-reset segment")
            for field in ("cell", "measurementId", "locationFixId"):
                if row.get(field) is not None and not isinstance(row[field], str):
                    raise ValueError(f"Stream {stream['id']} requires opaque string/null {field}")
    split_ids = [split["id"] for split in data.get("splits", [])]
    if len(set(split_ids)) != len(split_ids):
        raise ValueError("Split IDs must be unique")
    for split in data.get("splits", []):
        if not split.get("trainEpisodes") or not split.get("testEpisodes"):
            raise ValueError("Every split needs nonempty trainEpisodes and testEpisodes")
        explicit = ("trainStreamId" in split, "testStreamId" in split)
        if any(explicit) and not all(explicit):
            raise ValueError("A transfer split must specify both stream IDs")
        if all(explicit) and any(split[key] not in stream_ids for key in ("trainStreamId", "testStreamId")):
            raise ValueError("A split names an unknown stream")


def run(data: Row, prediction_sink: list[Row] | None = None) -> Row:
    validate_prepared(data)
    streams = {stream["id"]: stream for stream in data["streams"]}
    results = []
    for split in data["splits"]:
        if "trainStreamId" in split:
            pairs = [(streams[split["trainStreamId"]], streams[split["testStreamId"]])]
        else:
            pairs = [(stream, stream) for stream in streams.values()]
        results.extend(evaluate(train, test, split, data["floors"], prediction_sink) for train, test in pairs)
    return {
        "schemaVersion": 1,
        "floors": data["floors"],
        "configuration": {
            "lookback_seconds": [window // 1000 for window in LOOKBACK_MS],
            "methods": method_names(),
            "minimum_history": "One trained cell observation permits a partial-window prediction; no invented pre-window observations.",
            "full_history": "Time since the first available row in this test episode/reset segment is at least the lookback, including missing-cell opportunities.",
            "history_reset": "Partitioned by split train/test selection, episode, and segment; only current/past rows enter.",
            "current_cell_missing_policy": "Every cell-dependent method abstains; majority_all_opportunities and the privileged elapsed_time_prior also predict on null-cell rows. Failed/null-cell rows remain in eligible denominators.",
            "window_counts": "Average empirical P(floor|cell) over distinct measurement IDs in the causal window; unseen historical cells ignored. An unseen nonnull current cell can use seen past evidence.",
            "ordered_transition": "Empirical P(floor|most recent different cell,current cell), fitted at unique labeled training measurements within each lookback; unseen pair backs off to current single-cell lookup.",
            "majority": "Most frequent floor among unique labeled training measurements, including null-cell opportunities; predicts only when current cell is nonnull.",
            "majority_all_opportunities": "Same fitted floor prior, predicting on every opportunity even when current cell is null; this is an unconditional comparator, not recovered CNI.",
            "elapsed_time_prior": "Privileged known-episode-start comparator: empirical floor counts in frozen 60-second elapsed-time bins, fitted on deduplicated labeled training measurements. Test elapsed time is since its own episode's first available row, without segment-gap resets or future-duration normalization. Empty bins use the closest populated training bin, ties earlier. Predicts without a current cell; itinerary-confound diagnostic, not a caller-only method.",
            "ties": "Higher deduplicated training-floor frequency, then declared floor order; no test-dependent tuning.",
            "training_deduplication": "Exclude measurement IDs with conflicting known floors or nonnull cells, then retain earliest labeled occurrence per ID. Missing IDs remain separate rows and are disclosed.",
            "history_deduplication": "Latest available occurrence per nonnull measurement ID in each window; missing IDs remain separate observations.",
            "train_test_identity_overlap": "Before fitting, purge training rows sharing any nonnull measurementId or locationFixId with test rows; report both pre-purge overlap and post-purge checks.",
            "metric_denominators": "Overall accuracy and macro floor recall include abstention as a miss; conditional scores use predicted rows. Macro averages include classes present in the scored subset only.",
            "independent_counts": "Unique known IDs are reported, not assumed independent visits or uncertainty units; missing IDs do not count as known independent measurements.",
        },
        "limitations": [
            "Prepared floor references are consumed as supplied; this engine does not independently validate them.",
            "Cell-dependent methods do not use time-of-day, episode identity, recording identity, coordinates, altitude or target signal features. Episode/segment reset their histories. The separately named elapsed_time_prior is a privileged known-start itinerary comparator.",
            "Reference eligibility is floor != null. Unknown-reference observations can supply causal cell context but are not scored or used as labeled evidence.",
            "Training-supported and unseen reference classes are scored separately; there is no claimed unseen-floor detector.",
            "Scores are row-weighted descriptive pilot results. Overlapping windows and repeated visits are correlated; no row bootstrap confidence intervals are calculated.",
            "A one-class evening evaluation measures conditional behavior at that reference class, not general multiclass floor discrimination.",
            "Transfer splits sharing episode names across streams are disclosed and are not independent episode validation.",
        ],
        "results": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Prepared JSON, schemaVersion 1")
    parser.add_argument("--output", type=Path, required=True, help="Aggregate metrics JSON; contains no cell/measurement IDs")
    parser.add_argument("--predictions-output", type=Path, help="Optional private per-opportunity JSONL; includes opaque provenance IDs")
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error("Input and output paths must differ")
    if args.predictions_output and args.predictions_output.resolve() in (args.input.resolve(), args.output.resolve()):
        parser.error("Prediction, input and aggregate output paths must differ")
    predictions: list[Row] | None = [] if args.predictions_output else None
    result = run(json.loads(args.input.read_text()), predictions)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    if args.predictions_output:
        args.predictions_output.parent.mkdir(parents=True, exist_ok=True)
        with args.predictions_output.open("w") as target:
            for row in predictions or []:
                target.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Scored {len(result['results'])} split/stream evaluations with {len(method_names())} frozen methods.")
    print(f"Aggregate metrics: {args.output}")


if __name__ == "__main__":
    main()
