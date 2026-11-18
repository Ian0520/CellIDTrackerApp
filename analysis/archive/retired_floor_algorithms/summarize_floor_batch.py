#!/usr/bin/env python3
"""Create compact, aggregate summaries of the audited batch benchmark outputs."""
import argparse
import hashlib
import json
from pathlib import Path


METHODS = ["majority_all_opportunities", "elapsed_time_prior", "single_cell_lookup",
           "window_counts_60s", "ordered_transition_60s", "window_counts_120s"]
METRICS = ["n_reference_eligible", "n_predicted", "n_correct", "accuracy_overall",
           "balanced_accuracy_overall", "coverage", "accuracy_conditional"]


def main(root: Path):
    floor = json.loads((root / "floor_history_results.json").read_text())
    coarse = json.loads((root / "coarse_vertical_results.json").read_text())
    horizontal = json.loads((root / "horizontal_reference_results.json").read_text())
    profiles = json.loads((root / "CELL_FLOOR_PROFILES.json").read_text())
    prepared_hash = hashlib.sha256((root / "prepared.json").read_bytes()).hexdigest()
    if profiles["preparedInputSha256"] != prepared_hash:
        raise ValueError("Profiles and current prepared input differ")
    summary = {"preparedSha256": prepared_hash, "fiveFloorPrimary": [],
               "coarsePrimary": [], "referenceSensitivity": [], "sparseLocalPhases": [],
               "matchedPrimary": [], "horizontalPrimary": [],
               "profiles": [], "candidateSets": profiles["candidateFloorSets"]}
    for document, key in [(floor, "fiveFloorPrimary"), (coarse, "coarsePrimary")]:
        for r in document["results"]:
            primary = r["test_stream"]["id"] in ("caller_actual", "local_updates_age5")
            if not primary:
                continue
            summary[key].append({
                "split": r["split_id"], "trainStream": r["train_stream"]["id"],
                "testStream": r["test_stream"]["id"],
                "testFloors": r["methods"]["single_cell_lookup"]["per_floor_reference_counts"],
                "methods": {name: {m: r["methods"][name][m] for m in METRICS} for name in METHODS},
            })
            if key == "fiveFloorPrimary":
                for c in r["matched_coverage_comparisons"]:
                    if c["history_state"] != "full_window":
                        continue
                    summary["matchedPrimary"].append({
                        "split": r["split_id"], "trainStream": r["train_stream"]["id"],
                        "testStream": r["test_stream"]["id"], "lookbackSeconds": c["lookback_seconds"],
                        "n": c["n_all_methods_predicted_same_rows"],
                        "referenceFloorCounts": c["scores"]["single_cell_lookup"]["per_floor_reference_counts"],
                        "scores": {name: {m: score[m] for m in METRICS}
                                   for name, score in c["scores"].items()},
                    })
    for r in floor["results"]:
        if r["test_stream"]["id"] in ("caller_actual_reference_tight", "caller_actual_reference_loose", "caller_with_recovered_cni"):
            summary["referenceSensitivity"].append({
                "split": r["split_id"], "stream": r["test_stream"]["id"],
                "methods": {name: {m: r["methods"][name][m] for m in METRICS}
                            for name in ["single_cell_lookup", "window_counts_60s"]},
            })
    for split in ("excursion1_to_excursion2", "excursion2_to_excursion1"):
        for cap in (5000, 30000):
            for interval in (10, 30, 60):
                group = [r for r in floor["results"] if r["split_id"] == split and not r["transfer"]
                         and r["test_stream"]["intervalSeconds"] == interval
                         and r["test_stream"]["radioAgeCapMs"] == cap]
                summary["sparseLocalPhases"].append({
                    "split": split, "intervalSeconds": interval, "radioAgeCapMs": cap, "phases": len(group),
                    "methods": {
                        name: {m: [min(r["methods"][name][m] for r in group),
                                   max(r["methods"][name][m] for r in group)]
                               for m in ("accuracy_overall", "balanced_accuracy_overall", "coverage")}
                        for name in ("single_cell_lookup", "window_counts_60s")
                    },
                })
    for r in horizontal["results"]:
        if r["testStreamId"] not in ("caller_actual", "local_updates_age5"):
            continue
        summary["horizontalPrimary"].append({
            "split": r["splitId"], "trainStream": r["trainStreamId"], "testStream": r["testStreamId"],
            "qualifiedReferences": r["evaluation"]["qualifiedReferenceRows"],
            "methods": r["evaluation"]["methods"], "matched": r["evaluation"]["sameRowComparisons"],
            "referenceCaveat": horizontal["referenceCaveat"],
        })
    for profile in profiles["profiles"]:
        summary["profiles"].append({
            k: profile[k] for k in ("streamId", "floorTotals", "knownLabelDistinctCellMeasurements",
                                   "ambiguousCells", "cellsWithKnownFloorSupport", "cohorts")
        })
    (root / "RESULTS_SUMMARY.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("Saved compact aggregate RESULTS_SUMMARY.json; all primary splits and sensitivities retained.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--analysis-root", type=Path, default=Path("captures/batch_20261112/analysis"))
    main(parser.parse_args().analysis_root)
