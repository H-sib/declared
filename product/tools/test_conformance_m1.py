"""Unit tests for the pure helpers of conformance_m1.py. The samples are real output captured from the API 34 emulator."""
import copy
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m1 as c  # noqa: E402

XMLTREE = """N: android=http://schemas.android.com/apk/res/android (line=8)
  E: accessibility-service (line=8)
    A: http://schemas.android.com/apk/res/android:description(0x01010020)=@0x7f010000
    A: http://schemas.android.com/apk/res/android:accessibilityEventTypes(0x01010380)=0xffffffff
    A: http://schemas.android.com/apk/res/android:canRetrieveWindowContent(0x01010385)=true
    A: http://schemas.android.com/apk/res/android:canPerformGestures(0x0101050d)=true
"""

QUERY_SERVICES = """4 services found:
  Service #0:
    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
    com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService
  Service #3:
    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
    lab.fixture.a11y/lab.fixture.FixtureA11yService
"""

DUMPSYS = """    installerPackageName=null
    installerPackageUid=-1
    initiatingPackageName=com.android.shell
    originatingPackageName=null
"""


def good_dump(enabled=True):
    comp = "lab.fixture.a11y/lab.fixture.FixtureA11yService"
    other = "com.google.android.marvin.talkback/.TalkBackService"
    installed = [{"component": comp, "capabilityBits": 33, "declaresAccessibilityTool": None},
                 {"component": "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService", "capabilityBits": 123, "declaresAccessibilityTool": True}]
    del other
    dump = {
        "signals": [{"id": "special-access.accessibility.enabled", "availability": "AVAILABLE", "reason": ""}],
        "holders": [
            {"component": comp, "label": "Fixture a11y", "enabled": enabled, "tier": "ADB", "tierConfidence": "MEDIUM",
             "abilities": ["window-content", "gestures"], "unrecognizedBits": 0, "serviceLabel": "Fixture accessibility"},
            {"component": installed[1]["component"], "label": "TalkBack", "enabled": False, "tier": "SYSTEM", "tierConfidence": "HIGH",
             "abilities": ["window-content"], "unrecognizedBits": 0, "serviceLabel": "TalkBack"},
        ],
        "findings": [],
        "raw": {"accessibility": {
            "installed": installed,
            "enabledByManager": [comp] if enabled else [],
            "enabledBySetting": [comp] if enabled else [],
        }, "packages": [{"pkg": "lab.fixture.a11y", "label": "Fixture a11y", "system": False, "installing": None,
                          "initiating": "com.android.shell", "signerSha256": ["ab" * 32], "installRecordReadable": True}]},
    }
    if enabled:
        dump["findings"].append({
            "id": "accessibility:" + comp, "attention": "LOOK_FIRST", "confidence": "HIGH",
            "claim": [
                {"id": "special-access.accessibility.enabled", "provenance": "OBSERVED", "confidence": "HIGH", "value": True, "source": "x"},
                {"id": "special-access.accessibility.capabilities", "provenance": "DECLARED", "confidence": "HIGH", "value": ["window-content", "gestures"], "source": "y"},
            ],
            "context": [{"id": "app.install-origin", "provenance": "INFERRED", "confidence": "MEDIUM", "value": "ADB: x", "source": "z"}],
            "cannotTellCount": 4, "actionCount": 2,
        })
    return dump


def good_truth(enabled=True):
    comp = "lab.fixture.a11y/lab.fixture.FixtureA11yService"
    tb = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
    return {"enabled": {comp} if enabled else set(), "installed": {comp, tb}, "capability_bits": 33,
            "label": "Fixture a11y", "service_label": "Fixture accessibility", "signers": ["ab" * 32], "installer": None, "initiating": "com.android.shell", "system": False}


