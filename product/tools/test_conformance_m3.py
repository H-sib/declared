"""Unit tests for the pure helpers and the comparator of conformance_m3.py. Samples are real output from the API 34 emulator."""
import copy
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m3 as c  # noqa: E402

WIFI_STATUS = ('Wifi is enabled\nWifi scanning is always available\n==== Primary ClientModeManager instance ====\n'
               'Wifi is connected to "AndroidWifi"\nWifiInfo: SSID: "AndroidWifi", BSSID: 02:15:b2:00:00:01, IP: /10.0.2.16, '
               'Security type: 0, Supplicant state: COMPLETED, Wi-Fi standard: 1\n')

CONNECTIVITY = (
    "Active default network: 100\n"
    "  NetworkAgentInfo{network{100}  handle{1} ni{WIFI CONNECTED extra: } Score(Policies : TRANSPORT_PRIMARY&EVER_VALIDATED&IS_VALIDATED ; "
    "KeepConnected : 0)  lp{{InterfaceName: wlan0 LinkAddresses: [ 10.0.2.16/24 ] DnsAddresses: [ /10.0.2.3 ] Domains: null MTU: 0 "
    "UsePrivateDns: true PrivateDnsServerName: dns.google ValidatedPrivateDnsAddresses: [8.8.4.4] Domains: null Routes: [ ]}}  nc{[ Transports: WIFI Capabilities: NOT_METERED&INTERNET&"
    "NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED&NOT_ROAMING LinkUpBandwidth>=12000Kbps]}  factorySerialNumber=5}\n"
    "  NetworkAgentInfo{network{101}  handle{2} ni{MOBILE[HSPA] CONNECTED} lp{{InterfaceName: eth0}}  nc{[ Transports: CELLULAR "
    "Capabilities: INTERNET&NOT_VPN&VALIDATED]}  factorySerialNumber=3}\n"
)


def truth(**over):
    t = {
        "release": "14", "patch": "2023-09-05", "device_date": "2026-10-09", "lock_set": False, "usb": 1, "wireless": 0, "developer": None,
        "proxy": None, "wifi_connected": True, "wifi_security": 0,
        "network": {"present": True, "transports": ["wifi"], "validated": True, "captive_portal": False, "internet": True,
                    "private_dns_active": False, "private_dns_name": None},
    }
    t.update(over)
    return t


def finding(fid, attention):
    return {"id": fid, "attention": attention, "confidence": "HIGH", "cannotTellCount": 3, "actionCount": 1,
            "claim": [{"id": "x", "source": "api"}], "context": []}


def good_dump():
    return {
        "raw": {
            "device": {"release": "14", "sdk": 34, "securityPatch": "2023-09-05", "screenLock": False, "usbDebugging": 1,
                       "wirelessDebugging": 0, "developerOptions": None, "advancedProtection": "Unsupported"},
            "network": {"present": True, "transports": ["WIFI"], "internet": True, "validated": True, "captivePortal": False,
                        "privateDnsActive": False, "privateDnsServerName": None, "httpProxy": None},
            "wifi": {"connected": True, "security": "OPEN", "note": None},
        },
        "findings": [finding("condition:patch-level", "LOOK_FIRST"), finding("condition:screen-lock", "LOOK_FIRST"),
                     finding("condition:usb-debugging", "LOOK"), finding("condition:wifi-security", "LOOK")],
        "facts": [{"kind": k, "summary": "x", "availability": "AVAILABLE"} for k in c.FACT_KINDS],
        "signals": [{"id": i, "availability": "AVAILABLE"} for i in (
            "device.patch-level", "device.screen-lock", "device.usb-debugging", "device.wireless-debugging", "device.developer-options",
            "network.active", "network.private-dns", "network.http-proxy", "network.user-cas", "network.wifi-security")]
        + [{"id": "device.advanced-protection", "availability": "UNAVAILABLE"}],
    }


