import unittest

from analysis.floor_history_benchmark import deduplicate_training, fit, history_contexts, run


FLOORS = ["B1", "1F", "2F"]


def observation(t, cell, floor, episode="train", segment="one", measurement=None):
    return {
        "t_ms": t,
        "cell": cell,
        "floor": floor,
        "episode": episode,
        "segment": segment,
        "measurementId": measurement,
        "locationFixId": f"fix-{episode}-{t}",
        "referenceSource": "synthetic_anchor",
        "outcome": "cell_observed" if cell is not None else "no_provisional",
    }


def prepared(rows, **extra):
    return {
        "schemaVersion": 1,
        "floors": FLOORS,
        "streams": [{"id": "caller", "side": "caller", "rows": rows}],
        "splits": [{"id": "held_out", "trainEpisodes": ["train"], "testEpisodes": ["test"]}],
        **extra,
    }


class FloorHistoryBenchmarkTest(unittest.TestCase):
    def test_conflicting_measurement_is_excluded_and_missing_ids_are_not_collapsed(self):
        rows = [
            observation(0, "a", "B1", measurement="conflict"),
            observation(1, "a", "1F", measurement="conflict"),
            observation(2, "b", "B1", measurement="same"),
            observation(3, "b", "B1", measurement="same"),
            observation(4, "c", "1F"),
            observation(5, "c", "1F"),
        ]
        retained, audit = deduplicate_training(rows)
        self.assertEqual(retained, [2, 4, 5])
        self.assertEqual(audit["excluded_floor_conflict_ids"], 1)
        self.assertNotIn("a", fit(rows, FLOORS).cell_counts)

    def test_history_resets_and_cannot_see_future_observations(self):
        rows = [
            observation(0, "a", None, episode="test"),
            observation(10, "b", None, episode="test", segment="two"),
            observation(20, "c", None, episode="other", segment="two"),
            observation(30, "d", None, episode="test", segment="two"),
        ]
        contexts = history_contexts(rows, 60_000)
        self.assertEqual([p["cell"] for p in contexts[1].points], ["b"])
        self.assertEqual([p["cell"] for p in contexts[2].points], ["c"])
        self.assertEqual([p["cell"] for p in contexts[3].points], ["b", "d"])
        self.assertEqual(contexts[3].elapsed_ms, 20)

    def test_history_counts_measurement_once_even_when_reexported(self):
        rows = [
            observation(0, "a", None, measurement="one"),
            observation(10, "a", None, measurement="one"),
            observation(20, "b", None, measurement="two"),
        ]
        context = history_contexts(rows, 60_000)[-1]
        self.assertEqual(len(context.points), 2)
        self.assertEqual(context.points[0]["t_ms"], 10)

    def test_order_disambiguates_direction_and_backoff_respects_reset(self):
        train = [
            observation(0, "a", "B1", measurement="a"),
            observation(10_000, "b", "1F", measurement="b1"),
            observation(20_000, "c", "2F", measurement="c"),
            observation(30_000, "b", "2F", measurement="b2"),
        ]
        model = fit(train, FLOORS)
        test = [observation(0, "a", "2F", episode="test"), observation(10_000, "b", "B1", episode="test")]
        contexts = {w: history_contexts(test, w)[-1] for w in (60_000, 120_000)}
        predictions = model.predict(test[-1], contexts)
        self.assertEqual(predictions["single_cell_lookup"], "2F")
        self.assertEqual(predictions["ordered_transition_60s"], "1F")
        test[-1]["segment"] = "reset"
        contexts = {w: history_contexts(test, w)[-1] for w in (60_000, 120_000)}
        self.assertEqual(model.predict(test[-1], contexts)["ordered_transition_60s"], "2F")

    def test_null_failures_and_unseen_floor_remain_in_overall_denominator(self):
        rows = [
            observation(0, "a", "B1", measurement="train"),
            observation(100, "a", "B1", episode="test"),
            observation(200, "a", "1F", episode="test"),
            observation(300, None, "B1", episode="test"),
        ]
        result = run(prepared(rows))["results"][0]
        for name, method in result["methods"].items():
            if name in ("majority_all_opportunities", "elapsed_time_prior"):
                self.assertAlmostEqual(method["accuracy_overall"], 2 / 3)
                self.assertEqual(method["coverage"], 1)
                continue
            self.assertAlmostEqual(method["accuracy_overall"], 1 / 3)
            self.assertAlmostEqual(method["balanced_accuracy_overall"], 0.25)
            self.assertAlmostEqual(method["accuracy_conditional"], 0.5)
            self.assertEqual(method["n_abstained"], 1)
            self.assertEqual(method["unseen_test_classes"]["n_correct"], 0)
        self.assertEqual(result["test"]["unseen_test_class_floors"], ["1F"])

    def test_matched_full_history_comparison_uses_exact_same_covered_rows(self):
        rows = [
            observation(0, "a", "B1", measurement="train"),
            observation(0, "a", "B1", episode="test"),
            observation(60_000, "a", "B1", episode="test"),
            observation(120_000, "unseen", "1F", episode="test"),
            observation(180_000, None, "B1", episode="test"),
        ]
        result = run(prepared(rows))["results"][0]
        full = next(x for x in result["matched_coverage_comparisons"] if x["lookback_seconds"] == 60 and x["history_state"] == "full_window")
        self.assertEqual(full["n_reference_eligible_same_history_state"], 3)
        self.assertEqual(full["n_all_methods_predicted_same_rows"], 1)
        self.assertTrue(all(score["n_reference_eligible"] == 1 for score in full["scores"].values()))

    def test_explicit_transfer_selects_only_named_streams(self):
        local = {"id": "local", "side": "local", "rows": [observation(0, "a", "1F")]}
        caller = {"id": "caller", "side": "caller", "rows": [observation(100, "a", "1F", episode="test")]}
        data = prepared([], streams=[local, caller], splits=[{
            "id": "transfer", "trainStreamId": "local", "testStreamId": "caller",
            "trainEpisodes": ["train"], "testEpisodes": ["test"],
        }])
        results = run(data)["results"]
        self.assertEqual(len(results), 1)
        self.assertTrue(results[0]["transfer"])
        self.assertEqual(results[0]["methods"]["single_cell_lookup"]["n_correct"], 1)

    def test_same_stream_train_test_overlap_is_rejected(self):
        data = prepared([observation(0, "a", "B1")])
        data["splits"][0]["testEpisodes"] = ["train"]
        with self.assertRaisesRegex(ValueError, "overlaps"):
            run(data)

    def test_shared_measurement_or_fix_is_purged_from_training(self):
        train_a = observation(0, "a", "B1", measurement="shared-measurement")
        train_b = observation(1, "b", "1F", measurement="unique-measurement")
        train_b["locationFixId"] = "shared-fix"
        train_c = observation(2, "c", "2F", measurement="uncontaminated")
        test_a = observation(100, "a", "B1", episode="test", measurement="shared-measurement")
        test_b = observation(101, "b", "1F", episode="test", measurement="different")
        test_b["locationFixId"] = "shared-fix"
        result = run(prepared([train_a, train_b, train_c, test_a, test_b]))["results"][0]
        purge = result["training"]["identity_overlap_purge"]
        self.assertEqual(purge["purged_training_rows"], 2)
        self.assertEqual(purge["shared_measurement_ids_before_purge"], 1)
        self.assertEqual(purge["shared_location_fix_ids_before_purge"], 1)
        self.assertEqual(result["test"]["measurement_ids_also_present_in_training"], 0)
        self.assertEqual(result["test"]["location_fix_ids_also_present_in_training"], 0)
        self.assertEqual(result["methods"]["single_cell_lookup"]["n_abstained"], 2)

    def test_elapsed_prior_uses_known_episode_start_not_segment_or_future_duration(self):
        train = [
            observation(0, "a", "B1", measurement="a"),
            observation(10_000, "a", "B1", measurement="b"),
            observation(20_000, "a", "B1", measurement="c"),
            observation(60_000, "b", "1F", measurement="d"),
        ]
        test = [
            observation(100_000, None, "B1", episode="test"),
            observation(170_000, None, "1F", episode="test", segment="reset"),
        ]
        first = []
        run(prepared(train + test), first)
        self.assertEqual(first[1]["predictions"]["elapsed_time_prior"], "1F")
        self.assertEqual(first[1]["predictions"]["majority_all_opportunities"], "B1")
        self.assertEqual(first[1]["knownEpisodeElapsedMs"], 70_000)
        later = observation(10_000_000, None, "2F", episode="test", segment="another")
        extended = []
        run(prepared(train + test + [later]), extended)
        self.assertEqual([x["predictions"] for x in first], [x["predictions"] for x in extended[:2]])


if __name__ == "__main__":
    unittest.main()
