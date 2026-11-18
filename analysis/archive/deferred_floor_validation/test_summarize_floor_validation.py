import copy
import unittest

from summarize_floor_validation import score, summarize


def row(i, floor, answer, visit="one", split="leave_out_one", cell="A", unseen=False):
    return {"rowId": str(i), "referenceFloor": floor, "predictions": {"presence_lookup": answer},
            "side": "local", "visit": visit, "split": split, "cell": cell, "unseenCell": unseen}


class BalancedValidationTest(unittest.TestCase):
    def test_rejects_retired_algorithms(self):
        retired = row(1, "B1", ["B1"])
        retired["predictions"]["old_weighted_vote"] = ["B1"]
        with self.assertRaises(ValueError):
            summarize([retired], ["B1"])

    def test_candidate_retention_is_not_exact_accuracy(self):
        rows = [row(1, "B1", ["B1", "1F"]), row(2, "1F", ["B1", "1F"])]
        result = score(rows, "presence_lookup", ["B1", "1F"])
        self.assertEqual(result["primaryEqualFloorRecall"], 0)
        self.assertEqual(result["candidateSets"]["equalFloorRetention"], 1)
        self.assertEqual(result["candidateSets"]["meanSizeAmongAnswers"], 2)
        self.assertEqual(result["perFloor"]["B1"]["candidateRetention"], 1)

    def test_failed_probes_only_change_availability(self):
        rows = [row(1, "B1", ["B1"]), row(2, "1F", [])]
        failed = [row(i, "B1", [], cell=None) for i in range(3, 103)]
        a = summarize(rows, ["B1", "1F"])["channels"]["local"]
        b = summarize(rows + failed, ["B1", "1F"])["channels"]["local"]
        self.assertEqual(a["methods"], b["methods"])
        self.assertLess(b["cellAvailability"]["rateOnRequestedFloorReferences"],
                        a["cellAvailability"]["rateOnRequestedFloorReferences"])

    def test_returned_unseen_cell_is_not_removed_from_accuracy(self):
        rows = [row(1, "B1", ["B1"]), row(2, "1F", [], cell="NEW", unseen=True)]
        result = summarize(rows, ["B1", "1F"])["channels"]["local"]
        self.assertEqual(result["returnedUnseenCellEvents"], 1)
        self.assertEqual(result["methods"]["presence_lookup"]["nReferenceEligible"], 2)
        self.assertEqual(result["methods"]["presence_lookup"]["primaryEqualFloorRecall"], .5)

    def test_rejects_retained_prediction_on_failed_probe(self):
        with self.assertRaises(ValueError):
            summarize([row(1, "B1", ["B1"], cell=None)], ["B1"])

    def test_longer_easy_floor_does_not_raise_equal_floor_score(self):
        small = [row(1, "B1", ["B1"]), row(2, "1F", ["B1"])]
        large = small + [row(i, "B1", ["B1"]) for i in range(3, 103)]
        a, b = (score(rows, "presence_lookup", ["B1", "1F"]) for rows in (small, large))
        self.assertEqual(a["primaryEqualFloorRecall"], .5)
        self.assertEqual(b["primaryEqualFloorRecall"], .5)
        self.assertGreater(b["secondaryObservationWeightedAccuracy"], a["secondaryObservationWeightedAccuracy"])

    def test_unknown_is_a_miss_and_missing_floor_cannot_receive_full_score(self):
        rows = [row(1, "B1", ["B1"]), row(2, "1F", [])]
        self.assertEqual(score(rows, "presence_lookup", ["B1", "1F"])["primaryEqualFloorRecall"], .5)
        only_one = score(rows[:1], "presence_lookup", ["B1", "1F"])
        self.assertIsNone(only_one["primaryEqualFloorRecall"])
        self.assertEqual(only_one["missingReferenceFloors"], ["1F"])

    def test_no_duplicate_test_rows_or_reverse_date_double_count(self):
        rows = [row(1, "B1", ["B1"]), row(2, "1F", ["1F"])]
        diagnostic = copy.deepcopy(rows[0])
        diagnostic["split"] = "nov16_train_nov10_test"
        result = summarize(rows + [diagnostic], ["B1", "1F"])
        self.assertEqual(result["channels"]["local"]["methods"]["presence_lookup"]["nReferenceEligible"], 2)
        with self.assertRaises(ValueError):
            summarize(rows + [rows[0]], ["B1", "1F"])


if __name__ == "__main__":
    unittest.main()
