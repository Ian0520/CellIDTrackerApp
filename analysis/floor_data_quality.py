"""Measurement identity checks for floor-database collection; no inference rules."""
from __future__ import annotations

from collections import Counter, defaultdict
from typing import Any

Row = dict[str, Any]


def unit_counts(rows: list[Row]) -> dict[str, int]:
    result = {"rows": len(rows)}
    for field, name in (("measurementId", "measurement"), ("locationFixId", "location_fix")):
        result[f"unique_{name}_ids"] = len({row[field] for row in rows if row.get(field) is not None})
        result[f"rows_without_{name}_id"] = sum(row.get(field) is None for row in rows)
    return result


def select_reference_evidence(rows: list[Row]) -> tuple[list[int], dict[str, Any]]:
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
