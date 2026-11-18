#!/usr/bin/env python3
"""Build the presence-only floor dataset and describe its observed associations.

All accepted caller recordings contribute to the active database. No recordings
are assigned to training, validation or testing, and no accuracy is calculated.
Outputs are private research artifacts.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

from floor_data_quality import select_reference_evidence


METHOD = "presence_lookup"
ALGORITHM_REVISION = "caller_presence_dataset_v7"
PHASE = "dataset_construction_and_descriptive_analysis"


class PresenceFloorModel:
    """Unique observed cell/floor associations, without frequency or history."""
    def __init__(self, rows: list[dict], floors: list[str]):
        if not floors or len(floors) != len(set(floors)):
            raise ValueError("Floors must be nonempty and unique")
        self.floors = list(floors)
        indices, self.audit = select_reference_evidence(rows)
        self.evidence = [rows[i] for i in indices]
        self.support = defaultdict(set)
        for row in self.evidence:
            cell, floor = row.get("cell"), row["floor"]
            if floor not in floors:
                raise ValueError("Unrecognized reference floor")
            if cell is not None:
                self.support[cell].add(floor)

    def lookup(self, cell: str | None) -> list[str]:
        return [f for f in self.floors if f in self.support.get(cell, set())]

    def export(self) -> dict:
        return {"pairs": [[cell, f] for cell in sorted(self.support)
                          for f in self.floors if f in self.support[cell]]}


def describe_associations(model: PresenceFloorModel) -> dict:
    """Count distinct associations, never sample-weighted scores or accuracy."""
    cells_by_floor = {f: {c for c, support in model.support.items() if f in support}
                      for f in model.floors}
    pair_visits = defaultdict(set)
    for row in model.evidence:
        if row.get("cell") is not None:
            pair_visits[(row["cell"], row["floor"])].add(row["visit"])
    return {
        "distinctCells": len(model.support),
        "distinctCellFloorPairs": sum(map(len, model.support.values())),
        "cellsByNumberOfObservedFloors": dict(sorted(Counter(map(len, model.support.values())).items())),
        "cells": [{"cell": c, "observedFloors": model.lookup(c)} for c in sorted(model.support)],
        "perFloor": {
            f: {"distinctCells": len(cells), "cells": sorted(cells),
                "onlyObservedOnThisFloor": sorted(c for c in cells if len(model.support[c]) == 1),
                "sharedWithOtherFloors": sorted(c for c in cells if len(model.support[c]) > 1)}
            for f, cells in cells_by_floor.items()
        },
        "sharedCellCountsBetweenFloors": {
            f: {other: len(cells & cells_by_floor[other]) for other in model.floors}
            for f, cells in cells_by_floor.items()
        },
        "associationProvenance": [
            {"cell": c, "floor": f, "recordings": sorted(pair_visits[(c, f)])}
            for c in sorted(model.support) for f in model.floors if f in model.support[c]
        ],
        "interpretation": "Observed support and ambiguity only. A cell observed on one floor is not proven exclusive; overlaps and recording provenance are descriptive, not weights or prediction-performance estimates.",
    }


def run(prepared: dict, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    retired_files = ["models.json", "algorithm_results.json", "development_predictions.jsonl", "BALANCED_VALIDATION.json"]
    if any((output / name).exists() for name in retired_files):
        raise ValueError("Use a clean output directory for dataset-only analysis")
    floors = prepared["floors"]
    caller_streams = [s for s in prepared["streams"] if s["side"] == "caller"]
    if len(caller_streams) != 1:
        raise ValueError("Exactly one caller stream is required for the active analysis")
    models, profiles, associations = {}, {}, {}
    for stream in caller_streams:
        side, rows = stream["side"], stream["rows"]
        model = PresenceFloorModel(rows, floors)
        models[side] = model.export()
        associations[side] = describe_associations(model)
        profiles[side] = {
            "recordings": sorted({r["visit"] for r in rows}),
            "observationOpportunities": len(rows),
            "cellBearingObservations": sum(r.get("cell") is not None for r in rows),
            "acceptedFloorLabeledCellObservations": sum(r.get("cell") is not None for r in model.evidence),
            "acceptedCellObservationsPerFloor": dict(Counter(r["floor"] for r in model.evidence if r.get("cell") is not None)),
            "qualityAudit": model.audit,
        }
    config = {
        "algorithmRevision": ALGORITHM_REVISION, "activeMethod": METHOD, "phase": PHASE,
        "activeSource": "caller",
        "datasetPolicy": "All accepted caller recordings extend the caller database; no training, validation or test partition. Local radio cells are excluded from associations and inference.",
        "referencePolicy": "Target-side measurements may provide the accepted floor labels and timing context. They do not supply Cell IDs to the caller relation or inference features.",
        "associationPolicy": "Each accepted (complete cell identity, floor) pair once; no occurrence counts, proportions, probabilities or temporal weights.",
        "lookupPolicy": "Look up all observed floors for the current Cell ID. An unseen returned cell means unknown; no returned cell means no prediction. No history, intersection or fallback.",
        "analysisPolicy": "Describe distinct cells, floor supports, ambiguity, overlap and recording provenance. No prediction replay, scoring or accuracy estimate.",
        "coverageCaveat": "Not observed does not mean impossible. Uneven spatial coverage and uncertain references remain limitations.",
    }
    (output / "presence_database.json").write_text(json.dumps({
        "schemaVersion": 1, "datasetVersion": prepared["datasetVersion"], "floors": floors,
        "algorithmRevision": ALGORITHM_REVISION, "activeMethod": METHOD, "phase": PHASE,
        "activeSource": "caller",
        "pairFields": ["cellAlias", "floor"],
        "semantics": "Unique caller-observed associations, not exclusivity or probabilities. Local radio cells are excluded. Aliases resolve to complete cell identities via the private mapping.",
        "models": models,
    }, indent=2) + "\n")
    result = {"datasetVersion": prepared["datasetVersion"], "phase": PHASE, "activeSource": "caller",
              "config": config, "profiles": profiles, "associations": associations,
              "limitations": prepared["limitations"]}
    (output / "association_analysis.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = run(json.loads(args.prepared.read_text()), args.output)
    print(json.dumps({"datasetVersion": result["datasetVersion"], "phase": PHASE,
                      "activeMethod": METHOD,
                      "uniquePairs": {side: data["distinctCellFloorPairs"] for side, data in result["associations"].items()}}))


if __name__ == "__main__":
    main()
