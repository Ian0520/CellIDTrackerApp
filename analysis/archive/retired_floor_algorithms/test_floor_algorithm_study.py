import copy
import unittest

from extend_floor_database import combine_rows
from floor_algorithm_study import FloorModel, PresenceFloorModel, history_weights, purge, replay, score


FLOORS = ["1F", "4F", "5F"]


def row(i, cell="A", floor="4F", t=None, visit="train", segment=None):
    return {"t_ms": i * 1000 if t is None else t, "cell": cell, "floor": floor,
            "rowId": str(i), "visit": visit, "episode": visit,
            "segment": segment or visit, "measurementId": visit + str(i),
            "locationFixId": "fix/" + visit + str(i)}


def ambiguous_training():
    return [row(1, "A", "4F"), row(2, "A", "5F"),
            row(3, "B", "1F"), row(4, "B", "5F")]


def unequal_exposure_training(b1_scale=1):
    mix = [("B1", "A")] * (7 * b1_scale) + [("B1", "B")] * (93 * b1_scale)
    mix += [("1F", "A")] * 3 + [("1F", "B")] * 7
    return [row(i, cell, floor) for i, (floor, cell) in enumerate(mix)]


class FloorStudyTest(unittest.TestCase):
    def test_presence_relation_ignores_repeated_samples_in_one_section(self):
        train = ambiguous_training() + [row(5, "C", "4F")]
        # More samples of A on 4F change the within-floor mix and raw counts,
        # but cannot change any pair in the presence-only relation.
        repeated = train + [row(i, "A", "4F") for i in range(100, 200)]
        a, b = (FloorModel(r, FLOORS) for r in (train, repeated))
        self.assertEqual(a.presence.export(), b.presence.export())
        self.assertNotEqual(a.floor_normalized["A"], b.floor_normalized["A"])
        test = [row(1, "A", None, visit="test"), row(2, "B", None, visit="test")]
        for left, right in zip(replay(a, test), replay(b, test)):
            for method in ("presence_lookup", "presence_intersection_60s"):
                self.assertEqual(left["predictions"][method], right["predictions"][method])
        exported = a.presence.export()["pairs"]
        self.assertEqual(len(exported), len({tuple(p) for p in exported}))

    def test_presence_support_expands_without_fabricated_probabilities(self):
        a = PresenceFloorModel([row(1, "A", "1F")], FLOORS)
        b = PresenceFloorModel([row(1, "A", "1F"), row(2, "A", "4F"),
                                row(3, "A", "5F")], FLOORS)
        self.assertEqual(a.lookup("A"), ["1F"])
        self.assertEqual(b.lookup("A"), FLOORS)
        self.assertEqual(b.lookup("new"), [])
        self.assertEqual(b.lookup(None), [])
        # The full set is retained, not silently truncated to two floors.
        model = FloorModel([row(i, "A", f) for i, f in enumerate(FLOORS)], FLOORS)
        self.assertEqual(replay(model, [row(1, visit="test")])[0]["predictions"]["presence_lookup"], FLOORS)

    def test_presence_intersection_reports_conflict_and_unknown_current(self):
        model = PresenceFloorModel(ambiguous_training(), FLOORS)
        points = [row(1, "A"), row(2, "B")]
        self.assertEqual(model.intersection("B", points), ["5F"])
        self.assertEqual(model.intersection("new", points), [])
        self.assertEqual(model.intersection(None, points), [])
        conflict = PresenceFloorModel([row(1, "A", "1F"), row(2, "B", "4F")], FLOORS)
        self.assertEqual(conflict.intersection("B", points), [])

    def test_presence_training_fold_does_not_read_test_associations(self):
        train = [row(1, "A", "1F")]
        test = [row(1, "A", "4F", visit="test")]
        result = replay(FloorModel(train, FLOORS), test)
        self.assertEqual(result[0]["predictions"]["presence_lookup"], ["1F"])

    def test_normalized_history_corrects_unequal_floor_exposure(self):
        model = FloorModel(unequal_exposure_training(), ["B1", "1F"])
        predictions = replay(model, [row(1, "A", None, visit="test")])[0]["predictions"]
        self.assertEqual(predictions["window_vote_60s"], ["B1"])
        self.assertEqual(predictions["window_normalized_vote_60s"], ["1F"])
        self.assertAlmostEqual(model.floor_normalized["A"]["1F"], .30 / (.07 + .30))

    def test_normalized_weights_ignore_same_mix_extra_floor_samples(self):
        a = FloorModel(unequal_exposure_training(1), ["B1", "1F"])
        b = FloorModel(unequal_exposure_training(10), ["B1", "1F"])
        for cell in ("A", "B"):
            for floor in ("B1", "1F"):
                self.assertAlmostEqual(a.floor_normalized[cell][floor], b.floor_normalized[cell][floor])
        self.assertNotEqual(a.raw["A"], b.raw["A"])

    def test_combination_reduces_ambiguous_floor_sets(self):
        model = FloorModel(ambiguous_training(), FLOORS)
        test = [row(1, "A", None, visit="test"), row(2, "B", None, visit="test")]
        result = replay(model, test)
        self.assertEqual(set(result[0]["predictions"]["support_set"]), {"4F", "5F"})
        self.assertEqual(set(result[1]["predictions"]["support_set"]), {"1F", "5F"})
        self.assertEqual(result[1]["predictions"]["intersection_60s"], ["5F"])

    def test_replay_does_not_read_labels_or_future(self):
        model = FloorModel(ambiguous_training(), FLOORS)
        prefix = [row(1, "A", None, visit="test"), row(2, "B", None, visit="test")]
        expected = replay(model, prefix)
        changed = copy.deepcopy(prefix)
        for r in changed:
            r["floor"] = "arbitrary secret reference"
            r["referenceSource"] = "different"
        changed.append(row(100, "A", "4F", visit="test"))
        self.assertEqual(replay(model, changed)[:2], expected)

    def test_no_method_predicts_without_current_cell(self):
        model = FloorModel([row(1)], FLOORS)
        result = replay(model, [row(1, "A", visit="test"), row(2, None, visit="test")])
        self.assertTrue(result[0]["predictionEligible"])
        self.assertFalse(result[1]["predictionEligible"])
        self.assertTrue(all(not p for p in result[1]["predictions"].values()))
        self.assertNotIn("hold_60s", result[1]["predictions"])

    def test_past_history_survives_failed_probe_but_expires_and_resets(self):
        model = FloorModel(ambiguous_training(), FLOORS)
        rows = [row(1, "A", t=0, visit="test"), row(2, None, t=30000, visit="test"),
                row(3, "B", t=50000, visit="test"), row(4, "B", t=70000, visit="test"),
                row(5, "A", t=71000, visit="test", segment="reset")]
        result = replay(model, rows)
        self.assertEqual(result[2]["predictions"]["intersection_60s"], ["5F"])
        self.assertEqual(set(result[3]["predictions"]["intersection_60s"]), {"1F", "5F"})
        self.assertEqual(set(result[4]["predictions"]["intersection_60s"]), {"4F", "5F"})

    def test_missing_and_unseen_cells_are_not_fresh_evidence(self):
        model = FloorModel([row(1)], FLOORS)
        result = replay(model, [row(1, None, visit="test"), row(2, "new", visit="test")])
        for r in result:
            for method in ["unique_cell", "lookup_balanced", "window_candidates_60s", "intersection_60s", "window_normalized_vote_60s"]:
                self.assertEqual(r["predictions"][method], [])
        self.assertNotIn("5F", model.supported_floors)

    def test_repeats_do_not_strengthen_single_cell_history(self):
        model = FloorModel(ambiguous_training(), FLOORS)
        first, second = row(1, "A", None, visit="test"), row(20, "A", None, visit="test")
        a = history_weights(model, second, [first, second], likelihood=True)
        b = history_weights(model, second, [second], likelihood=True)
        self.assertEqual(a, b)

    def test_shared_fix_and_measurement_are_purged(self):
        train = [row(1), row(2), row(3)]
        test = [row(100, visit="test"), row(101, visit="test")]
        test[0]["measurementId"] = train[0]["measurementId"]
        test[1]["locationFixId"] = train[1]["locationFixId"]
        kept, audit = purge(train, test)
        self.assertEqual(kept, [train[2]])
        self.assertEqual(audit["purgedTrainingRows"], 2)

    def test_two_floor_candidate_is_not_exact_success(self):
        scores = score([row(1, floor="5F")], [["4F", "5F"]], FLOORS)
        self.assertEqual(scores["n_correct"], 0)
        self.assertEqual(scores["candidate_sets"]["n_containing_reference"], 1)
        self.assertEqual(scores["candidate_sets"]["mean_size_among_answers"], 2)

    def test_balancing_can_reverse_the_long_visit_count_bias(self):
        train = [row(i, "A" if i % 2 else "B", "4F", visit="long") for i in range(1, 201)]
        train += [row(201, "A", "4F", visit="short")]
        train += [row(300 + i, "A" if i < 9 else "B", "5F", visit="other") for i in range(10)]
        model = FloorModel(train, FLOORS)
        self.assertEqual(len(model.visits["4F"]), 2)
        prediction = replay(model, [row(1, "A", None, visit="test")])[0]["predictions"]
        self.assertEqual(prediction["lookup_majority"], ["4F"])
        self.assertEqual(prediction["lookup_balanced"], ["5F"])


class ExtensionTest(unittest.TestCase):
    def test_extension_strips_target_features_and_does_not_reset_at_floor_changes(self):
        old = [{**row(1), "recording": "afternoon", "lat": 99, "localCell": "secret", "radioAgeMs": 1234},
               {**row(2, floor="5F"), "recording": "afternoon"}]
        new = [{**row(1, t=100000), "recording": "nov16"}]
        result = combine_rows(old, new, "caller")
        self.assertNotIn("lat", result[0])
        self.assertNotIn("localCell", result[0])
        self.assertNotIn("radioAgeMs", result[0])
        self.assertEqual(result[0]["segment"], result[1]["segment"])
        self.assertNotEqual(result[0]["measurementId"], result[-1]["measurementId"])
        self.assertNotEqual(result[0]["segment"], result[-1]["segment"])


if __name__ == "__main__":
    unittest.main()