class Helpers(unittest.TestCase):
    def test_wifi_status_from_real_output(self):
        self.assertEqual((True, 0), c.parse_wifi_status(WIFI_STATUS))
        self.assertEqual((False, None), c.parse_wifi_status("Wifi is disabled\n"))

    def test_active_network_picks_the_default_network_block(self):
        n = c.parse_active_network(CONNECTIVITY)
        self.assertEqual(["wifi"], n["transports"])
        self.assertTrue(n["validated"] and n["internet"] and not n["captive_portal"])
        self.assertTrue(n["private_dns_active"])
        self.assertEqual("dns.google", n["private_dns_name"])

    def test_no_default_network(self):
        self.assertEqual({"present": False}, c.parse_active_network("Active default network: none\n"))

    def test_settings_parsing(self):
        self.assertIsNone(c.parse_global("null\n"))
        self.assertIsNone(c.parse_int_setting(""))
        self.assertEqual(1, c.parse_int_setting("1\n"))
        self.assertEqual(0, c.parse_int_setting("0"))

    def test_proxy_comes_from_the_real_keys_not_the_deprecated_setting(self):
        # Real state seen after `settings delete global http_proxy`: the effective proxy was still in force.
        eol = chr(10)
        self.assertEqual('10.0.2.2:8888', c.parse_proxy('10.0.2.2' + eol, '8888' + eol, eol, 'null' + eol))
        self.assertIsNone(c.parse_proxy(eol, eol, eol, 'null' + eol))
        self.assertIsNone(c.parse_proxy('null', 'null', 'null', ':0'))
        self.assertEqual('script http://x/p.pac', c.parse_proxy('', '', 'http://x/p.pac', 'null'))
        self.assertEqual('1.2.3.4:80', c.parse_proxy('', '', '', '1.2.3.4:80'))

    def test_lock_rule(self):
        self.assertFalse(c.lock_set_from_get_disabled("true\n"))
        self.assertTrue(c.lock_set_from_get_disabled("false\n"))
        self.assertTrue(c.lock_set_from_get_disabled("NOTE: when lock screen is set, all commands require the --old argument"))

    def test_patch_thresholds_at_their_boundaries(self):
        day = lambda n: str(__import__("datetime").date(2026, 10, 9) - __import__("datetime").timedelta(days=n))  # noqa: E731
        self.assertIsNone(c.expected_patch_attention(day(179), "2026-10-09"))
        self.assertEqual("LOOK", c.expected_patch_attention(day(180), "2026-10-09"))
        self.assertEqual("LOOK", c.expected_patch_attention(day(364), "2026-10-09"))
        self.assertEqual("LOOK_FIRST", c.expected_patch_attention(day(365), "2026-10-09"))
        self.assertIsNone(c.expected_patch_attention("2027-01-01", "2026-10-09"))
        self.assertIsNone(c.expected_patch_attention("garbage", "2026-10-09"))

    def test_expected_conditions_for_the_emulator_as_found(self):
        self.assertEqual({"condition:patch-level": "LOOK_FIRST", "condition:screen-lock": "LOOK_FIRST",
                          "condition:usb-debugging": "LOOK", "condition:wifi-security": "LOOK"}, c.expected_conditions(truth(), "34"))

    def test_expected_conditions_for_the_changed_state(self):
        t = truth(lock_set=True, developer=1, proxy="10.0.2.2:8888")
        self.assertEqual({"condition:patch-level": "LOOK_FIRST", "condition:usb-debugging": "LOOK", "condition:developer-options": "NOTE",
                          "condition:wifi-security": "LOOK", "condition:http-proxy": "LOOK"}, c.expected_conditions(t, "34"))

    def test_wifi_finding_needs_android_12(self):
        self.assertNotIn("condition:wifi-security", c.expected_conditions(truth(), "30"))


class UserCa(unittest.TestCase):
    FP = "9a8aee12c24d985388f0f11e1f6a4f1c49d177257674d59ff7af1bf1c61f7625"

    def dump(self, present):
        return {
            "raw": {"userCas": {"sha256": [self.FP] if present else []}},
            "findings": ([{"id": "condition:user-cas", "attention": "LOOK", "confidence": "MEDIUM"}] if present else []),
            "facts": [{"kind": "USER_CAS", "summary": ("Added certificate authorities: 1 found (medium confidence: checked on one emulator only)." if present
                                                     else "Added certificate authorities: none found (medium confidence: checked on one emulator only).")}],
        }

    def failures(self, dump, present):
        return [x.name for x in c.compare_ca(dump, self.FP, present) if not x.ok]

    def test_present_and_absent_pass(self):
        self.assertEqual([], self.failures(self.dump(True), True))
        self.assertEqual([], self.failures(self.dump(False), False))

    def test_a_missed_certificate_is_caught(self):
        self.assertTrue(self.failures(self.dump(False), True))

    def test_a_ghost_certificate_after_removal_is_caught(self):
        self.assertTrue(self.failures(self.dump(True), False))

    def test_a_wrong_fingerprint_or_confidence_is_caught(self):
        d = self.dump(True)
        d["raw"]["userCas"]["sha256"] = ["00" * 32]
        self.assertIn("userCas: SHA-256 list = OpenSSL's fingerprint of the placed certificate", self.failures(d, True))
        d = self.dump(True)
        d["findings"][0]["confidence"] = "LOW"
        self.assertIn("finding attention and confidence", self.failures(d, True))


