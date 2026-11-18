#!/usr/bin/env python3
"""Summarize held-out floor predictions with each requested floor weighted equally.

Only observations with a returned Cell ID enter prediction accuracy. Probe
availability is reported separately. An unseen returned ID still enters the
denominator; unknown and multi-floor answers are misses for exact-floor recall.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


DEFAULT_FLOORS = ["B1", "1F", "2F", "3F", "4F"]
METHOD = "presence_lookup"


def score(rows: list[dict], method: str, floors: list[str]) -> dict:
    if any(r.get("cell") is None for r in rows):
        raise ValueError("Accuracy rows must have a current Cell ID")
    per_floor = {}
    for floor in floors:
        group = [r for r in rows if r["referenceFloor"] == floor]
        correct = sum(r["predictions"][method] == [floor] for r in group)
        exact = sum(len(r["predictions"][method]) == 1 for r in group)
        retained = sum(floor in r["predictions"][method] for r in group)
        answered = [r["predictions"][method] for r in group if r["predictions"][method]]
        per_floor[floor] = {"correct": correct, "total": len(group), "exactAnswers": exact,
                            "recall": correct / len(group) if group else None,
                            "candidateContainingReference": retained,
                            "candidateRetention": retained / len(group) if group else None,
                            "candidateAnswers": len(answered),
                            "candidateCoverage": len(answered) / len(group) if group else None,
                            "meanCandidateSizeAmongAnswers": sum(map(len, answered)) / len(answered) if answered else None}
    n = sum(p["total"] for p in per_floor.values())
    correct = sum(p["correct"] for p in per_floor.values())
    exact = sum(p["exactAnswers"] for p in per_floor.values())
    missing = [f for f, p in per_floor.items() if not p["total"]]
    answered = [r["predictions"][method] for r in rows
                if r["referenceFloor"] in floors and r["predictions"][method]]
    retained = sum(p["candidateContainingReference"] for p in per_floor.values())
    return {
        "primaryEqualFloorRecall": None if missing else sum(p["recall"] for p in per_floor.values()) / len(floors),
        "missingReferenceFloors": missing, "perFloor": per_floor,
        "nReferenceEligible": n, "nCorrect": correct, "nExactAnswers": exact,
        "secondaryObservationWeightedAccuracy": correct / n if n else None,
        "exactAnswerCoverage": exact / n if n else None,
        "accuracyAmongExactAnswers": correct / exact if exact else None,
        "worstFloorRecall": None if missing else min(p["recall"] for p in per_floor.values()),
        "candidateSets": {
            "equalFloorRetention": None if missing else sum(p["candidateRetention"] for p in per_floor.values()) / len(floors),
            "nAnswered": len(answered), "nContainingReference": retained,
            "coverage": len(answered) / n if n else None,
            "retentionAmongAnswers": retained / len(answered) if answered else None,
            "meanSizeAmongAnswers": sum(map(len, answered)) / len(answered) if answered else None,
            "sizeHistogram": dict(sorted(Counter(map(len, answered)).items())),
        },
    }


def summarize(predictions: list[dict], floors: list[str]) -> dict:
    if not floors or len(floors) != len(set(floors)):
        raise ValueError("Requested floors must be nonempty and unique")
    if any(set(r["predictions"]) != {METHOD} for r in predictions):
        raise ValueError("Only presence_lookup is active; regenerate retired comparison journals")
    held_out = [r for r in predictions if r["split"].startswith("leave_out_")]
    identities = [(r["side"], r["rowId"]) for r in held_out]
    if len(identities) != len(set(identities)):
        raise ValueError("A test row appears more than once in the selected held-out folds")
    if any(r.get("cell") is None and any(r["predictions"].values()) for r in held_out):
        raise ValueError("Prediction emitted without a current Cell ID; use cell-returned replay policy")
    result = {
        "activeMethod": METHOD,
        "primaryMetric": "Mean exact-floor recall conditional on a returned Cell ID; each requested floor has equal weight.",
        "eligibilityPolicy": "No Cell ID means no prediction, excluded from prediction accuracy and included in availability statistics only.",
        "unknownPolicy": "Returned unseen IDs stay in the denominator. Unknown and candidate sets count as misses for exact-floor recall.",
        "splitPolicy": "Only three leave-one-recording-out folds; each test row appears once per channel. Reverse-date diagnostic is not pooled again.",
        "floors": floors, "weightPerFloor": 1 / len(floors),
        "limitations": ["Equal-floor scoring removes floor-count dominance, not unrepresentative within-floor coverage.",
                        "Only three recordings/two dates; these are developmental scores for presence lookup.",
                        "Floor references are user-supported/altitude-derived, not independent surveyed truth.",
                        "No confidence interval treating correlated measurements as independent trials."],
        "channels": {},
    }
    for side in sorted({r["side"] for r in held_out}):
        all_rows = [r for r in held_out if r["side"] == side]
        references = [r for r in all_rows if r.get("referenceFloor") in floors]
        rows = [r for r in references if r.get("cell") is not None]
        if not rows:
            raise ValueError("No requested-floor references for channel " + side)
        methods = list(rows[0]["predictions"])
        if any(set(r["predictions"]) != set(methods) for r in rows):
            raise ValueError("Methods differ between prediction rows")
        scored = {m: score(rows, m, floors) for m in methods}
        visits = sorted({r["visit"] for r in rows})
        result["channels"][side] = {
            "cellAvailability": {
                "allHeldOutEvents": len(all_rows),
                "cellReturnedEvents": sum(r.get("cell") is not None for r in all_rows),
                "requestedFloorReferenceEvents": len(references),
                "requestedFloorWithCell": len(rows),
                "requestedFloorWithoutCell": len(references) - len(rows),
                "rateOnRequestedFloorReferences": len(rows) / len(references) if references else None,
                "perFloor": {f: {"referenceEvents": sum(r["referenceFloor"] == f for r in references),
                                 "cellReturnedEvents": sum(r["referenceFloor"] == f for r in rows)} for f in floors},
            },
            "returnedUnseenCellEvents": sum(r.get("unseenCell") is True for r in rows),
            "floorReferenceCounts": dict(Counter(r["referenceFloor"] for r in rows)),
            "floorReferenceFractions": {f: sum(r["referenceFloor"] == f for r in rows) / len(rows) for f in floors},
            "excludedUnknownOrOtherFloorRows": len(all_rows) - len(references),
            "activeMethod": METHOD,
            "methods": scored,
            "perVisit": {v: {m: score([r for r in rows if r["visit"] == v], m, floors) for m in methods} for v in visits},
        }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--floors", nargs="+", default=DEFAULT_FLOORS)
    args = parser.parse_args()
    raw = args.predictions.read_bytes()
    result = summarize([json.loads(line) for line in raw.splitlines() if line], args.floors)
    result["source"] = {"path": str(args.predictions), "sha256": hashlib.sha256(raw).hexdigest()}
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({side: {"method": METHOD,
                            "score": x["methods"][METHOD]["primaryEqualFloorRecall"]}
                      for side, x in result["channels"].items()}))


if __name__ == "__main__":
    main()
