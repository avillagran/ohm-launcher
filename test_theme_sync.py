#!/usr/bin/env python3
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock

import link_server


class ThemeSyncTest(unittest.TestCase):
    def test_reads_current_omarchy_theme_palette(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            theme = home / ".local/state/omarchy/current/theme"
            theme.mkdir(parents=True)
            (theme / "colors.toml").write_text(
                'mode = "dark"\naccent = "#81a1c1"\nbackground = "#2e3440"\ninvalid = "value"\n',
                encoding="utf-8",
            )
            (theme.parent / "theme.name").write_text("Nord\n", encoding="utf-8")

            payload = link_server.read_omarchy_theme(home)

            self.assertEqual("Nord", payload["name"])
            self.assertEqual("dark", payload["mode"])
            self.assertEqual("#81a1c1", payload["colors"]["accent"])
            self.assertNotIn("invalid", payload["colors"])

    def test_pushes_theme_to_connected_phone_with_put(self):
        opener = MagicMock()
        opener.return_value.__enter__.return_value.status = 200
        payload = {"name": "Nord", "mode": "dark", "colors": {"accent": "#81a1c1"}}

        pushed = link_server.push_theme_to_phone(
            {"connected": True, "peerIp": "192.168.1.100", "peerPort": 8753},
            payload,
            opener,
        )

        self.assertTrue(pushed)
        request = opener.call_args.args[0]
        self.assertEqual("PUT", request.method)
        self.assertEqual("http://192.168.1.100:8753/omarchy/theme", request.full_url)
        self.assertEqual(payload, json.loads(request.data))

    def test_parses_ipv4_phone_from_avahi_browse_output(self):
        output = (
            '=;wlan0;IPv6;OhmLauncher;_ohm._tcp;local;phone.local;fe80::1;8753;"ohm=1"\n'
            '=;wlan0;IPv4;OhmLauncher;_ohm._tcp;local;phone.local;192.168.1.100;8753;"ohm=1"\n'
        )

        self.assertEqual(
            {"connected": True, "peerIp": "192.168.1.100", "peerPort": 8753, "peerName": "OhmLauncher"},
            link_server.parse_avahi_peer(output),
        )

    def test_discovered_phone_is_persisted_and_resynced_after_launcher_reinstall(self):
        payload = {"name": "Nord", "mode": "dark", "colors": {"accent": "#81a1c1"}}
        encoded = json.dumps(payload, sort_keys=True)
        persisted = []
        pushed = []

        result = link_server.sync_theme_once(
            encoded,
            payload_reader=lambda: payload,
            state_reader=lambda: {"connected": False},
            discover=lambda: {
                "connected": True,
                "peerIp": "192.168.1.100",
                "peerPort": 8753,
                "peerName": "OhmLauncher",
            },
            push=lambda state, value: pushed.append((state, value)) or True,
            state_writer=persisted.append,
        )

        self.assertEqual(encoded, result)
        self.assertEqual(1, len(pushed))
        self.assertEqual("192.168.1.100", persisted[0]["peerIp"])
        self.assertEqual(link_server.PORT, persisted[0]["linkPort"])

    def test_panel_exposes_server_diagnostics_even_while_disconnected(self):
        panel = Path(__file__).with_name("Panel.qml").read_text(encoding="utf-8")

        self.assertIn('property bool showLog: true', panel)
        self.assertIn('path: "/tmp/ls.log"', panel)
        self.assertIn('id: serverLogFile', panel)
        self.assertIn('enabled: true', panel)

    def test_panel_pulls_screen_frames_from_phone_instead_of_blocked_reverse_path(self):
        panel = Path(__file__).with_name("Panel.qml").read_text(encoding="utf-8")

        self.assertIn('base() + "/omarchy/screen/status"', panel)
        self.assertIn('base() + "/omarchy/screen/frame?sequence="', panel)
        self.assertNotIn('source: "file:///tmp/omarchy-screen.jpg"', panel)

    def test_screen_viewer_can_expand_inside_the_panel(self):
        panel = Path(__file__).with_name("Panel.qml").read_text(encoding="utf-8")

        self.assertIn("property bool screenExpanded: false", panel)
        self.assertIn("root.screenExpanded ? 620 : 280", panel)
        self.assertIn("root.screenExpanded ? 560 : 220", panel)
        self.assertIn('i18n.t(root.screenExpanded ? "screenReduce" : "screenExpand")', panel)


if __name__ == "__main__":
    unittest.main()