class Compare(unittest.TestCase):
    def failures(self, dump, t, api="34"):
        return [x.name for x in c.compare(dump, t, api) if not x.ok]

    def test_a_faithful_report_passes(self):
        self.assertEqual([], self.failures(good_dump(), truth()))

    def test_findings_owned_by_other_scripts_are_not_judged_here(self):
        d = good_dump()
        d["findings"].append(finding("condition:software-keys", "NOTE"))
        self.assertEqual([], self.failures(d, truth()))

    def test_a_missing_patch_finding_is_caught(self):
        d = good_dump()
        d["findings"] = [f for f in d["findings"] if f["id"] != "condition:patch-level"]
        self.assertIn("condition findings = the documented rules applied to the system's state", self.failures(d, truth()))

    def test_a_finding_for_a_phone_with_a_lock_is_caught(self):
        self.assertIn("condition findings = the documented rules applied to the system's state", self.failures(good_dump(), truth(lock_set=True)))

    def test_wrong_lock_reading_is_caught(self):
        d = good_dump()
        d["raw"]["device"]["screenLock"] = True
        self.assertIn("device: screen lock = locksettings", self.failures(d, truth()))

    def test_absent_setting_is_not_zero(self):
        d = good_dump()
        d["raw"]["device"]["developerOptions"] = 0
        self.assertIn("device: developer options = settings", self.failures(d, truth()))

    def test_a_refused_setting_is_not_a_reading(self):
        d = good_dump()
        d["raw"]["device"]["usbDebugging"] = "Refused(reason=x)"
        self.assertIn("device: USB debugging = settings", self.failures(d, truth()))

    def test_wrong_transport_and_proxy_are_caught(self):
        d = good_dump()
        d["raw"]["network"]["transports"] = ["CELLULAR"]
        d["raw"]["network"]["httpProxy"] = "1.2.3.4:5"
        f = self.failures(d, truth())
        self.assertIn("network: transports = dumpsys", f)
        self.assertIn("network: web proxy = settings", f)

    def test_wrong_wifi_security_is_caught(self):
        d = good_dump()
        d["raw"]["wifi"]["security"] = "WPA_PSK"
        self.assertIn("wifi: security type = cmd wifi status", self.failures(d, truth()))

    def test_a_missing_fact_row_is_caught(self):
        d = good_dump()
        d["facts"] = d["facts"][:-1]
        self.assertIn("every fact row is present exactly once, in display order", self.failures(d, truth()))

    def test_an_unavailable_signal_that_should_be_available_is_caught(self):
        d = good_dump()
        d["signals"][0]["availability"] = "UNAVAILABLE"
        self.assertIn("signal device.patch-level is AVAILABLE", self.failures(d, truth()))

    def test_android_eleven_wifi_security_is_expected_to_be_unavailable(self):
        d = good_dump()
        d["raw"]["wifi"]["security"] = None
        for s in d["signals"]:
            if s["id"] == "network.wifi-security":
                s["availability"] = "UNAVAILABLE"
        d["raw"]["device"]["sdk"] = 30
        d["findings"] = [f for f in d["findings"] if f["id"] != "condition:wifi-security"]
        self.assertEqual([], self.failures(d, truth(), api="30"))

    def test_a_refused_raw_reading_is_reported_not_crashed(self):
        d = copy.deepcopy(good_dump())
        d["raw"]["network"] = "Refused(reason=nope)"
        self.assertIn("network: raw reading present", self.failures(d, truth()))


if __name__ == "__main__":
    unittest.main()
