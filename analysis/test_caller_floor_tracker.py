import copy
import csv
from itertools import product
import json
from pathlib import Path
import tempfile
import unittest

from caller_floor_tracker import FloorHistoryTracker, compare_history, replay, run, score
from floor_algorithm_study import PresenceFloorModel

FLOORS = ["B1", "1F", "2F", "3F", "4F", "5F"]
PAIRS = [["A", "1F"], ["A", "5F"], ["B", "4F"], ["B", "5F"],
         ["C", "1F"], ["D", "4F"], ["E", "1F"], ["E", "2F"]]


def event(i, cell, floor=None, t=None, visit="one"):
    return {"rowId": str(i), "cell": cell, "floor": floor, "t_ms": i * 1000 if t is None else t,
            "visit": visit, "measurementId": visit + str(i)}


class CallerFloorTrackerTest(unittest.TestCase):
    def test_proposed_shared_cell_example_resolves_to_5f(self):
        t = FloorHistoryTracker(FLOORS, PAIRS)
        self.assertEqual(t.observe("A", 0, "one")["kind"], "unknown")
        p = t.observe("B", 10000, "one")
        self.assertEqual(p["floors"], ["5F"])
        self.assertEqual(p["costByCurrentFloor"], {"4F": 1, "5F": 0})

    def test_adjacent_pair_policy_preserves_nonadjacent_support_internally(self):
        t = FloorHistoryTracker(FLOORS, PAIRS + [["Z", "B1"], ["Z", "1F"]])
        self.assertEqual(t.observe("Z", 0, "one")["floors"], ["B1", "1F"])
        self.assertEqual(t.observe("E", 100000, "one")["floors"], ["1F", "2F"])
        p = t.observe("A", 200000, "one")
        self.assertEqual(p["kind"], "unknown")
        self.assertEqual(p["bestCurrentFloors"], ["1F", "5F"])
        self.assertEqual(p["currentSupport"], ["1F", "5F"])

    def test_elevator_jump_and_change_after_conflict_are_allowed(self):
        t = FloorHistoryTracker(FLOORS, PAIRS)
        t.observe("C", 0, "one")
        p = t.observe("B", 10000, "one")
        self.assertEqual(p["floors"], ["4F", "5F"])
        self.assertEqual(p["minimumFloorChanges"], 1)
        self.assertEqual(t.observe("D", 20000, "one")["floors"], ["4F"])

    def test_expiry_and_recording_reset_use_no_reference_labels(self):
        t = FloorHistoryTracker(FLOORS, PAIRS)
        t.observe("A", 0, "one")
        self.assertEqual(t.observe("B", 60001, "one")["floors"], ["4F", "5F"])
        t.observe("A", 70000, "one")
        self.assertEqual(t.observe("B", 0, "two")["floors"], ["4F", "5F"])

    def test_failed_and_unseen_observations_never_emit_retained_floor(self):
        t = FloorHistoryTracker(FLOORS, PAIRS)
        t.observe("D", 0, "one")
        self.assertIsNone(t.observe(None, 10000, "one"))
        unseen = t.observe("new", 20000, "one")
        self.assertEqual(unseen["kind"], "unknown")
        self.assertEqual(unseen["floors"], [])
        self.assertIsNone(t.observe(None, 100000, "one"))
        self.assertEqual(t.observe("B", 100001, "one")["floors"], ["4F", "5F"])

    def test_duplicate_pairs_and_consecutive_repeats_cannot_boost_costs(self):
        a, b = FloorHistoryTracker(FLOORS, PAIRS), FloorHistoryTracker(FLOORS, PAIRS * 100)
        a.observe("A", 0, "one")
        for time in range(10):
            b.observe("A", time, "one")
        left, right = a.observe("B", 10000, "one"), b.observe("B", 10000, "one")
        for field in ["floors", "costByCurrentFloor", "minimumFloorChanges"]:
            self.assertEqual(left[field], right[field])

    def test_dynamic_program_matches_exhaustive_path_enumeration(self):
        supports = {"A": ["1F", "5F"], "B": ["4F", "5F"], "C": ["1F"], "E": ["1F", "2F"]}
        for sequence in product(supports, repeat=4):
            t = FloorHistoryTracker(FLOORS, PAIRS)
            for i, cell in enumerate(sequence):
                result = t.observe(cell, i, "one")
            expected = {}
            for path in product(*(supports[c] for c in sequence)):
                cost = sum(a != b for a, b in zip(path, path[1:]))
                expected[path[-1]] = min(expected.get(path[-1], 99), cost)
            self.assertEqual(result["costByCurrentFloor"], expected)

    def test_replay_prefix_ignores_target_data_and_future_observations(self):
        rows = [event(1, "A", "1F"), event(2, "B", "4F")]
        expected = replay(rows, FLOORS, PAIRS, 60000)
        changed = copy.deepcopy(rows)
        for r in changed:
            r.update(floor="secret", segment="reference reset", localCell="secret", altitude=999)
        changed.append(event(3, "C", "5F"))
        self.assertEqual(replay(changed, FLOORS, PAIRS, 60000)[:2], expected)

    def test_export_history_contains_only_retained_earlier_inputs(self):
        rows = [event(1, "A", t=0), event(2, "B", t=10000),
                event(3, None, t=20000), event(4, "new", t=30000),
                event(5, "B", t=60001), event(6, "A", t=0, visit="two")]
        events = replay(rows, FLOORS, PAIRS, 60000)
        self.assertEqual(events[0]["history"], [])
        self.assertEqual(events[1]["history"],
                         [{"cell": "A", "t_ms": 0, "age_ms": 10000, "observedFloors": ["1F", "5F"]}])
        self.assertTrue(events[1]["historyUsedForPrediction"])
        self.assertFalse(events[2]["historyUsedForPrediction"])
        self.assertFalse(events[3]["historyUsedForPrediction"])
        self.assertEqual([p["cell"] for p in events[4]["history"]], ["B"])
        self.assertEqual(events[5]["history"], [])
        self.assertTrue(all(not e["history"] for e in replay(rows, FLOORS, PAIRS, 0)))

    def test_nonchronological_input_is_rejected(self):
        t = FloorHistoryTracker(FLOORS, PAIRS)
        t.observe("A", 10, "one")
        with self.assertRaises(ValueError):
            t.observe("B", 9, "one")

    def test_scoring_separates_exact_pair_unknown_and_missing_cell(self):
        rows = [event(1, "D", "4F"), event(2, "E", "1F"), event(3, "A", "5F"),
                event(4, None, "4F"), event(5, "unseen", "5F")]
        predictions = replay(rows, FLOORS, PAIRS, 0)
        s = score(rows, predictions, ["1F", "4F", "5F"])
        self.assertEqual(s["pooled"]["total"], 4)
        self.assertEqual(s["pooled"]["exactCorrect"], 1)
        self.assertEqual(s["pooled"]["adjacentPairCorrect"], 1)
        self.assertEqual(s["pooled"]["unknown"], 2)
        self.assertAlmostEqual(s["equalFloorExactRecall"], 1/3)
        self.assertAlmostEqual(s["equalFloorCandidateRetention"], 2/3)
        failed = rows + [event(i, None, "4F") for i in range(10, 110)]
        self.assertEqual(score(failed, replay(failed, FLOORS, PAIRS, 0), ["1F", "4F", "5F"]), s)

    def test_history_can_be_wrong_and_is_counted_as_wrong(self):
        rows = [event(1, "A", "1F"), event(2, "B", "4F")]
        current = replay(rows, FLOORS, PAIRS, 0)
        history = replay(rows, FLOORS, PAIRS, 60000)
        self.assertEqual(score(rows, history, ["1F", "4F"])["pooled"]["exactWrong"], 1)
        effects = compare_history(rows, current, history, FLOORS)
        self.assertEqual(effects["lostReferenceRetention"], 1)
        self.assertEqual(effects["narrowedToWrongSingle"], 1)

    def test_run_uses_complete_same_caller_data_and_exports_no_failed_predictions(self):
        rows = [event(1, "A", "1F"), event(2, "A", "5F"), event(3, "B", "4F"),
                event(4, "B", "5F"), event(5, None, "4F"), event(6, "B", None)]
        database = {"datasetVersion": "sample", "floors": FLOORS, "activeSource": "caller",
                    "models": {"caller": PresenceFloorModel(rows, FLOORS).export()}}
        prepared = {"datasetVersion": "sample", "floors": FLOORS,
                    "streams": [{"side": "caller", "rows": rows}, {"side": "local", "rows": [event(1,"other","2F")]}]}
        with tempfile.TemporaryDirectory() as tmp:
            result = run(prepared, database, Path(tmp))
            self.assertEqual(result["evaluationType"], "same_dataset_resubstitution_replay")
            self.assertEqual(result["availability"]["scoredCellBearingReferences"], 4)
            predictions = [json.loads(l) for l in (Path(tmp)/"predictions.jsonl").read_text().splitlines()]
            self.assertEqual(len(predictions), 5)
            self.assertTrue(all(r["cell"] is not None for r in predictions))
            wrong = copy.deepcopy(database)
            wrong["models"]["caller"]["pairs"].append(["other", "2F"])
            with self.assertRaises(ValueError):
                run(prepared, wrong, Path(tmp))

    def test_detail_csv_matches_history_predictions_floor_truth_and_cell_mapping(self):
        rows = [event(1, "A", "1F"), event(2, "A", "5F"), event(3, "B", "4F"),
                event(4, "B", "5F"), event(5, None, "4F"), event(6, "B", None)]
        database = {"datasetVersion": "sample", "floors": FLOORS, "activeSource": "caller",
                    "models": {"caller": PresenceFloorModel(rows, FLOORS).export()}}
        prepared = {"datasetVersion": "sample", "floors": FLOORS,
                    "streams": [{"side": "caller", "rows": rows}]}
        mapping = {alias: {"rat": "LTE", "mcc": 1, "mnc": 2, "area": 3, "cell_id": cid}
                   for alias, cid in [("A", 101), ("B", 202)]}
        with tempfile.TemporaryDirectory() as tmp:
            run(prepared, database, Path(tmp), cell_mapping=mapping)
            with (Path(tmp)/"detailed_results.csv").open(encoding="utf-8-sig", newline="") as source:
                detail = list(csv.DictReader(source))
            self.assertEqual(len(detail), len(rows))
            self.assertEqual(detail[0]["history"], "")
            self.assertEqual(detail[2]["current_cell_alias"], "B")
            self.assertEqual(detail[2]["current_cell_id"], "202")
            self.assertEqual(detail[2]["history"], "A (2.0s ago) -> A (1.0s ago)")
            self.assertEqual(detail[2]["history_cell_ids"], "101 (2.0s ago) -> 101 (1.0s ago)")
            self.assertEqual(detail[2]["prediction"], "5F")
            self.assertEqual(detail[2]["truth_floor"], "4F")
            self.assertEqual(detail[2]["result"], "wrong")
            self.assertEqual(detail[4]["prediction"], "no prediction")
            self.assertEqual(detail[4]["history_used_for_prediction"], "False")
            self.assertEqual(detail[5]["truth_floor"], "unknown")
            self.assertEqual(detail[5]["result"], "unscored_no_floor_reference")
            with (Path(tmp)/"database.csv").open(encoding="utf-8-sig", newline="") as source:
                cells = list(csv.DictReader(source))
            self.assertEqual(len(cells), 2)
            self.assertEqual([(c["cell_id"], c["floors"]) for c in cells],
                             [("101", "1F, 5F"), ("202", "4F, 5F")])
            self.assertEqual([c["cell_alias"] for c in cells], ["A", "B"])
            wrong = copy.deepcopy(database)
            wrong["models"]["local"] = {"pairs": []}
            with self.assertRaises(ValueError):
                run(prepared, wrong, Path(tmp))


if __name__ == "__main__":
    unittest.main()
