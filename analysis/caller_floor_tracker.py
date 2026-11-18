#!/usr/bin/env python3
"""Replay caller-only floor tracking against its complete construction dataset.

The database contains unique observed (cell, floor) pairs, with no frequency
weights. Same-dataset scores diagnose behavior and cannot establish accuracy on
new observations. All inference inputs are caller cells, times and recordings.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict, deque
import csv
from datetime import datetime
import json
from pathlib import Path
from zoneinfo import ZoneInfo

from extend_floor_database import fingerprint
from floor_algorithm_study import PresenceFloorModel


REVISION = "caller_minimum_changes_v1"
DEFAULT_WINDOW_MS = 60_000


class FloorHistoryTracker:
    def __init__(self, floors: list[str], pairs: list[list[str]], window_ms=DEFAULT_WINDOW_MS):
        if not floors or len(set(floors)) != len(floors) or window_ms < 0:
            raise ValueError("Require a unique ordered floor list and nonnegative history window")
        self.floors = list(floors)
        self.order = {f: i for i, f in enumerate(floors)}
        self.support = defaultdict(set)
        for cell, floor in pairs:
            if cell is None or floor not in self.order:
                raise ValueError("Invalid cell/floor pair")
            self.support[cell].add(floor)
        self.window_ms = window_ms
        self.history = deque()
        self.recording = None
        self.last_time = None

    def display(self, candidates: list[str]) -> tuple[str, list[str]]:
        if len(candidates) == 1:
            return "single_floor", list(candidates)
        if len(candidates) == 2 and abs(self.order[candidates[0]] - self.order[candidates[1]]) == 1:
            return "adjacent_pair", list(candidates)
        return "unknown", []

    def observe(self, cell: str | None, t_ms: int, recording: str) -> dict | None:
        """Consume one available event; no target labels or future data accepted."""
        if recording != self.recording:
            self.history.clear()
            self.last_time = None
            self.recording = recording
        if self.last_time is not None and t_ms < self.last_time:
            raise ValueError("Observations must be chronological within a recording")
        self.last_time = t_ms
        while self.history and self.history[0][0] < t_ms - self.window_ms:
            self.history.popleft()
        if cell is None:
            return None
        support = self.support.get(cell, set())
        current = [f for f in self.floors if f in support]
        if not current:
            return {"kind": "unknown", "floors": [], "reason": "unseen_current_cell",
                    "currentSupport": [], "bestCurrentFloors": [], "costByCurrentFloor": {},
                    "minimumFloorChanges": None, "historyDistinctCells": 0}
        self.history.append((t_ms, cell))
        # A zero-length window is the current-cell-only mode, even for events
        # with identical timestamps. It is the same output rule without history.
        points = list(self.history) if self.window_ms else [(t_ms, cell)]
        costs = {}
        for _, observed in points:
            candidates = self.support[observed]
            costs = ({f: min(cost + int(previous != f) for previous, cost in costs.items())
                      for f in candidates} if costs else {f: 0 for f in candidates})
        minimum = min(costs.values())
        best = [f for f in self.floors if costs.get(f) == minimum]
        kind, displayed = self.display(best)
        return {"kind": kind, "floors": displayed,
                "reason": "minimum_change_paths" if displayed else "nonadjacent_or_broad_tie",
                "currentSupport": current, "bestCurrentFloors": best,
                "costByCurrentFloor": {f: costs[f] for f in self.floors if f in costs},
                "minimumFloorChanges": minimum,
                "historyDistinctCells": len({c for _, c in points})}


def replay(rows: list[dict], floors: list[str], pairs: list[list[str]], window_ms: int) -> list[dict]:
    tracker = FloorHistoryTracker(floors, pairs, window_ms)
    events = []
    for r in rows:
        cell = r.get("cell")
        prediction = tracker.observe(cell, r["t_ms"], r["visit"])
        prior = list(tracker.history) if window_ms else []
        if window_ms and cell is not None and tracker.support.get(cell):
            prior = prior[:-1]  # The current observation has its own column.
        events.append({"rowId": r["rowId"], "recording": r["visit"], "t_ms": r["t_ms"], "cell": cell,
                       "history": [{"cell": previous, "t_ms": time, "age_ms": r["t_ms"] - time,
                                    "observedFloors": [f for f in floors if f in tracker.support[previous]]}
                                   for time, previous in prior],
                       "historyUsedForPrediction": bool(prior and prediction and prediction["currentSupport"]),
                       "prediction": prediction})
    return events


def load_cell_mapping(path: Path) -> dict:
    mapping = {}
    for item in json.loads(path.read_text()):
        if len(item["tuple"]) != 5 or item["cell"] in mapping:
            raise ValueError("Invalid or duplicated full-cell alias mapping")
        mapping[item["cell"]] = dict(zip(["rat", "mcc", "mnc", "area", "cell_id"], item["tuple"]))
    return mapping


def prediction_text(prediction: dict | None) -> str:
    if prediction is None:
        return "no prediction"
    return " or ".join(prediction["floors"]) if prediction["floors"] else "unknown"


def result_status(event: dict) -> str:
    prediction, truth = event["withHistory"], event["referenceFloor"]
    if prediction is None:
        return "no_prediction"
    if truth is None:
        return "unscored_no_floor_reference"
    if prediction["kind"] == "unknown":
        return "unknown"
    if truth not in prediction["floors"]:
        return "wrong"
    return "exact_correct" if prediction["kind"] == "single_floor" else "adjacent_pair_correct"


def detail_row(event: dict, mapping: dict) -> dict:
    cell, history = event["cell"], event["history"]
    identity = mapping.get(cell, {})
    real_id = lambda alias: str(mapping[alias]["cell_id"]) if alias in mapping else str(alias)
    return {
        "history": " -> ".join(f"{p['cell']} ({p['age_ms']/1000:.1f}s ago)" for p in history),
        "current_cell_id": real_id(cell) if cell is not None else "",
        "prediction": prediction_text(event["withHistory"]),
        "truth_floor": event["referenceFloor"] if event["referenceFloor"] is not None else "unknown",
        "result": result_status(event),
        "current_cell_alias": cell or "",
        "current_cell_only_prediction": prediction_text(event["currentCellOnly"]),
        "history_cell_ids": " -> ".join(f"{real_id(p['cell'])} ({p['age_ms']/1000:.1f}s ago)" for p in history),
        "history_used_for_prediction": event["historyUsedForPrediction"],
        "recording": event["recording"],
        "observed_at_taipei": datetime.fromtimestamp(event["t_ms"]/1000, ZoneInfo("Asia/Taipei")).isoformat(timespec="milliseconds"),
        "row_id": event["rowId"],
        "rat": identity.get("rat", ""), "mcc": identity.get("mcc", ""),
        "mnc": identity.get("mnc", ""), "area": identity.get("area", ""),
        "reference_source": event.get("referenceSource") or "",
    }


def write_database_view(output: Path, floors: list[str], pairs: list[list[str]], mapping: dict) -> int:
    support = defaultdict(set)
    for cell, floor in pairs:
        support[cell].add(floor)
    with (output / "database.csv").open("w", encoding="utf-8-sig", newline="") as sink:
        writer = csv.DictWriter(sink, fieldnames=["cell_id", "floors", "cell_alias", "rat", "mcc", "mnc", "area"])
        writer.writeheader()
        for cell in sorted(support):
            identity = mapping.get(cell, {})
            writer.writerow({"cell_id": str(identity["cell_id"]) if identity else cell,
                             "floors": ", ".join(f for f in floors if f in support[cell]), "cell_alias": cell,
                             "rat": identity.get("rat", ""), "mcc": identity.get("mcc", ""),
                             "mnc": identity.get("mnc", ""), "area": identity.get("area", "")})
    return len(support)


def score(rows: list[dict], predictions: list[dict], floors: list[str]) -> dict:
    if len(rows) != len(predictions):
        raise ValueError("Reference/prediction lengths differ")
    counts = {f: Counter(total=0, exactCorrect=0, exactWrong=0, adjacentPairCorrect=0,
                         adjacentPairWrong=0, unknown=0) for f in floors}
    confusion = {f: Counter() for f in floors}
    for row, event in zip(rows, predictions):
        if row["rowId"] != event["rowId"]:
            raise ValueError("Reference/prediction identity mismatch")
        prediction = event["prediction"]
        if row.get("cell") is None:
            if prediction is not None:
                raise ValueError("Floor prediction without current caller cell")
            continue
        if prediction is None:
            raise ValueError("A returned cell must produce an explicit result, including unknown")
        if row.get("floor") not in counts:
            continue
        floor = row["floor"]
        c = counts[floor]
        c["total"] += 1
        kind, answer = prediction["kind"], prediction["floors"]
        if kind == "single_floor":
            c["exactCorrect" if answer == [floor] else "exactWrong"] += 1
        elif kind == "adjacent_pair":
            c["adjacentPairCorrect" if floor in answer else "adjacentPairWrong"] += 1
        else:
            c["unknown"] += 1
        confusion[floor]["|".join(answer) if answer else "UNKNOWN"] += 1

    def metrics(c: Counter) -> dict:
        total = c["total"]
        exact = c["exactCorrect"] + c["exactWrong"]
        pair = c["adjacentPairCorrect"] + c["adjacentPairWrong"]
        ratio = lambda n, d: n / d if d else None
        return {**dict(c), "exactRecall": ratio(c["exactCorrect"], total),
                "exactAnswerCoverage": ratio(exact, total),
                "accuracyAmongExactAnswers": ratio(c["exactCorrect"], exact),
                "candidateRetention": ratio(c["exactCorrect"] + c["adjacentPairCorrect"], total),
                "answerCoverage": ratio(exact + pair, total)}
    per_floor = {f: metrics(c) for f, c in counts.items()}
    missing = [f for f, c in counts.items() if not c["total"]]
    combined = Counter()
    for c in counts.values():
        combined.update(c)
    return {"perFloor": per_floor, "pooled": metrics(combined),
            "equalFloorExactRecall": None if missing else sum(x["exactRecall"] for x in per_floor.values()) / len(floors),
            "equalFloorCandidateRetention": None if missing else sum(x["candidateRetention"] for x in per_floor.values()) / len(floors),
            "missingReferenceFloors": missing, "confusion": confusion}


def compare_history(rows: list[dict], current: list[dict], history: list[dict], floors: list[str]) -> dict:
    changes = Counter(referenceEligible=0, displayedResultChanged=0, gainedExactCorrect=0,
                      lostExactCorrect=0, gainedReferenceRetention=0, lostReferenceRetention=0,
                      narrowedToCorrectSingle=0, narrowedToWrongSingle=0)
    for row, before, after in zip(rows, current, history):
        if row.get("cell") is None or row.get("floor") not in floors:
            continue
        a, b, floor = before["prediction"], after["prediction"], row["floor"]
        changes["referenceEligible"] += 1
        changes["displayedResultChanged"] += (a["kind"], a["floors"]) != (b["kind"], b["floors"])
        exact_a, exact_b = a["floors"] == [floor], b["floors"] == [floor]
        retained_a, retained_b = floor in a["floors"], floor in b["floors"]
        changes["gainedExactCorrect"] += exact_b and not exact_a
        changes["lostExactCorrect"] += exact_a and not exact_b
        changes["gainedReferenceRetention"] += retained_b and not retained_a
        changes["lostReferenceRetention"] += retained_a and not retained_b
        if b["kind"] == "single_floor" and a["kind"] != "single_floor":
            changes["narrowedToCorrectSingle" if exact_b else "narrowedToWrongSingle"] += 1
    return dict(changes)


def run(prepared: dict, database: dict, output: Path, window_ms=DEFAULT_WINDOW_MS, sources=None, cell_mapping=None) -> dict:
    if window_ms <= 0:
        raise ValueError("History replay requires a positive window")
    if prepared["datasetVersion"] != database["datasetVersion"] or prepared["floors"] != database["floors"]:
        raise ValueError("Prepared observations and database do not match")
    if database.get("activeSource") != "caller" or set(database["models"]) != {"caller"}:
        raise ValueError("A caller-only database is required")
    streams = [s for s in prepared["streams"] if s["side"] == "caller"]
    if len(streams) != 1:
        raise ValueError("Exactly one caller observation stream is required")
    rows, floors = streams[0]["rows"], database["floors"]
    mapping = cell_mapping or {}
    if cell_mapping is not None and any(r.get("cell") is not None and r["cell"] not in mapping for r in rows):
        raise ValueError("The supplied cell mapping must cover every returned caller cell")
    if len({r["rowId"] for r in rows}) != len(rows):
        raise ValueError("Duplicate caller row identity")
    pairs = database["models"]["caller"]["pairs"]
    expected = PresenceFloorModel(rows, floors).export()["pairs"]
    if len(pairs) != len(set(map(tuple, pairs))) or set(map(tuple, pairs)) != set(map(tuple, expected)):
        raise ValueError("The frozen relation must equal the unique accepted pairs from this complete caller dataset")
    current = replay(rows, floors, pairs, 0)
    history = replay(rows, floors, pairs, window_ms)
    # Join scoring references after replay. The frozen relation was deliberately
    # constructed from all accepted labels; runtime observations remain causal.
    methods = {"current_cell_only": current, "with_history": history}
    scores = {name: {"allFloors": score(rows, events, floors),
                     "B1_to_4F": score(rows, events, [f for f in floors if f != "5F"]),
                     "perRecording": {visit: score([r for r in rows if r["visit"] == visit],
                                                   [p for p in events if p["recording"] == visit], floors)
                                      for visit in sorted({r["visit"] for r in rows})}}
              for name, events in methods.items()}
    labeled = [r for r in rows if r.get("floor") in floors]
    result = {
        "revision": REVISION, "datasetVersion": prepared["datasetVersion"], "activeSource": "caller",
        "sources": sources or {},
        "evaluationType": "same_dataset_resubstitution_replay", "floors": floors,
        "detailExport": {"csv": "detailed_results.csv", "allRows": len(rows),
                         "databaseCsv": "database.csv", "databaseRows": len({c for c, _ in pairs}),
                         "historyDefinition": "Chronological earlier known cells retained within the configured window; the current observation is separate. History may be retained but is unused on failed or unseen-current-cell events.",
                         "cellIdentifiers": "Real cell IDs and full identity components, plus aliases" if mapping else "Cell aliases; supply --cell-mapping for real identities",
                         "missingTruth": "unknown", "missingCellPrediction": "no prediction"},
        "config": {"historyWindowMs": window_ms, "stayCost": 0, "anyFloorChangeCost": 1,
                   "outputPolicy": "One minimum-cost current floor, two adjacent tied current floors, or unknown. Full nonadjacent supports retained internally.",
                   "historyPolicy": "Caller cells only; actual past elapsed time; recording resets; events older than the window expire. Missing cells emit no prediction. Unseen current cells emit unknown and add no constraint; earlier known history expires normally.",
                   "repeatPolicy": "Consecutive repeats cannot change minimum-change costs or confidence; a fresh repeat can refresh the age of an observation.",
                   "parameterPolicy": "One initial 60-second setting chosen before viewing scores; no parameter search." if window_ms == DEFAULT_WINDOW_MS else "Explicit caller-specified history window; no automatic parameter search.",
                   "featurePolicy": "Current and past caller Cell IDs, observation timestamps and recording boundaries only. Floor references are used for database construction and scoring, never for replay resets or updates."},
        "availability": {"allProbes": len(rows), "cellReturned": sum(r.get("cell") is not None for r in rows),
                         "noCell": sum(r.get("cell") is None for r in rows),
                         "referenceEligibleProbes": len(labeled),
                         "scoredCellBearingReferences": sum(r.get("cell") is not None for r in labeled),
                         "cellBearingWithoutFloorReference": sum(r.get("cell") is not None and r.get("floor") not in floors for r in rows)},
        "scores": scores, "historyEffect": compare_history(rows, current, history, floors),
        "limitations": [
            "Every accepted recording supplies database associations and is replayed for scoring; these are same-dataset results, not independent validation or future accuracy.",
            "Full-database floor support uses all accepted labels, including later observations. Only runtime history is causal; model construction is deliberately retrospective.",
            "Single-floor supports and current-cell retention are partly guaranteed by construction; scores mainly expose ambiguity and errors introduced by the history assumption.",
            "Equal-floor metrics remove floor-count dominance but not within-floor section coverage or correlated measurements.",
            "Reference floors remain user-supported/altitude-derived; transition references may be unknown and cross-phone timing has no independent calibration.",
            "Fewer changes is a heuristic, not a probability or guarantee. Elevators and incomplete cell support can invalidate its preferred path."]}
    output.mkdir(parents=True, exist_ok=True)
    write_database_view(output, floors, pairs, mapping)
    with (output / "replay_events.jsonl").open("w") as events_sink, (output / "predictions.jsonl").open("w") as prediction_sink, \
            (output / "detailed_results.csv").open("w", encoding="utf-8-sig", newline="") as detail_sink:
        writer = None
        for row, a, b in zip(rows, current, history):
            event = {"rowId": row["rowId"], "recording": row["visit"], "t_ms": row["t_ms"],
                     "cell": row.get("cell"), "referenceFloor": row.get("floor"),
                     "referenceSource": row.get("referenceSource"),
                     "history": [{**p, "cellIdentity": mapping.get(p["cell"])} for p in b["history"]],
                     "historyUsedForPrediction": b["historyUsedForPrediction"],
                     "currentCellIdentity": mapping.get(row.get("cell")),
                     "currentCellOnly": a["prediction"], "withHistory": b["prediction"]}
            events_sink.write(json.dumps(event, separators=(",", ":")) + "\n")
            detail = detail_row(event, mapping)
            if writer is None:
                writer = csv.DictWriter(detail_sink, fieldnames=list(detail))
                writer.writeheader()
            writer.writerow(detail)
            if row.get("cell") is not None:
                prediction_sink.write(json.dumps(event, separators=(",", ":")) + "\n")
    (output / "SAME_DATASET_RESULTS.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--window-seconds", type=int, default=60)
    parser.add_argument("--cell-mapping", type=Path, help="Full cell alias mapping; defaults to private_cell_mapping.json beside --prepared when available")
    args = parser.parse_args()
    mapping_path = args.cell_mapping or args.prepared.with_name("private_cell_mapping.json")
    mapping = load_cell_mapping(mapping_path) if mapping_path.exists() else None
    if args.cell_mapping and mapping is None:
        parser.error("The specified cell mapping file does not exist")
    sources = {"prepared": fingerprint(args.prepared), "database": fingerprint(args.database)}
    if mapping is not None:
        sources["cellMapping"] = fingerprint(mapping_path)
    result = run(json.loads(args.prepared.read_text()), json.loads(args.database.read_text()), args.output,
                 args.window_seconds * 1000, sources, mapping)
    print(json.dumps({name: {"equalFloorExactRecall": s["allFloors"]["equalFloorExactRecall"],
                            "equalFloorCandidateRetention": s["allFloors"]["equalFloorCandidateRetention"]}
                      for name, s in result["scores"].items()}))


if __name__ == "__main__":
    main()
