import unittest

from analysis.horizontal_reference_benchmark import evaluate, fit_model, predict_rows, run


def row(time, cell, fix, x=0, y=0, episode="train", segment="one", **extra):
    return {
        "t_ms": time, "cell": cell, "locationFixId": fix,
        "measurementId": f"measurement-{time}", "x_m": x, "y_m": y,
        "positionEligible": True, "episode": episode, "segment": segment,
        "recording": "synthetic", **extra,
    }


class HorizontalReferenceBenchmarkTest(unittest.TestCase):
    def test_fitting_deduplicates_fixes_and_removes_cross_split_shared_fixes(self):
        train = [row(0, "A", "first", x=0)] * 20 + [row(1, "A", "second", x=10), row(2, "A", "shared", x=999)]
        model = fit_model(train, [row(3, "A", "shared", x=999, episode="test")])
        self.assertEqual(model.constant, (5, 0))
        self.assertEqual(list(model.cell_centres.values()), [(5, 0)])
        self.assertEqual(model.statistics["distinctTrainingReferenceFixes"], 2)
        self.assertEqual(model.statistics["sharedFixIdsExcludedFromTraining"], 1)

    def test_history_is_causal_deduplicated_and_resets_at_boundaries(self):
        model = fit_model([row(0, "A", "train-a", x=0), row(1, "B", "train-b", x=100)], [])
        testing = [
            row(0, "A", "t0", episode="test", measurementId="same"),
            row(1000, "A", "t1", episode="test", measurementId="same"),
            row(2000, "B", "t2", episode="test"),
            row(3000, None, "t3", episode="test"),
            row(4000, "unseen", "t4", episode="test", segment="new"),
        ]
        predicted = predict_rows(testing, model)
        self.assertEqual(predicted[0]["predictions"]["history_60s"], (0, 0))
        self.assertEqual(predicted[2]["predictions"]["history_60s"], (50, 0))
        self.assertIsNone(predicted[3]["predictions"]["history_60s"])
        self.assertIsNone(predicted[4]["predictions"]["history_60s"])

    def test_history_window_does_not_retimestamp_cached_measurements(self):
        model = fit_model([row(0, "A", "train-a", x=10)], [])
        testing = [row(0, "A", "t0", episode="test", measurementId="cached"),
                   row(121000, "A", "t1", episode="test", measurementId="cached")]
        prediction = predict_rows(testing, model)[1]["predictions"]
        self.assertEqual(prediction["cell_train_median"], (10, 0))
        self.assertIsNone(prediction["history_120s"])

    def test_comparison_uses_same_rows_and_excludes_unqualified_reference(self):
        model = fit_model([row(0, "A", "train-a", x=0), row(1, "B", "train-b", x=100)], [])
        summary = evaluate([
            row(2, "A", "test-a", x=0, episode="test"),
            row(3, "unseen", "test-b", x=500, episode="test"),
            row(4, None, "test-c", x=600, episode="test"),
            row(5, "A", "test-d", x=9999, episode="test", positionEligible=False),
        ], model)
        self.assertEqual(summary["qualifiedReferenceRows"], 3)
        self.assertEqual(summary["qualifiedCurrentNullRows"], 1)
        self.assertEqual(summary["qualifiedUnseenCurrentCellRows"], 1)
        pair = summary["sameRowComparisons"]["cell_train_median_vs_constant_train_median"]
        self.assertEqual(pair["matchedRows"], 1)
        self.assertEqual(pair["modelDiscrepancy"]["medianM"], 0)
        self.assertEqual(pair["baselineDiscrepancy"]["medianM"], 50)

    def test_test_coordinates_do_not_change_predictions(self):
        model = fit_model([row(0, "A", "train-a", x=10)], [])
        a = predict_rows([row(1, "A", "test", x=0, episode="test")], model)
        b = predict_rows([row(1, "A", "test", x=100000, episode="test")], model)
        self.assertEqual(a[0]["predictions"], b[0]["predictions"])

    def test_recording_boundary_resets_history_and_rejects_episode_leakage(self):
        model = fit_model([row(0, "A", "train-a", x=10)], [])
        prediction = predict_rows([
            row(1, "A", "t1", episode="test", recording="first"),
            row(2, "unseen", "t2", episode="test", recording="second"),
        ], model)
        self.assertIsNone(prediction[1]["predictions"]["history_120s"])
        with self.assertRaises(ValueError):
            run({"streams": [], "splits": [{"id": "bad", "trainEpisodes": ["same"], "testEpisodes": ["same"]}]})


if __name__ == "__main__":
    unittest.main()
