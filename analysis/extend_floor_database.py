#!/usr/bin/env python3
"""Combine audited caller floor observations without changing source batches.

Outputs contain private observations and identifiers and belong under captures/.
The labels are retrospective/altitude references, not independent floor truth.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


FLOORS = ["B1", "1F", "2F", "3F", "4F", "5F"]
VISITS = ["nov10_afternoon", "nov10_evening", "nov16"]
KEEP = (
    "t_ms", "cell", "floor", "referenceSource", "referenceStatus", "referencePolicy",
    "measurementId", "locationFixId", "outcome", "radioAgeMs", "radioAgeCapMs",
    "sourceRowIndex", "targetRowIndex", "targetRowGapMs", "attemptOrdinal",
    "invite_t_ms", "response_t_ms", "responseTimingSource", "timeSource",
)


def read(path: Path):
    return json.loads(path.read_text())


def fingerprint(path: Path) -> dict:
    data = path.read_bytes()
    return {"path": str(path), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def verify_inventory(root: Path, path: Path) -> int:
    inventory = read(path)
    for source in inventory["sourceFiles"]:
        actual = fingerprint(root / source["path"])
        if any(actual[k] != source[k] for k in ("bytes", "sha256")):
            raise ValueError("Source file changed: " + source["path"])
    return len(inventory["sourceFiles"])


def combine_rows(old: list[dict], new: list[dict], side: str) -> list[dict]:
    result = []
    for batch, rows in (("nov10", old), ("nov16", new)):
        for index, source in enumerate(rows):
            visit = "nov16" if batch == "nov16" else "nov10_" + source["recording"]
            row = {k: source[k] for k in KEEP if k in source}
            row.update(recording=visit, episode=visit, visit=visit, date=batch,
                       rowId=f"{side}/{visit}/{index}", sourceBatch=batch,
                       sourceTableIndex=index, referencePolicy="primary")
            if side == "local":
                row["radioAgeCapMs"] = 5000
                row.setdefault("outcome", "cell_observed" if row.get("cell") is not None else "local_cell_missing_or_old")
            else:
                # In earlier paired tables these describe the target modem,
                # not the freshness of the caller's successful CNI response.
                row.pop("radioAgeMs", None)
                row.pop("radioAgeCapMs", None)
            # Android elapsed-time IDs are only unique within a boot. Preserve
            # shared IDs across same-date visits while separating different days.
            for field in ("measurementId", "locationFixId"):
                if row.get(field) is not None:
                    row[field] = batch + "/" + row[field]
            if row.get("floor") not in FLOORS + [None]:
                raise ValueError("Unknown reference floor")
            result.append(row)
    result.sort(key=lambda r: r["t_ms"])
    previous = None
    segment = 0
    for row in result:
        if previous is None or row["visit"] != previous["visit"] or row["t_ms"] - previous["t_ms"] > 60_000:
            segment += 1
        # Resets use observable recording boundaries/gaps, never reference floors.
        row["segment"] = f"{row['visit']}/{segment}"
        previous = row
    if len({r["rowId"] for r in result}) != len(result):
        raise ValueError("Duplicate source row")
    return result


def run(root: Path, old_batch: Path, extension: Path, output: Path) -> dict:
    inputs = [old_batch / "analysis/prepared.json", old_batch / "analysis/private_cell_mapping.json",
              old_batch / "REFERENCE_ANCHORS.json", old_batch / "INVENTORY.json",
              extension / "inspection/caller_rows.json",
              extension / "inspection/private_cell_mapping.json", extension / "REFERENCE_ANCHORS.json",
              extension / "INVENTORY.json"]
    for batch in (old_batch, extension):
        verify_inventory(root, batch / "INVENTORY.json")
    old = read(inputs[0])
    old_mapping = {x["cell"]: x["tuple"] for x in read(inputs[1])}
    mapping = {x["cell"]: x["tuple"] for x in read(extension / "inspection/private_cell_mapping.json")}
    if any(mapping.get(alias) != cell for alias, cell in old_mapping.items()):
        raise ValueError("Extension aliases disagree with earlier full cell identities")
    streams = []
    for side, old_id, filename in (("caller", "caller_actual", "caller_rows.json"),):
        earlier = next(s["rows"] for s in old["streams"] if s["id"] == old_id)
        rows = combine_rows(earlier, read(extension / "inspection" / filename), side)
        if any(r.get("cell") is not None and r["cell"] not in mapping for r in rows):
            raise ValueError("Unmapped cell alias")
        streams.append({"id": side + "_combined", "side": side, "rows": rows})
    prepared = {
        "schemaVersion": 1, "datasetVersion": "building_A_B1_5F_20261116", "floors": FLOORS,
        "streams": streams, "collectionVisits": VISITS,
        "activeSource": "caller",
        "phase": "dataset_construction_and_descriptive_analysis",
        "datasetUse": "All accepted caller observations from all three recordings extend the caller database. Local radio measurements are outside the active analysis. No training, validation or test partition is assigned.",
        "referenceType": "User-supported altitude-derived labels, plus user-reported whole evening 4F visit.",
        "featurePolicy": "The lookup rule uses the current caller-returned complete cell identity only. Target-side floor references label associations; target radio cells never supply caller associations. Caller timestamps and source recordings support provenance and future caller-history design.",
        "limitations": ["Only two collection dates and three recordings.",
                        "5F has reference-labeled data in only one recording/date; repeat coverage is limited.",
                        "Current outputs describe the collected associations; they do not measure prediction accuracy or future generalization.",
                        "Only caller-returned Cell IDs contribute floor associations; target measurements supply floor references only.",
                        "No independent inter-phone clock calibration; references are not surveyed floor truth."],
        "inputs": [fingerprint(p) for p in inputs],
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "prepared.json").write_text(json.dumps(prepared, indent=2) + "\n")
    (output / "private_cell_mapping.json").write_text(json.dumps([
        {"cell": alias, "tuple": cell} for alias, cell in sorted(mapping.items())
    ], indent=2) + "\n")
    for stream in streams:
        with (output / (stream["side"] + "_observations.jsonl")).open("w") as sink:
            for row in stream["rows"]:
                sink.write(json.dumps(row, separators=(",", ":")) + "\n")
    return prepared


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--old-batch", type=Path, default=Path("captures/batch_20261112"))
    parser.add_argument("--extension", type=Path, default=Path("captures/batch_20261116"))
    parser.add_argument("--output", type=Path, default=Path("captures/floor_database_20261116"))
    args = parser.parse_args()
    prepared = run(args.root, args.old_batch, args.extension, args.output)
    print(json.dumps({"datasetVersion": prepared["datasetVersion"],
                      "rows": {s["side"]: len(s["rows"]) for s in prepared["streams"]}}))


if __name__ == "__main__":
    main()
