import json
from pathlib import Path
import tempfile
import unittest

from extend_floor_database import combine_rows
from floor_algorithm_study import PresenceFloorModel, describe_associations, run


FLOORS = ["1F", "4F", "5F"]


def row(i, cell="A", floor="4F", visit="one"):
    return {"t_ms": i * 1000, "cell": cell, "floor": floor,
            "rowId": str(i), "visit": visit, "episode": visit, "segment": visit,
            "measurementId": visit + str(i), "locationFixId": "fix/" + visit + str(i)}


def observations():
    return [row(1, "A", "4F"), row(2, "A", "5F"),
            row(3, "B", "1F"), row(4, "B", "5F")]


class PresenceDatasetTest(unittest.TestCase):
    def test_repeating_one_section_cannot_change_associations(self):
        rows = observations() + [row(5, "C", "4F")]
        repeated = rows + [row(i, "A", "4F") for i in range(100, 200)]
        a, b = (PresenceFloorModel(r, FLOORS) for r in (rows, repeated))
        self.assertEqual(a.export(), b.export())
        self.assertEqual(describe_associations(a), describe_associations(b))
        pairs = a.export()["pairs"]
        self.assertEqual(len(pairs), len(set(map(tuple, pairs))))

    def test_full_candidate_set_expands_without_rank_or_probability(self):
        a = PresenceFloorModel([row(1, "A", "1F")], FLOORS)
        b = PresenceFloorModel([row(i, "A", f) for i, f in enumerate(FLOORS)], FLOORS)
        self.assertEqual(a.lookup("A"), ["1F"])
        self.assertEqual(b.lookup("A"), FLOORS)
        self.assertEqual(set(b.export()), {"pairs"})

    def test_missing_and_unseen_cell_have_no_candidate(self):
        model = PresenceFloorModel(observations(), FLOORS)
        self.assertEqual(model.lookup("A"), ["4F", "5F"])
        self.assertEqual(model.lookup("B"), ["1F", "5F"])
        self.assertEqual(model.lookup("unseen"), [])
        self.assertEqual(model.lookup(None), [])

    def test_conflicting_measurement_labels_and_unknown_references_excluded(self):
        rows = [row(1, "A", "1F"), row(2, "A", "4F"), row(3, "B", None)]
        rows[1]["measurementId"] = rows[0]["measurementId"]
        model = PresenceFloorModel(rows, FLOORS)
        self.assertEqual(model.export(), {"pairs": []})
        self.assertEqual(model.audit["excluded_conflicting_measurement_ids"], 1)

    def test_describes_floor_overlap_and_recording_provenance(self):
        rows = observations() + [row(10, "A", "4F", visit="two"), row(11, "C", "4F", visit="three")]
        report = describe_associations(PresenceFloorModel(rows, FLOORS))
        self.assertEqual(report["distinctCellFloorPairs"], 5)
        self.assertEqual(report["cellsByNumberOfObservedFloors"], {1: 1, 2: 2})
        self.assertEqual(report["perFloor"]["4F"]["onlyObservedOnThisFloor"], ["C"])
        overlap = report["sharedCellCountsBetweenFloors"]
        self.assertEqual(overlap["1F"]["4F"], 0)
        self.assertEqual(overlap["4F"]["5F"], 1)
        self.assertEqual(overlap["5F"]["4F"], 1)
        pair = next(p for p in report["associationProvenance"] if p["cell"] == "A" and p["floor"] == "4F")
        self.assertEqual(pair["recordings"], ["one", "two"])

    def test_uses_all_caller_recordings_and_excludes_local_relations(self):
        caller = [row(1, "A", "1F", "one"), row(2, "A", "4F", "two"), row(3, "A", "5F", "three")]
        local = [row(1, "A", "4F", "one")]
        prepared = {"datasetVersion": "sample", "floors": FLOORS,
                    "streams": [{"side": "caller", "rows": caller}, {"side": "local", "rows": local}],
                    "limitations": []}
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            report = run(prepared, output)
            db = json.loads((output / "presence_database.json").read_text())
            self.assertEqual(db["models"]["caller"]["pairs"], [["A", f] for f in FLOORS])
            self.assertEqual(set(db["models"]), {"caller"})
            self.assertEqual(set(report["profiles"]), {"caller"})
            self.assertEqual(set(report["associations"]), {"caller"})
            self.assertEqual(db["activeSource"], "caller")
            self.assertEqual(report["profiles"]["caller"]["recordings"], ["one", "three", "two"])
            self.assertEqual({p.name for p in output.iterdir()}, {"presence_database.json", "association_analysis.json"})
            for key in ("splits", "evaluations", "predictions", "accuracy", "primaryValidation"):
                self.assertNotIn(key, report)

    def test_local_associations_cannot_add_floors_to_caller_cell(self):
        prepared = {"datasetVersion": "sample", "floors": FLOORS,
                    "streams": [{"side": "caller", "rows": [row(1, "A", "1F")]},
                                {"side": "local", "rows": [row(1, "A", "5F")]}],
                    "limitations": []}
        with tempfile.TemporaryDirectory() as tmp:
            report = run(prepared, Path(tmp))
            self.assertEqual(report["associations"]["caller"]["cells"],
                             [{"cell": "A", "observedFloors": ["1F"]}])

    def test_local_only_input_cannot_be_used_as_caller_data(self):
        prepared = {"datasetVersion": "sample", "floors": FLOORS,
                    "streams": [{"side": "local", "rows": observations()}], "limitations": []}
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                run(prepared, Path(tmp))

    def test_refuses_output_directory_containing_old_validation_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            (output / "BALANCED_VALIDATION.json").write_text("{}")
            with self.assertRaises(ValueError):
                run({"floors": FLOORS}, output)

    def test_extension_preserves_all_rows_and_separates_target_diagnostics(self):
        old = [{**row(1), "recording": "afternoon", "lat": 99, "localCell": "secret", "radioAgeMs": 1234},
               {**row(2, floor="5F"), "recording": "afternoon"}]
        new = [{**row(100), "recording": "nov16"}]
        result = combine_rows(old, new, "caller")
        self.assertEqual(len(result), 3)
        self.assertNotIn("lat", result[0])
        self.assertNotIn("localCell", result[0])
        self.assertNotIn("radioAgeMs", result[0])
        self.assertNotEqual(result[0]["measurementId"], result[-1]["measurementId"])


if __name__ == "__main__":
    unittest.main()