class Helpers(unittest.TestCase):
    def test_declared_bits_from_real_xmltree(self):
        self.assertEqual(33, c.declared_capability_bits(XMLTREE))

    def test_false_attribute_adds_nothing(self):
        self.assertEqual(0, c.declared_capability_bits("A: x:canRetrieveWindowContent(0x1)=false"))

    def test_ability_keys(self):
        self.assertEqual(["window-content", "gestures"], c.ability_keys(33))
        self.assertEqual([], c.ability_keys(0x4))  # the deprecated bit has no name, by design

    def test_label_and_package(self):
        badging = "package: name='lab.fixture.a11y' versionCode='1'\napplication-label:'Fixture a11y'\n"
        self.assertEqual("Fixture a11y", c.parse_label(badging))
        self.assertEqual("lab.fixture.a11y", c.parse_package_name(badging))

    def test_label_from_the_application_line_when_there_is_no_label_line(self):
        text = "application: label='Fixture notif' icon=''" + chr(10) + "application-debuggable" + chr(10)
        self.assertEqual("Fixture notif", c.parse_label(text))

    def test_signer_digests_sorted_and_lowercased(self):
        out = "Signer #1 certificate SHA-256 digest: " + "AB" * 32 + "\n"
        self.assertEqual(["ab" * 32], c.parse_signer_digests(out))

    def test_install_source_from_real_dumpsys(self):
        self.assertEqual((None, "com.android.shell"), c.parse_install_source(DUMPSYS))

    def test_installed_services_normalised(self):
        s = c.parse_installed_services(QUERY_SERVICES)
        self.assertEqual({"com.android.systemui.accessibility.accessibilitymenu/com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService",
                          "lab.fixture.a11y/lab.fixture.FixtureA11yService"}, s)

    def test_service_label_from_manifest_xmltree(self):
        tree = ('''      E: service (line=5)
        A: http://schemas.android.com/apk/res/android:label(0x01010001)="Other service" (Raw: "Other service")
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="x.Other" (Raw: "x.Other")
      E: service (line=12)
        A: http://schemas.android.com/apk/res/android:label(0x01010001)="Fixture accessibility" (Raw: "Fixture accessibility")
        A: http://schemas.android.com/apk/res/android:name(0x01010003)=".FixtureA11yService" (Raw: ".FixtureA11yService")
        E: intent-filter (line=15)
''')
        self.assertEqual("Fixture accessibility", c.parse_service_label(tree, "FixtureA11yService"))
        self.assertIsNone(c.parse_service_label(tree, "Missing"))

    def test_permissions(self):
        self.assertEqual(["a.b.C"], c.parse_permissions("package: x\nuses-permission: name='a.b.C'\n"))

    def test_expected_tier(self):
        self.assertEqual("ADB", c.expected_tier(None, "com.android.shell"))
        self.assertEqual("UNKNOWN", c.expected_tier(None, None))
        self.assertIsNone(c.expected_tier("com.android.vending", "com.android.vending"))


class Compare(unittest.TestCase):
    def failures(self, dump, truth):
        return [x.name for x in c.compare(dump, truth) if not x.ok]

    def test_a_faithful_report_passes_everything_enabled(self):
        self.assertEqual([], self.failures(good_dump(True), good_truth(True)))

    def test_a_faithful_report_passes_everything_disabled(self):
        self.assertEqual([], self.failures(good_dump(False), good_truth(False)))

    def test_a_missed_enabled_service_is_caught(self):
        d = good_dump(True)
        d["raw"]["accessibility"]["enabledByManager"] = []
        d["holders"][0]["enabled"] = False
        d["findings"] = []
        self.assertTrue(self.failures(d, good_truth(True)))

    def test_an_invented_finding_is_caught(self):
        d = good_dump(False)
        d["findings"].append({"id": "accessibility:made/up", "attention": "NOTE", "confidence": "HIGH", "claim": [], "context": [], "cannotTellCount": 3, "actionCount": 1})
        self.assertIn("findings = enabled and installed services", self.failures(d, good_truth(False)))

    def test_a_wrong_signer_is_caught(self):
        d = good_dump(True)
        d["raw"]["packages"][0]["signerSha256"] = ["cd" * 32]
        self.assertIn("signer SHA-256 = apksigner", self.failures(d, good_truth(True)))

    def test_a_wrong_tier_is_caught(self):
        d = good_dump(True)
        d["holders"][0]["tier"] = "STORE"
        self.assertIn("tier follows the documented rule", self.failures(d, good_truth(True)))

    def test_a_dropped_ability_is_caught(self):
        d = good_dump(True)
        d["raw"]["accessibility"]["installed"][0]["capabilityBits"] = 1
        self.assertIn("declared ability bits = XML resource", self.failures(d, good_truth(True)))

    def test_a_refused_reading_is_reported_not_crashed(self):
        d = copy.deepcopy(good_dump(True))
        d["raw"]["accessibility"] = "Refused(reason=nope)"
        self.assertEqual(["raw accessibility reading present"], self.failures(d, good_truth(True)))


if __name__ == "__main__":
    unittest.main()
