#!/usr/bin/env python3
"""Fit the extended floor database and compare causal, grouped baselines.

All files produced here are private research artifacts. Model weights are not
calibrated probabilities, and derived-reference scores are not live accuracy.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from itertools import combinations
import json
import math
from pathlib import Path

from floor_history_benchmark import deduplicate_training, history_contexts, score_rows


WINDOW_MS = 60_000
HALF_LIFE_MS = 30_000
ALPHA = .25
METHODS = ["majority_all", "time_only_control", "unique_cell", "lookup_majority",
           "lookup_balanced", "support_set", "window_vote_60s", "window_likelihood_60s",
           "window_normalized_vote_60s", "window_candidates_60s", "intersection_60s", "ordered_pair_60s",
           "presence_lookup", "presence_intersection_60s"]


def normalize(values: dict) -> dict:
    total = sum(values.values())
    return {k: v / total for k, v in values.items()} if total else {}


def winner(weights: dict) -> str | None:
    if not weights:
        return None
    best = max(weights.values())
    ties = [f for f, value in weights.items() if abs(value - best) < 1e-12]
    return ties[0] if len(ties) == 1 else None


def displayed_set(weights: dict) -> list[str]:
    ordered = sorted(weights, key=lambda f: (-weights[f], f))
    if ordered and weights[ordered[0]] >= .8:
        return ordered[:1]
    if len(ordered) >= 2 and sum(weights[f] for f in ordered[:2]) >= .9:
        return ordered[:2]
    return []


def exact(floor: str | None) -> list[str]:
    return [floor] if floor is not None else []


class PresenceFloorModel:
    """A relation of unique (cell, floor) pairs, with no frequency weights.

    Input must already pass the measurement/reference quality checks. Duplicate
    measurement identities with conflicting labels are excluded by the shared
    training audit. The relation is fitted anew inside each development fold.
    """
    def __init__(self, rows: list[dict], floors: list[str]):
        self.floors = floors
        indices, _ = deduplicate_training(rows)
        self.support = defaultdict(set)
        for i in indices:
            cell, floor = rows[i].get("cell"), rows[i]["floor"]
            if cell is not None and floor in floors:
                self.support[cell].add(floor)

    def lookup(self, cell: str | None) -> list[str]:
        return [f for f in self.floors if f in self.support.get(cell, set())]

    def intersection(self, cell: str | None, points: list[dict]) -> list[str]:
        # An unseen current cell must not silently inherit a past-cell answer.
        candidates = set(self.lookup(cell))
        for point in points:
            support = self.support.get(point.get("cell"))
            if support:
                candidates &= support
        return [f for f in self.floors if f in candidates]

    def export(self) -> dict:
        # Persist only the relation, not counters, rates or implied probabilities.
        return {"pairs": [[cell, f] for cell in sorted(self.support)
                          for f in self.floors if f in self.support[cell]]}


class FloorModel:
    def __init__(self, rows: list[dict], floors: list[str]):
        self.floors = floors
        indices, self.audit = deduplicate_training(rows)
        self.evidence = [rows[i] for i in indices]
        self.presence = PresenceFloorModel(rows, floors)
        self.counts = defaultdict(Counter)
        self.visits = defaultdict(lambda: defaultdict(Counter))
        self.floor_counts = Counter()
        self.time_counts = defaultdict(Counter)
        starts = {}
        for row in rows:
            starts.setdefault(row["visit"], row["t_ms"])
        for row in self.evidence:
            floor, cell = row["floor"], row.get("cell")
            self.floor_counts[floor] += 1
            self.time_counts[(row["t_ms"] - starts[row["visit"]]) // 60_000][floor] += 1
            if cell is not None:
                self.counts[cell][floor] += 1
                self.visits[floor][row["visit"]][cell] += 1
        self.supported_floors = [f for f in floors if self.visits.get(f)]
        self.raw = {cell: normalize(dict(n)) for cell, n in self.counts.items()}
        self.floor_normalized = {}
        self.balanced = {}
        vocab = len(self.counts) + 1
        for cell in self.counts:
            # Estimate P(cell | floor, visit), average visits equally, then
            # normalize across floors with equal floor priors. Duplicating a
            # floor's observations with the same cell mix cannot change these
            # weights. Zero observed support is not proof of impossibility.
            rates = {
                floor: sum(n[cell] / sum(n.values()) for n in self.visits[floor].values())
                / len(self.visits[floor]) for floor in self.supported_floors
            }
            self.floor_normalized[cell] = normalize(rates)
            weights = {}
            for floor in self.supported_floors:
                # Equal floor prior, equal weight for each contributing visit.
                # Smoothing uses only the current training vocabulary.
                visits = self.visits[floor]
                weights[floor] = sum(
                    (n[cell] + ALPHA) / (sum(n.values()) + ALPHA * vocab)
                    for n in visits.values()
                ) / len(visits)
            self.balanced[cell] = normalize(weights)
        contexts = history_contexts(rows, WINDOW_MS)
        self.pairs = defaultdict(Counter)
        for i in indices:
            row = rows[i]
            if row.get("cell") is not None:
                previous = contexts[i].previous_different_cell(row["cell"])
                if previous is not None:
                    self.pairs[(previous, row["cell"])][row["floor"]] += 1

    def export(self) -> dict:
        return {
            "floors": self.floors, "supportedFloors": self.supported_floors,
            "trainingAudit": self.audit, "floorEvidenceCounts": dict(self.floor_counts),
            "cells": {c: {"floorCounts": dict(n), "observedFloors": [f for f in self.floors if n[f]],
                           "visitCounts": {f: {v: q[c] for v, q in self.visits[f].items() if q[c]}
                                           for f in self.floors if n[f]},
                           "empiricalFloorWeights": self.raw[c],
                           "floorNormalizedWeights": self.floor_normalized[c],
                           "balancedFloorWeights": self.balanced[c]}
                      for c, n in sorted(self.counts.items())},
            "orderedPairs": [{"previousCell": a, "currentCell": b, "floorCounts": dict(n)}
                             for (a, b), n in sorted(self.pairs.items())],
        }


def unique_cells(points: list[dict]) -> list[dict]:
    latest = {}
    for point in points:
        latest[point["cell"]] = point
    return list(latest.values())


def history_weights(model: FloorModel, row: dict, points: list[dict], likelihood: bool,
                    floor_normalized: bool = False) -> dict:
    usable = [p for p in unique_cells(points) if p["cell"] in model.counts]
    if not usable:
        return {}
    weights = {f: 0.0 for f in model.supported_floors}
    total = 0.0
    for point in usable:
        age = row["t_ms"] - point["t_ms"]
        if age < 0:
            raise ValueError("Future observation in history")
        weight = 2 ** (-age / HALF_LIFE_MS)
        probabilities = (model.floor_normalized[point["cell"]] if floor_normalized else
                         model.balanced[point["cell"]] if likelihood else model.raw[point["cell"]])
        total += weight
        for floor in weights:
            value = probabilities.get(floor, 0)
            weights[floor] += weight * (math.log(max(value, 1e-9)) if likelihood else value)
    if likelihood:
        # A weighted geometric mean bounds correlated repeated evidence; its
        # exponent does not grow simply because the probe count grows.
        weights = {f: math.exp(v / total) for f, v in weights.items()}
    return normalize(weights)


def replay(model: FloorModel, rows: list[dict]) -> list[dict]:
    """Predictions depend on features/past only; no reference labels are read."""
    contexts = history_contexts(rows, WINDOW_MS)
    starts = {}
    output = []
    for i, row in enumerate(rows):
        starts.setdefault(row["visit"], row["t_ms"])
        methods = dict.fromkeys(METHODS)
        methods = {m: [] for m in methods}
        cell = row.get("cell")
        methods["presence_lookup"] = model.presence.lookup(cell)
        methods["presence_intersection_60s"] = model.presence.intersection(cell, contexts[i].points)
        if cell is not None:
            methods["majority_all"] = exact(winner(model.floor_counts))
        if cell is not None and model.time_counts:
            minute = (row["t_ms"] - starts[row["visit"]]) // 60_000
            nearest = min(model.time_counts, key=lambda k: (abs(k - minute), k))
            methods["time_only_control"] = exact(winner(model.time_counts[nearest]))
        if cell in model.counts:
            support = [f for f in model.floors if model.counts[cell][f]]
            methods["unique_cell"] = support if len(support) == 1 else []
            methods["lookup_majority"] = exact(winner(model.raw[cell]))
            methods["lookup_balanced"] = exact(winner(model.balanced[cell]))
            methods["support_set"] = support if len(support) <= 2 else []
            points = contexts[i].points
            vote = history_weights(model, row, points, likelihood=False)
            normalized_vote = history_weights(model, row, points, likelihood=False, floor_normalized=True)
            likelihood = history_weights(model, row, points, likelihood=True)
            methods["window_vote_60s"] = exact(winner(vote))
            methods["window_normalized_vote_60s"] = exact(winner(normalized_vote))
            methods["window_likelihood_60s"] = exact(winner(likelihood))
            methods["window_candidates_60s"] = displayed_set(likelihood)
            intersection = None
            for point in unique_cells(points):
                if point["cell"] in model.counts:
                    floors = set(model.counts[point["cell"]])
                    intersection = floors if intersection is None else intersection & floors
            methods["intersection_60s"] = [f for f in model.floors if f in intersection] if intersection and len(intersection) <= 2 else []
            previous = contexts[i].previous_different_cell(cell)
            pair = model.pairs.get((previous, cell))
            methods["ordered_pair_60s"] = exact(winner(pair)) if pair else methods["lookup_balanced"]
        output.append({"rowId": row["rowId"], "t_ms": row["t_ms"], "visit": row["visit"],
                       "cell": cell, "unseenCell": cell is not None and cell not in model.counts,
                       "predictionEligible": cell is not None,
                       "historyDistinctCells": len(unique_cells(contexts[i].points)),
                       "predictions": methods})
    return output


def score(rows: list[dict], predictions: list[list[str]], floors: list[str]) -> dict:
    exact_predictions = [p[0] if len(p) == 1 else None for p in predictions]
    result = score_rows(rows, exact_predictions, floors)
    answered = [p for p in predictions if p]
    included = sum(row["floor"] in p for row, p in zip(rows, predictions))
    result["candidate_sets"] = {
        "n_answered": len(answered), "n_containing_reference": included,
        "n_wrong_sets": len(answered) - included,
        "coverage": len(answered) / len(rows) if rows else None,
        "retention_overall": included / len(rows) if rows else None,
        "retention_among_answers": included / len(answered) if answered else None,
        "mean_size_among_answers": sum(map(len, answered)) / len(answered) if answered else None,
    }
    return result


def purge(train: list[dict], test: list[dict]) -> tuple[list[dict], dict]:
    shared = {field: {r[field] for r in test if r.get(field) is not None}
              for field in ("measurementId", "locationFixId")}
    keep = [r for r in train if not any(r.get(k) is not None and r[k] in ids for k, ids in shared.items())]
    return keep, {"trainingRowsBefore": len(train), "purgedTrainingRows": len(train) - len(keep)}


def evaluate(stream: dict, split: dict, floors: list[str]) -> tuple[dict, list[dict]]:
    train_visits, test_visits = set(split["trainEpisodes"]), set(split["testEpisodes"])
    if train_visits & test_visits:
        raise ValueError("Training and test recordings overlap")
    train = [r for r in stream["rows"] if r["visit"] in train_visits]
    test = [r for r in stream["rows"] if r["visit"] in test_visits]
    train, audit = purge(train, test)
    model = FloorModel(train, floors)
    predictions = replay(model, test)
    reference_eligible = [i for i, r in enumerate(test) if r.get("floor") is not None]
    eligible = [i for i in reference_eligible if test[i].get("cell") is not None]
    supported = [i for i in eligible if test[i]["floor"] in model.supported_floors]
    unseen_floor = [i for i in eligible if test[i]["floor"] not in model.supported_floors]
    methods = {}
    for method in METHODS:
        groups = {"all": eligible, "trained_floors": supported, "untrained_floors": unseen_floor,
                  "current_cell_available": eligible}
        methods[method] = {name: score([test[i] for i in indices], [predictions[i]["predictions"][method] for i in indices], floors)
                           for name, indices in groups.items()}
        methods[method]["per_visit"] = {
            visit: score([test[i] for i in eligible if test[i]["visit"] == visit],
                         [predictions[i]["predictions"][method] for i in eligible if test[i]["visit"] == visit], floors)
            for visit in sorted(test_visits)}
    for raw, prediction in zip(test, predictions):
        prediction.update(referenceFloor=raw.get("floor"), referenceSource=raw.get("referenceSource"),
                          split=split["id"], side=stream["side"])
    matched = []
    for cohort, indices in (("all", eligible), ("trained_floors", supported)):
        for left, right in (("lookup_majority", "window_vote_60s"),
                            ("window_vote_60s", "window_normalized_vote_60s"),
                            ("lookup_balanced", "window_likelihood_60s"),
                            ("lookup_balanced", "ordered_pair_60s")):
            same = [i for i in indices if len(predictions[i]["predictions"][left]) == 1
                    and len(predictions[i]["predictions"][right]) == 1]
            a = [predictions[i]["predictions"][left][0] == test[i]["floor"] for i in same]
            b = [predictions[i]["predictions"][right][0] == test[i]["floor"] for i in same]
            matched.append({"cohort": cohort, "left": left, "right": right,
                            "nBothAnswered": len(same), "leftCorrect": sum(a), "rightCorrect": sum(b),
                            "correctedByRight": sum(not x and y for x, y in zip(a, b)),
                            "brokenByRight": sum(x and not y for x, y in zip(a, b)),
                            "floorCounts": dict(Counter(test[i]["floor"] for i in same))})
    return {"split": split["id"], "side": stream["side"], "trainingVisits": sorted(train_visits),
            "testVisits": sorted(test_visits), "supportedFloors": model.supported_floors,
            "identityPurge": audit, "trainingAudit": model.audit,
            "availability": {"allEvents": len(test), "cellReturnedEvents": sum(r.get("cell") is not None for r in test),
                             "referenceEligibleEvents": len(reference_eligible),
                             "referenceEligibleWithCell": len(eligible),
                             "referenceEligibleWithoutCell": len(reference_eligible) - len(eligible)},
            "historySummary": {"referenceEligible": len(reference_eligible), "predictionEligible": len(eligible),
                               "currentCellAvailable": sum(test[i].get("cell") is not None for i in eligible),
                               "currentCellUnseen": sum(predictions[i]["unseenCell"] for i in eligible),
                               "availableWithTwoDistinctCells60s": sum(test[i].get("cell") is not None and predictions[i]["historyDistinctCells"] >= 2 for i in eligible)},
            "matchedComparisons": matched, "methods": methods}, predictions


def combination_catalog(model: FloorModel, rows: list[dict]) -> list[dict]:
    """Descriptive pair intersections in the full training database, not accuracy."""
    candidates = {}
    for a, b in combinations(sorted(model.counts), 2):
        left, right = set(model.counts[a]), set(model.counts[b])
        intersection = left & right
        if len(left) > 1 and len(right) > 1 and len(intersection) == 1:
            candidates[(a, b)] = {"cells": [a, b], "cellFloorSets": [sorted(left), sorted(right)],
                                   "intersection": sorted(intersection), "observedEndFloors": Counter(),
                                   "visits": set(), "distinctMeasurementPairs": set()}
    contexts = history_contexts(rows, WINDOW_MS)
    for row, context in zip(rows, contexts):
        if row.get("cell") is None or row.get("floor") is None:
            continue
        for point in unique_cells(context.points):
            pair = tuple(sorted((point["cell"], row["cell"])))
            if pair in candidates:
                entry = candidates[pair]
                ident = (point.get("measurementId"), row.get("measurementId"))
                if ident not in entry["distinctMeasurementPairs"]:
                    entry["distinctMeasurementPairs"].add(ident)
                    entry["observedEndFloors"][row["floor"]] += 1
                    entry["visits"].add(row["visit"])
    return [{**{k: v for k, v in c.items() if k not in ("visits", "distinctMeasurementPairs")},
             "visits": sorted(c["visits"]), "nDistinctMeasurementPairs": len(c["distinctMeasurementPairs"])}
            for c in candidates.values() if c["distinctMeasurementPairs"]]


def plot_database(models: dict, destination: Path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np

    fig, axes = plt.subplots(2, 1, figsize=(16, 7), constrained_layout=True)
    for axis, side in zip(axes, ("caller", "local")):
        model = models[side]
        cells, floors = list(model["cells"]), model["floors"]
        counts = np.array([[model["cells"][c]["floorCounts"].get(f, 0) for c in cells] for f in floors])
        axis.imshow(np.log10(1 + counts), aspect="auto", cmap="Blues", vmin=0, vmax=2.5)
        for y, x in zip(*np.nonzero(counts)):
            axis.text(x, y, str(counts[y, x]), ha="center", va="center", fontsize=7,
                      color="white" if counts[y, x] >= 30 else "black")
        axis.set_xticks(range(len(cells)), cells, rotation=60, fontsize=8)
        axis.set_yticks(range(len(floors)), floors)
        axis.set_title(f"{side.capitalize()}: {int(counts.sum())} reference-labeled cell observations; {len(cells)} cell aliases")
        axis.set_xlabel("Same anonymous cell identity mapping in both panels; blank = not observed here")
    fig.suptitle("Extended B1–5F database: counts, not prediction accuracy")
    fig.savefig(destination, dpi=150)
    plt.close(fig)


def run(prepared: dict, output: Path):
    output.mkdir(parents=True, exist_ok=True)
    floors = prepared["floors"]
    models, presence_models, profiles, evaluations = {}, {}, {}, []
    with (output / "development_predictions.jsonl").open("w") as sink:
        for stream in prepared["streams"]:
            model = FloorModel(stream["rows"], floors)
            models[stream["side"]] = model.export()
            presence_models[stream["side"]] = model.presence.export()
            profiles[stream["side"]] = {
                "rows": len(stream["rows"]),
                "observedCells": sum(r.get("cell") is not None for r in stream["rows"]),
                "labeledCellEvidence": sum(sum(n.values()) for n in model.counts.values()),
                "labeledUniqueCells": len(model.counts),
                "distinctCellFloorPairs": len(model.presence.export()["pairs"]),
                "singleObservedFloorCells": sum(len(n) == 1 for n in model.counts.values()),
                "ambiguousObservedFloorCells": sum(len(n) > 1 for n in model.counts.values()),
                "cellEvidencePerFloor": dict(Counter({f: sum(n[f] for n in model.counts.values()) for f in floors})),
                "combinationExamples": combination_catalog(model, stream["rows"]),
            }
            for split in prepared["splits"]:
                result, predictions = evaluate(stream, split, floors)
                evaluations.append(result)
                for row in predictions:
                    sink.write(json.dumps(row, separators=(",", ":")) + "\n")
    config = {"historyWindowMs": WINDOW_MS, "decayHalfLifeMs": HALF_LIFE_MS,
              "algorithmRevision": "presence_only_v4",
              "presencePolicy": "Unique (cell, floor) pairs only; no training counts, floor priors, rates, smoothing or confidence weights. Full candidate sets are retained. A missing association means not observed, not impossible.",
              "presenceHistoryPolicy": "Intersect supports of known cells within 60 seconds, including the current known cell; no frequency weighting or fallback. Empty intersection or unseen current cell means unknown. This assumes the window describes one floor and can fail across floor changes.",
              "normalizedVotePolicy": "Mean P(cell|floor,visit) across visits; equal floor priors; no count-dependent smoothing. This removes observation-count imbalance, not uneven spatial/temporal coverage.",
              "likelihoodSmoothing": ALPHA, "candidateSingleWeight": .8, "candidatePairWeight": .9,
              "parameterPolicy": "Fixed exploratory settings; no parameter search and no preregistration.",
              "calibratedProbabilities": False, "weightedCandidateMaxSetSize": 2,
              "presenceCandidateMaxSetSize": len(floors),
              "repeatPolicy": "At most one contribution per distinct Cell identity in each history window.",
              "predictionPolicy": "Only a current nonnull Cell ID permits a prediction. Failed probes are availability events, not prediction opportunities; no carried estimate is emitted. History can inform a later Cell-bearing probe.",
              "unknownCellPolicy": "A returned unseen cell is an eligible prediction opportunity; cell-based methods abstain and this remains in the validation denominator.",
              "featurePolicy": prepared["featurePolicy"]}
    (output / "models.json").write_text(json.dumps({"datasetVersion": prepared["datasetVersion"], "config": config, "models": models}, indent=2) + "\n")
    (output / "presence_database.json").write_text(json.dumps({
        "schemaVersion": 1, "datasetVersion": prepared["datasetVersion"], "floors": floors,
        "pairFields": ["cellAlias", "floor"],
        "semantics": "Observed association, not exclusivity or probability. No duplicates; caller and local relations are separate. Aliases resolve to complete cell identities via the private mapping.",
        "models": presence_models,
    }, indent=2) + "\n")
    result = {"datasetVersion": prepared["datasetVersion"], "studyType": "Grouped development comparisons plus full-database training",
              "config": config, "profiles": profiles, "evaluations": evaluations,
              "limitations": prepared["limitations"]}
    (output / "algorithm_results.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plot", action="store_true")
    args = parser.parse_args()
    result = run(json.loads(args.prepared.read_text()), args.output)
    if args.plot:
        plot_database(json.loads((args.output / "models.json").read_text())["models"],
                      args.output / "CELL_FLOOR_DATABASE.png")
    print(json.dumps({"datasetVersion": result["datasetVersion"], "evaluations": len(result["evaluations"]),
                      "methods": len(METHODS), "modelsTrainedOnAllRecordings": True}))


if __name__ == "__main__":
    main()
