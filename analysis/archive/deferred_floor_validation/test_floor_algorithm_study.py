import copy
import json
from pathlib import Path
import tempfile
import unittest

from extend_floor_database import combine_rows
from floor_algorithm_study import PresenceFloorModel, evaluate, purge, replay, run, score


FLOORS = ["1F", "4F", "5F"]
METHOD = "presence_lookup"


def row(i, cell="A", floor="4F", t=None, visit="train", segment=None):
    return {"t_ms": i * 1000 if t is None else t, "cell": cell, "floor": floor,
            "rowId": str(i), "visit": visit, "episode": visit,
            "segment": segment or visit, "measurementId": visit + str(i),
            "locationFixId": "fix/" + visit + str(i)}


def training():
    return [row(1, "A", "4F"), row(2, "A", "5F"),
            row(3, "B", "1F"), row(4, "B", "5F")]


class PresenceLookupTest(unittest.TestCase):
    def test_section_specific_repetition_cannot_change_model_or_predictions(self):
        train = training() + [row(5, "C", "4F")]
        repeated = train + [row(i, "A", "4F") for i in range(100, 200)]
        a, b = (PresenceFloorModel(r, FLOORS) for r in (train, repeated))
        test = [row(1, "A", None, visit="test"), row(2, "B", None, visit="test")]
        self.assertEqual(a.export(), b.export())
        self.assertEqual(replay(a, test), replay(b, test))
        pairs = a.export()["pairs"]
        self.assertEqual(len(pairs), len(set(map(tuple, pairs))))
        self.assertEqual(replay(a, test)[0]["predictions"], {METHOD: ["4F", "5F"]})

    def test_full_candidate_set_expands_without_rank_or_probability(self):
        a = PresenceFloorModel([row(1, "A", "1F")], FLOORS)
        b = PresenceFloorModel([row(i, "A", f) for i, f in enumerate(FLOORS)], FLOORS)
        self.assertEqual(a.lookup("A"), ["1F"])
        self.assertEqual(b.lookup("A"), FLOORS)
        self.assertEqual(set(b.export()), {"pairs"})

    def test_prediction_reads_current_cell_only(self):
        model = PresenceFloorModel(training(), FLOORS)
        rows = [row(1, "A", None, visit="test"), row(2, "B", None, visit="test")]
        self.assertEqual(replay(model, rows)[1]["predictions"], {METHOD: ["1F", "5F"]})
        changed = copy.deepcopy(rows)
        changed[0]["cell"] = "B"
        for r in changed:
            r.update(floor="secret reference", referenceSource="secret", t_ms=r["t_ms"] * 100,
                     segment="different", lat=99, altitude=99)
        changed.append(row(3, "A", "1F", visit="test"))
        self.assertEqual(replay(model, changed)[1]["predictions"], replay(model, rows)[1]["predictions"])
        self.assertEqual(replay(model, [rows[1]])[0]["predictions"], replay(model, rows)[1]["predictions"])

    def test_no_cell_or_unseen_current_cell_never_inherits_answer(self):
        model = PresenceFloorModel(training(), FLOORS)
        rows = [row(1, "A", visit="test"), row(2, None, visit="test"), row(3, "new", visit="test")]
        result = replay(model, rows)
        self.assertEqual(result[1]["predictions"], {METHOD: []})
        self.assertFalse(result[1]["predictionEligible"])
        self.assertEqual(result[2]["predictions"], {METHOD: []})
        self.assertTrue(result[2]["predictionEligible"])
        self.assertTrue(result[2]["unseenCell"])

    def test_conflicting_measurement_labels_and_unknown_references_are_excluded(self):
        train = [row(1, "A", "1F"), row(2, "A", "4F"), row(3, "B", None)]
        train[1]["measurementId"] = train[0]["measurementId"]
        model = PresenceFloorModel(train, FLOORS)
        self.assertEqual(model.export(), {"pairs": []})
        self.assertEqual(model.audit["excluded_conflicting_measurement_ids"], 1)

    def test_shared_fix_and_measurement_purged_before_training(self):
        train = [row(1), row(2), row(3)]
        test = [row(100, visit="test"), row(101, visit="test")]
        test[0]["measurementId"] = train[0]["measurementId"]
        test[1]["locationFixId"] = train[1]["locationFixId"]
        kept, audit = purge(train, test)
        self.assertEqual(kept, [train[2]])
        self.assertEqual(audit["purgedTrainingRows"], 2)

    def test_held_out_association_cannot_enter_training(self):
        stream = {"side": "caller", "rows": [row(1, "A", "1F"), row(1, "A", "4F", visit="test")]}
        split = {"id": "leave_out_test", "trainEpisodes": ["train"], "testEpisodes": ["test"]}
        evaluation, predictions = evaluate(stream, split, FLOORS)
        self.assertEqual(predictions[0]["predictions"], {METHOD: ["1F"]})
        self.assertEqual(evaluation["methods"][METHOD]["all"]["nCorrect"], 0)
        split["trainEpisodes"].append("test")
        with self.assertRaises(ValueError):
            evaluate(stream, split, FLOORS)

    def test_candidate_retention_is_separate_from_exact_success(self):
        result = score([row(1, floor="5F")], [["4F", "5F"]], FLOORS)
        self.assertEqual(result["nCorrect"], 0)
        self.assertEqual(result["candidateSets"]["nContainingReference"], 1)
        self.assertEqual(result["candidateSets"]["meanSizeAmongAnswers"], 2)

    def test_generated_workflow_contains_only_presence_lookup(self):
        prepared = {"datasetVersion": "test", "floors": FLOORS,
                    "streams": [{"side": "caller", "rows": training()}],
                    "splits": [{"id": "leave_out_empty", "trainEpisodes": ["train"], "testEpisodes": ["empty"]}],
                    "featurePolicy": "current cell only", "limitations": []}
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            result = run(prepared, output)
            self.assertEqual(result["config"]["activeMethod"], METHOD)
            self.assertEqual(set(result["evaluations"][0]["methods"]), {METHOD})
            database = json.loads((output / "presence_database.json").read_text())
            self.assertEqual(database["activeMethod"], METHOD)
            self.assertEqual(set(database["models"]["caller"]), {"pairs"})
            self.assertFalse((output / "models.json").exists())
            (output / "models.json").write_text("{}")
            with self.assertRaises(ValueError):
                run(prepared, output)

    def test_extension_keeps_quality_metadata_separate_from_inference(self):
        old = [{**row(1), "recording": "afternoon", "lat": 99, "localCell": "secret", "radioAgeMs": 1234},
               {**row(2, floor="5F"), "recording": "afternoon"}]
        new = [{**row(1, t=100000), "recording": "nov16"}]
        result = combine_rows(old, new, "caller")
        self.assertNotIn("lat", result[0])
        self.assertNotIn("localCell", result[0])
        self.assertNotIn("radioAgeMs", result[0])
        self.assertEqual(result[0]["segment"], result[1]["segment"])
        self.assertNotEqual(result[0]["measurementId"], result[-1]["measurementId"])


if __name__ == "__main__":
    unittest.main()
