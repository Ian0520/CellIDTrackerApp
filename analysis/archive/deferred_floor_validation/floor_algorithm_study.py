#!/usr/bin/env python3
"""Build and evaluate the sole active floor algorithm: presence-only lookup.

Outputs are private research artifacts. Each accepted (cell, floor) association
is stored once; predictions use the current cell only. Derived-reference scores
are developmental, not independently measured live accuracy.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

from floor_data_quality import deduplicate_training
from summarize_floor_validation import METHOD, score as validation_score


ALGORITHM_REVISION = "presence_lookup_only_v5"


class PresenceFloorModel:
    """Unique observed cell/floor associations, without frequency or history."""
    def __init__(self, rows: list[dict], floors: list[str]):
        if not floors or len(floors) != len(set(floors)):
            raise ValueError("Floors must be nonempty and unique")
        self.floors = list(floors)
        indices, self.audit = deduplicate_training(rows)
        self.evidence = [rows[i] for i in indices]
        self.support = defaultdict(set)
        for row in self.evidence:
            cell, floor = row.get("cell"), row["floor"]
            if floor not in floors:
                raise ValueError("Unrecognized training floor")
            if cell is not None:
                self.support[cell].add(floor)
        self.supported_floors = [f for f in floors if any(f in support for support in self.support.values())]

    def lookup(self, cell: str | None) -> list[str]:
        return [f for f in self.floors if f in self.support.get(cell, set())]

    def export(self) -> dict:
        return {"pairs": [[cell, f] for cell in sorted(self.support)
                          for f in self.floors if f in self.support[cell]]}


def replay(model: PresenceFloorModel, rows: list[dict]) -> list[dict]:
    """Use only the current cell; reference labels, history and time are unused."""
    return [{"rowId": row["rowId"], "t_ms": row["t_ms"], "visit": row["visit"],
             "cell": row.get("cell"),
             "unseenCell": row.get("cell") is not None and row["cell"] not in model.support,
             "predictionEligible": row.get("cell") is not None,
             "predictions": {METHOD: model.lookup(row.get("cell"))}}
            for row in rows]


def score(rows: list[dict], predictions: list[list[str]], floors: list[str]) -> dict:
    if len(rows) != len(predictions):
        raise ValueError("Prediction and reference lengths differ")
    return validation_score([
        {"cell": row.get("cell"), "referenceFloor": row["floor"], "predictions": {METHOD: prediction}}
        for row, prediction in zip(rows, predictions)
    ], METHOD, floors)


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
    model = PresenceFloorModel(train, floors)
    predictions = replay(model, test)
    reference_eligible = [i for i, r in enumerate(test) if r.get("floor") is not None]
    eligible = [i for i in reference_eligible if test[i].get("cell") is not None]
    groups = {"all": eligible,
              "trained_floors": [i for i in eligible if test[i]["floor"] in model.supported_floors],
              "untrained_floors": [i for i in eligible if test[i]["floor"] not in model.supported_floors]}
    results = {name: score([test[i] for i in indices],
                           [predictions[i]["predictions"][METHOD] for i in indices], floors)
               for name, indices in groups.items()}
    results["per_visit"] = {
        visit: score([test[i] for i in eligible if test[i]["visit"] == visit],
                     [predictions[i]["predictions"][METHOD] for i in eligible if test[i]["visit"] == visit], floors)
        for visit in sorted(test_visits)}
    for raw, prediction in zip(test, predictions):
        prediction.update(referenceFloor=raw.get("floor"), referenceSource=raw.get("referenceSource"),
                          split=split["id"], side=stream["side"])
    return {"split": split["id"], "side": stream["side"], "method": METHOD,
            "trainingVisits": sorted(train_visits), "testVisits": sorted(test_visits),
            "supportedFloors": model.supported_floors, "identityPurge": audit,
            "trainingAudit": model.audit,
            "availability": {"allEvents": len(test),
                             "cellReturnedEvents": sum(r.get("cell") is not None for r in test),
                             "referenceEligibleEvents": len(reference_eligible),
                             "referenceEligibleWithCell": len(eligible),
                             "referenceEligibleWithoutCell": len(reference_eligible) - len(eligible)},
            "methods": {METHOD: results}}, predictions


def run(prepared: dict, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    # Prevent an old comparison directory from looking like this sole model.
    if (output / "models.json").exists():
        raise ValueError("Use a clean output directory; retired frequency models are present")
    floors = prepared["floors"]
    models, profiles, evaluations = {}, {}, []
    with (output / "development_predictions.jsonl").open("w") as sink:
        for stream in prepared["streams"]:
            model = PresenceFloorModel(stream["rows"], floors)
            models[stream["side"]] = model.export()
            profiles[stream["side"]] = {
                "rows": len(stream["rows"]),
                "observedCells": sum(r.get("cell") is not None for r in stream["rows"]),
                "labeledCellEvidence": sum(r.get("cell") is not None for r in model.evidence),
                "labeledUniqueCells": len(model.support),
                "distinctCellFloorPairs": len(models[stream["side"]]["pairs"]),
                "singleObservedFloorCells": sum(len(s) == 1 for s in model.support.values()),
                "ambiguousObservedFloorCells": sum(len(s) > 1 for s in model.support.values()),
                "cellEvidencePerFloor": dict(Counter(r["floor"] for r in model.evidence if r.get("cell") is not None)),
                "trainingAudit": model.audit,
            }
            for split in prepared["splits"]:
                result, predictions = evaluate(stream, split, floors)
                evaluations.append(result)
                for row in predictions:
                    sink.write(json.dumps(row, separators=(",", ":")) + "\n")
    config = {
        "algorithmRevision": ALGORITHM_REVISION, "activeMethod": METHOD,
        "trainingPolicy": "Unique accepted (cell, floor) pairs; caller and local relations fitted separately on all designated training recordings. Each development fold builds its relation from its own training partition only.",
        "inferencePolicy": "Look up all observed floors for the current Cell ID. One floor is exact; multiple floors remain unordered candidates. No frequency weights, probabilities, history, intersection, temporal prior or fallback.",
        "predictionPolicy": "Only a current nonnull Cell ID permits a prediction. Failed probes produce no prediction and count only toward availability.",
        "unknownCellPolicy": "Returned unseen IDs produce unknown and remain in the prediction-accuracy denominator.",
        "coverageCaveat": "Not observed does not mean impossible. Uneven spatial coverage and uncertain references remain limitations.",
        "featurePolicy": "Inference reads the current complete cell identity only. Times, recording IDs and references are retained solely for provenance and evaluation.",
    }
    (output / "presence_database.json").write_text(json.dumps({
        "schemaVersion": 1, "datasetVersion": prepared["datasetVersion"], "floors": floors,
        "algorithmRevision": ALGORITHM_REVISION, "activeMethod": METHOD,
        "pairFields": ["cellAlias", "floor"],
        "semantics": "Observed association, not exclusivity or probability. No duplicates; caller and local relations are separate. Aliases resolve to complete cell identities via the private mapping.",
        "models": models,
    }, indent=2) + "\n")
    result = {"datasetVersion": prepared["datasetVersion"],
              "studyType": "Presence-only lookup: grouped development evaluation and full-database training",
              "config": config, "profiles": profiles, "evaluations": evaluations,
              "limitations": prepared["limitations"]}
    (output / "algorithm_results.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = run(json.loads(args.prepared.read_text()), args.output)
    print(json.dumps({"datasetVersion": result["datasetVersion"], "evaluations": len(result["evaluations"]),
                      "activeMethod": METHOD, "modelsTrainedOnAllRecordings": True}))


if __name__ == "__main__":
    main()
