"""Unit tests for the pure functions in ui.py. Run: python tools/test_ui.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402

SAMPLE = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="Fingerprint unlock" resource-id="id/title" clickable="false" enabled="true" bounds="[100,200][900,300]" />
    <node index="1" text="I agree" resource-id="id/next" content-desc="" clickable="true" enabled="true" bounds="[600,2100][1000,2250]" />
    <node index="2" text="Cancel" resource-id="id/cancel" clickable="true" enabled="false" bounds="[80,2100][400,2250]" />
    <node index="3" text="" content-desc="Touch the sensor" clickable="false" enabled="true" bounds="[0,0][10,10]" />
  </node>
</hierarchy>
UI hierchary dumped to: /dev/tty"""


class ParseTests(unittest.TestCase):
    def test_parses_all_nodes_and_ignores_trailing_text(self):
        nodes = ui.parse_nodes(SAMPLE)
        self.assertEqual(len(nodes), 5)
        self.assertEqual(nodes[1]["text"], "Fingerprint unlock")
        self.assertEqual(nodes[1]["bounds"], (100, 200, 900, 300))

    def test_garbage_gives_empty_list(self):
        self.assertEqual(ui.parse_nodes("not xml at all"), [])
        self.assertEqual(ui.parse_nodes(""), [])
        self.assertEqual(ui.parse_nodes("<?xml version='1.0'?><hierarchy><node"), [])

    def test_center(self):
        self.assertEqual(ui.center((600, 2100, 1000, 2250)), (800, 2175))


class FindTests(unittest.TestCase):
    nodes = ui.parse_nodes(SAMPLE)

    def test_find_by_text_case_insensitive(self):
        self.assertEqual(ui.find(self.nodes, "i agree")["rid"], "id/next")

    def test_find_by_content_description(self):
        self.assertIsNotNone(ui.find(self.nodes, "touch the sensor"))

    def test_disabled_nodes_are_skipped(self):
        self.assertIsNone(ui.find(self.nodes, "^Cancel$"))

    def test_clickable_only(self):
        self.assertIsNone(ui.find(self.nodes, "Fingerprint unlock", clickable_only=True))
        self.assertIsNotNone(ui.find(self.nodes, "I agree", clickable_only=True))

    def test_any_text(self):
        self.assertTrue(ui.any_text(self.nodes, "fingerprint"))
        self.assertFalse(ui.any_text(self.nodes, "banana"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
