import json
import tempfile
import unittest
from pathlib import Path

from analysis.compare_sip_observability_days import jaccard
from analysis.sip_observability import cliffs_delta, holm_adjust, parse_config, wilson_interval


class StatisticsTest(unittest.TestCase):
    def test_cliffs_delta_orders_groups(self) -> None:
        self.assertEqual(cliffs_delta([1, 2, 3], [4, 5, 6]), -1.0)
        self.assertEqual(cliffs_delta([4, 5, 6], [1, 2, 3]), 1.0)

    def test_holm_adjust_is_monotonic_in_rank_order(self) -> None:
        adjusted = holm_adjust([0.04, 0.001, 0.02])
        self.assertAlmostEqual(adjusted[1], 0.003)
        self.assertAlmostEqual(adjusted[2], 0.04)
        self.assertAlmostEqual(adjusted[0], 0.04)

    def test_wilson_interval_contains_observed_rate(self) -> None:
        low, high = wilson_interval(21, 30)
        self.assertLess(low, 0.7)
        self.assertGreater(high, 0.7)

    def test_wilson_interval_handles_empty_input(self) -> None:
        self.assertEqual(wilson_interval(0, 0), (0.0, 0.0))

    def test_ground_truth_condition_label_can_differ_from_caller_label(self) -> None:
        config = {
            "blocks": [
                {
                    "label": label,
                    "conditionLabel": f"caller_{label}",
                    "groundTruthConditionLabel": f"target_{label}",
                    "callerSessionId": f"session_{label}",
                    "groundTruthFile": f"truth_{label}.json",
                    "networkName": label,
                    "primaryWifiFrequencyMhz": 5000,
                }
                for label in ("A1", "B", "A2")
            ]
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text(json.dumps(config), encoding="utf-8")
            _, blocks = parse_config(path)

        self.assertEqual(blocks[1].condition_label, "caller_B")
        self.assertEqual(blocks[1].ground_truth_condition_label, "target_B")

    def test_jaccard_handles_overlap_and_empty_sets(self) -> None:
        self.assertEqual(jaccard({"a", "b"}, {"b", "c"}), 1 / 3)
        self.assertEqual(jaccard(set(), set()), 1.0)


if __name__ == "__main__":
    unittest.main()
