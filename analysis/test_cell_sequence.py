import unittest

from analysis.cell_sequence import Cell, enodeb_group, header_cells, parse_cell_header, persistent_change_times, persistent_changes, transitions
from scripts.evaluate_geolocation_combinations import Tower, build_request_payload


class CellSequenceTest(unittest.TestCase):
    def test_parses_two_and_three_digit_mnc_and_tac_widths(self) -> None:
        lte = parse_cell_header("3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=0010100010000001")
        self.assertEqual(lte, Cell("lte", 1, 1, 1, 1))
        self.assertEqual(
            parse_cell_header("3GPP-E-UTRAN-TDD;utran-cell-id-3gpp=31026012340123456"),
            Cell("lte", 310, 260, 0x1234, 0x0123456),
        )
        self.assertEqual(
            parse_cell_header("3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=001010000010000001"),
            Cell("lte", 1, 1, 1, 1),
        )

    def test_parses_nr_without_truncating_identity(self) -> None:
        self.assertEqual(
            parse_cell_header("3GPP-NR-FDD;utran-cell-id-3gpp=00101000001000000001"),
            Cell("nr", 1, 1, 1, 1),
        )

    def test_does_not_take_cell_from_another_header(self) -> None:
        present, cell, cni, pani = header_cells([{
            "headers": [
                {"name": "Cellular-Network-Info", "value": "3GPP-E-UTRAN-FDD"},
                {"name": "X-Other", "value": "utran-cell-id-3gpp=0010100010000001"},
            ]
        }])
        self.assertTrue(present)
        self.assertIsNone(cell)
        self.assertEqual((cni, pani), (1, 0))

    def test_stationary_ping_pong_is_counted(self) -> None:
        a = Cell("lte", 1, 1, 1, 101)
        b = Cell("lte", 1, 1, 1, 102)
        self.assertEqual(transitions([a, b, a, None, b]), (3, 3, 2))
        self.assertEqual(persistent_changes([a, a, a, b, a, b, a]), 0)
        self.assertEqual(persistent_changes([a, a, a, b, b, b, a, a, a]), 2)
        self.assertEqual(
            persistent_change_times([a, a, a, b, b, b], [0, 10, 20, 30, 40, 50]),
            [50],
        )

    def test_lte_sectors_group_to_one_enodeb(self) -> None:
        a = Cell("lte", 1, 1, 1, (12345 << 8) | 1)
        b = Cell("lte", 1, 1, 2, (12345 << 8) | 2)
        self.assertEqual(enodeb_group(a), enodeb_group(b))
        self.assertEqual(transitions([enodeb_group(a), enodeb_group(b)]), (0, 1, 0))

    def test_google_payload_has_request_level_radio_type(self) -> None:
        payload = build_request_payload([Tower(1, 1, 1, 12345678)])
        self.assertEqual(payload["radioType"], "lte")
        self.assertFalse(payload["considerIp"])
        self.assertNotIn("radioType", payload["cellTowers"][0])
        with self.assertRaises(ValueError):
            build_request_payload([Tower(1, 1, 1, 1, "lte"), Tower(1, 1, 1, 2, "wcdma")])


if __name__ == "__main__":
    unittest.main()
