"""Checks for reference provenance and causal telemetry timing, without phone data."""
import unittest

from analysis.prepare_floor_batch import Reference, radio_acquired_at, ts


def fix(t, height, age=100):
    return {
        "recordedAtMillis": t, "capturedElapsedRealtimeNanos": t * 1000000,
        "locationElapsedRealtimeNanos": (t - age) * 1000000,
        "locationFixTimeMillis": t - age, "locationAgeMillis": age,
        "altitudeM": height, "isMockLocation": False,
    }


class ReferenceTests(unittest.TestCase):
    def test_confirmed_evening_floor_does_not_require_a_gps_fix(self):
        self.assertEqual(Reference([]).label("evening", None, ts("19:15:00")), ("4F", "user_reported_whole_visit", "accepted"))

    def test_old_fix_cannot_label_a_current_afternoon_sample(self):
        row = fix(ts("15:45:00"), 36.8, age=11000)
        self.assertIsNone(Reference([row]).label("afternoon", row, row["recordedAtMillis"])[0])

    def test_guard_excludes_both_sides_of_a_height_change(self):
        t = ts("15:49:00")
        rows = [fix(t - 20000, 36.8), fix(t - 1000, 36.8), fix(t + 1000, 42.3), fix(t + 20000, 42.3)]
        reference = Reference(rows)
        self.assertEqual(reference.label("afternoon", rows[0], rows[0]["recordedAtMillis"])[0], "B1")
        self.assertIsNone(reference.label("afternoon", rows[1], rows[1]["recordedAtMillis"])[0])
        self.assertIsNone(reference.label("afternoon", rows[2], rows[2]["recordedAtMillis"])[0])
        self.assertEqual(reference.label("afternoon", rows[3], rows[3]["recordedAtMillis"])[0], "1F")

    def test_intermediate_height_is_not_forced_to_a_floor(self):
        row = fix(ts("15:50:00"), 45.5)
        self.assertIsNone(Reference([row]).label("afternoon", row, row["recordedAtMillis"])[0])

    def test_radio_acquisition_can_postdate_sampling_tick(self):
        row = fix(ts("19:20:00"), 57.3)
        row["radio"] = {"capturedElapsedRealtimeNanos": row["capturedElapsedRealtimeNanos"] + 585000000}
        self.assertEqual(radio_acquired_at(row), row["recordedAtMillis"] + 585)


if __name__ == "__main__":
    unittest.main()
