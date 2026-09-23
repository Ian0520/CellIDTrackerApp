import unittest

from analysis.cell_location_benchmark import PairedPoint, fit_areas, run, score, score_google


class CellLocationBenchmarkTest(unittest.TestCase):
    def test_empirical_area_scores_known_cell_and_abstains_on_unseen_cell(self) -> None:
        cell = ("lte", 1, 1, 100, 1000)
        unknown = ("lte", 1, 1, 100, 2000)
        training = [PairedPoint(cell, 24.0 + i * 0.0001, 121.0, None, None, None) for i in range(6)]
        testing = [
            PairedPoint(cell, 24.0002, 121.0, 24.001, 121.0, 100.0),
            PairedPoint(unknown, 24.005, 121.0, None, None, None),
        ]
        areas = fit_areas(training, "cell", min_points=5, radius_quantile=0.9)
        summary, errors = score(testing, areas, "cell")
        google, _ = score_google(testing)
        self.assertEqual(summary["predicted_points"], 1)
        self.assertEqual(summary["abstained_points"], 1)
        self.assertEqual(google["predicted_points"], 1)
        self.assertLess(summary["median_error_m"], google["median_error_m"])

    def test_training_and_test_days_must_be_disjoint(self) -> None:
        session = {"callerSessionId": "one", "day": "day1"}
        with self.assertRaises(ValueError):
            run({"trainSessions": [session], "testSessions": [session]}, None)


if __name__ == "__main__":
    unittest.main()
