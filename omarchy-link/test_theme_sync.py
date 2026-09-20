#!/usr/bin/env python3
import json
import os
import stat
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import MagicMock, patch

import link_server


class ThemeSyncTest(unittest.TestCase):
    def test_state_file_is_private_and_replaces_symlink_without_following_it(self):
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "state.json"
            victim = Path(directory) / "victim"
            victim.write_text("untouched", encoding="utf-8")
            state_file.symlink_to(victim)

            with patch.object(link_server, "STATE_FILE", str(state_file)):
                link_server.write_state({"connected": True, "peerToken": "secret"})

            self.assertEqual("untouched", victim.read_text(encoding="utf-8"))
            self.assertFalse(state_file.is_symlink())
            self.assertEqual(0o600, stat.S_IMODE(os.stat(state_file).st_mode))
            self.assertEqual("secret", json.loads(state_file.read_text())["peerToken"])

    def test_remote_requests_require_qr_pairing_token(self):
        with tempfile.TemporaryDirectory() as directory:
            token_file = Path(directory) / "token"
            token_file.write_text("a1b2c3", encoding="utf-8")

            request = MagicMock()
            request.client_address = ("192.168.1.50", 12345)
            with patch.object(link_server, "TOKEN_FILE", token_file):
                request.headers = {}
                self.assertFalse(link_server.Handler._authorized(request))
                request.headers = {"X-Omarchy-Link-Token": "wrong"}
                self.assertFalse(link_server.Handler._authorized(request))
                request.headers = {"X-Omarchy-Link-Token": "a1b2c3"}
                self.assertTrue(link_server.Handler._authorized(request))

            request.client_address = ("127.0.0.1", 12345)
            request.headers = {}
            self.assertTrue(link_server.Handler._authorized(request))

    def test_remote_clipboard_put_rejects_missing_pairing_token(self):
        request = MagicMock()
        request.client_address = ("192.168.1.50", 12345)
        request.headers = {}
        request.path = "/omarchy/clipboard"
        request._authorized = MagicMock(return_value=False)
        request._json = MagicMock()

        link_server.Handler.do_PUT(request)

        request._json.assert_called_once_with(401, {"ok": False, "error": "unauthorized"})
        request._read_body.assert_not_called()

    def test_current_theme_exports_live_hyprland_corner_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            theme = home / ".local/state/omarchy/current/theme"
            theme.mkdir(parents=True)
            (theme / "colors.toml").write_text('accent = "#ffffff"\n', encoding="utf-8")
            runner = MagicMock(return_value=subprocess.CompletedProcess(
                [], 0, stdout='{"int": 0}\n', stderr="",
            ))

            payload = link_server.read_omarchy_theme(home, runner=runner)

            self.assertEqual({"cornerRadius": 0}, payload["geometry"])
            runner.assert_called_once_with(
                ["hyprctl", "-j", "getoption", "decoration:rounding"],
                check=False,
                capture_output=True,
                text=True,
                timeout=3,
            )

    def test_background_catalog_parses_all_categories_with_opaque_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "home"
            plugin = Path(directory) / "plugin"
            (plugin / "bin").mkdir(parents=True)
            (plugin / "bin/list.sh").write_text("#!/bin/sh\n", encoding="utf-8")
            preview = Path(directory) / "poster.jpg"
            preview.write_bytes(b"poster")
            calls = []

            def runner(argv, **kwargs):
                calls.append((argv, kwargs))
                category = argv[1]
                stdout = ""
                if category == "image":
                    stdout = "image\t/wall/a.png\tAurora\t/wall/a.png\t\t/wall/a.png\n"
                elif category == "video":
                    stdout = f"video\t/wall/movie.mp4\tMovie\t{preview}\t/wall/movie.mp4\t/wall/movie.mp4\n"
                elif category == "audio":
                    stdout = "audio\tvhstape\tVHS Tape\t/missing.jpg\t\tvhstape\n"
                return subprocess.CompletedProcess(argv, 0, stdout=stdout, stderr="")

            catalog = link_server.read_omarchy_background_catalog(home, plugin, runner)

            self.assertEqual(link_server.BACKGROUND_CATEGORIES, tuple(call[0][1] for call in calls))
            self.assertEqual(["image", "video", "audio"], [item["type"] for item in catalog["backgrounds"]])
            first = catalog["backgrounds"][0]
            self.assertEqual({"id", "label", "type", "preview", "hasPreview"}, set(first))
            self.assertRegex(first["id"], r"^[0-9a-f]{64}$")
            self.assertNotIn("/wall", first["id"])
            self.assertEqual("/omarchy/backgrounds/%s/preview" % first["id"], first["preview"])
            self.assertTrue(catalog["backgrounds"][1]["hasPreview"])
            self.assertFalse(catalog["backgrounds"][2]["hasPreview"])
            for _, kwargs in calls:
                self.assertFalse(kwargs.get("shell", False))

    def test_background_selection_resolves_fresh_catalog_uses_argv_and_verifies_state(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "home"
            plugin = Path(directory) / "plugin"
            (plugin / "bin").mkdir(parents=True)
            list_script = plugin / "bin/list.sh"
            apply_script = plugin / "bin/omarchy-parallax-set-type"
            list_script.write_text("#!/bin/sh\n", encoding="utf-8")
            apply_script.write_text("#!/bin/sh\n", encoding="utf-8")
            source = Path(directory) / "wall;touch-pwned.png"
            source.write_bytes(b"image")
            row = f"image\t{source}\tSafe label\t{source}\t\t{source}\n"

            def listing(argv, **_kwargs):
                return subprocess.CompletedProcess(argv, 0, stdout=row if argv[1] == "image" else "", stderr="")

            background_id = link_server.read_omarchy_background_catalog(home, plugin, listing)["backgrounds"][0]["id"]
            calls = []

            def runner(argv, **kwargs):
                calls.append((argv, kwargs))
                if argv[0] == str(list_script):
                    return subprocess.CompletedProcess(argv, 0, stdout=row if argv[1] == "image" else "", stderr="")
                state = home / ".local/state/omarchy/current"
                state.mkdir(parents=True, exist_ok=True)
                (state / "bg-source-type").write_text(argv[1] + "\n", encoding="utf-8")
                (state / "bg-source-path").write_text(argv[2] + "\n", encoding="utf-8")
                return subprocess.CompletedProcess(argv, 0)

            self.assertTrue(link_server.select_omarchy_background(background_id, home, plugin, runner))
            apply_argv, apply_kwargs = calls[-1]
            self.assertEqual([str(apply_script), "image", str(source)], apply_argv)
            self.assertFalse(apply_kwargs.get("shell", False))
            self.assertFalse(link_server.select_omarchy_background(str(source), home, plugin, runner))
            self.assertEqual(1, sum(call[0][0] == str(apply_script) for call in calls))

    def test_background_selection_rejects_success_without_matching_state(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "home"
            plugin = Path(directory) / "plugin"
            (plugin / "bin").mkdir(parents=True)
            (plugin / "bin/list.sh").write_text("", encoding="utf-8")
            (plugin / "bin/omarchy-parallax-set-type").write_text("", encoding="utf-8")
            row = "audio\tvhstape\tVHS Tape\t\t\tvhstape\n"

            def runner(argv, **_kwargs):
                return subprocess.CompletedProcess(argv, 0, stdout=row if argv[-1] == "audio" else "", stderr="")

            background_id = link_server.read_omarchy_background_catalog(home, plugin, runner)["backgrounds"][0]["id"]
            self.assertFalse(link_server.select_omarchy_background(background_id, home, plugin, runner))

    def test_background_selection_rejects_id_removed_from_fresh_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "home"
            plugin = Path(directory) / "plugin"
            (plugin / "bin").mkdir(parents=True)
            (plugin / "bin/list.sh").write_text("", encoding="utf-8")
            (plugin / "bin/omarchy-parallax-set-type").write_text("", encoding="utf-8")
            row = "audio\tvhstape\tVHS Tape\t\t\tvhstape\n"

            def listing(argv, **_kwargs):
                return subprocess.CompletedProcess(argv, 0, stdout=row if argv[-1] == "audio" else "", stderr="")

            background_id = link_server.read_omarchy_background_catalog(home, plugin, listing)["backgrounds"][0]["id"]
            runner = MagicMock(return_value=subprocess.CompletedProcess([], 0, stdout="", stderr=""))

            self.assertFalse(link_server.select_omarchy_background(background_id, home, plugin, runner))
            self.assertEqual(len(link_server.BACKGROUND_CATEGORIES), runner.call_count)

    def test_reads_audio_desktop_background_and_android_ttfx_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            current = home / ".local/state/omarchy/current"
            theme = current / "theme"
            theme.mkdir(parents=True)
            (theme / "colors.toml").write_text('background = "#000000"\n', encoding="utf-8")
            fallback = theme / "fallback.png"
            fallback.write_bytes(b"fallback")
            (current / "background").symlink_to(fallback)
            (current / "bg-source-type").write_text("audio\n", encoding="utf-8")
            (current / "bg-source-path").write_text("vhstape\n", encoding="utf-8")
            audio_state = home / ".local/state/omarchy/audio-background/state.json"
            audio_state.parent.mkdir(parents=True)
            audio_state.write_text(json.dumps({
                "running": True,
                "effect": "errorcorrect",
                "ttfx_text": "OMARCHY",
                "intro_size": 7,
                "audio": True,
                "intensity": 5,
                "speed": 8,
                "resolution": 2,
                "reactivity": 2,
            }), encoding="utf-8")

            payload = link_server.read_omarchy_theme(home)

            self.assertEqual("fallback.png", payload["background"]["name"])
            self.assertEqual("audio", payload["desktopBackground"]["type"])
            self.assertEqual("vhstape", payload["desktopBackground"]["path"])
            self.assertEqual({
                "enabled": True,
                "effect": "vhstape",
                "text": "OMARCHY",
                "textSize": 7,
                "audio": True,
                "intensity": 5,
                "speed": 1.6,
                "resolution": 2,
                "reactivity": 2,
            }, payload["desktopBackground"]["ttfx"])

    def test_desktop_background_is_emitted_when_theme_palette_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            current = home / ".local/state/omarchy/current"
            current.mkdir(parents=True)
            (current / "bg-source-type").write_text("audio\n", encoding="utf-8")
            (current / "bg-source-path").write_text("matrix\n", encoding="utf-8")

            payload = link_server.read_omarchy_theme(home)

            self.assertEqual({}, payload["colors"])
            self.assertEqual("audio", payload["desktopBackground"]["type"])
            self.assertEqual("matrix", payload["desktopBackground"]["ttfx"]["effect"])

    def test_image_desktop_background_becomes_transferable_canonical_background(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            current = home / ".local/state/omarchy/current"
            theme = current / "theme"
            theme.mkdir(parents=True)
            (theme / "colors.toml").write_text('background = "#000000"\n', encoding="utf-8")
            fallback = theme / "fallback.png"
            fallback.write_bytes(b"fallback")
            selected = Path(directory) / "selected.mp4"
            selected.write_bytes(b"selected-video")
            (current / "background").symlink_to(fallback)
            (current / "bg-source-type").write_text("video\n", encoding="utf-8")
            (current / "bg-source-path").write_text(str(selected) + "\n", encoding="utf-8")

            payload = link_server.read_omarchy_theme(home)

            self.assertEqual({"type": "video", "path": str(selected)}, payload["desktopBackground"])
            self.assertEqual("selected.mp4", payload["background"]["name"])
            self.assertEqual(
                link_server.hashlib.sha256(b"selected-video").hexdigest(),
                payload["background"]["sha256"],
            )

    def test_background_preview_resolves_only_by_fresh_opaque_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            preview = Path(directory) / "poster.jpg"
            preview.write_bytes(b"poster")
            item = {
                "id": "a" * 64,
                "label": "Poster",
                "type": "image",
                "preview": "/omarchy/backgrounds/%s/preview" % ("a" * 64),
                "hasPreview": True,
                "_previewPath": str(preview),
            }
            with patch("link_server._background_entries", return_value=[item]):
                self.assertEqual(preview, link_server.servable_background_preview("a" * 64))
                self.assertIsNone(link_server.servable_background_preview("../poster.jpg"))

    def test_background_video_preview_is_converted_to_bounded_static_jpeg(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "cache"
            video = Path(directory) / "clip.mp4"
            video.write_bytes(b"video")
            item = {
                "id": "c" * 64,
                "_previewPath": str(video),
            }

            def ffmpeg(argv, **_kwargs):
                Path(argv[-1]).write_bytes(b"jpeg")
                return subprocess.CompletedProcess(argv, 0)

            with patch.dict(link_server.os.environ, {"XDG_CACHE_HOME": str(cache)}), \
                    patch("link_server._background_entries", return_value=[item]), \
                    patch("link_server.subprocess.run", side_effect=ffmpeg) as runner:
                preview = link_server.servable_background_preview("c" * 64)

            self.assertEqual(".jpg", preview.suffix)
            self.assertEqual(b"jpeg", preview.read_bytes())
            argv = runner.call_args.args[0]
            self.assertEqual("ffmpeg", argv[0])
            self.assertIn("scale='min(1280,iw)':-2", argv)

    def test_background_static_preview_thumbnail_is_cached_as_bounded_jpeg(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "cache"
            image = Path(directory) / "poster.png"
            image.write_bytes(b"png")
            item = {"id": "d" * 64, "_previewPath": str(image)}

            def ffmpeg(argv, **_kwargs):
                Path(argv[-1]).write_bytes(b"jpeg")
                return subprocess.CompletedProcess(argv, 0)

            with patch.dict(link_server.os.environ, {"XDG_CACHE_HOME": str(cache)}), \
                    patch("link_server._background_entries", return_value=[item]), \
                    patch("link_server.subprocess.run", side_effect=ffmpeg) as runner:
                first = link_server.background_preview_thumbnail("d" * 64)
                second = link_server.background_preview_thumbnail("d" * 64)

            self.assertEqual(first, second)
            self.assertEqual(".jpg", first.suffix)
            self.assertEqual(1, runner.call_count)
            argv = runner.call_args.args[0]
            self.assertEqual("ffmpeg", argv[0])
            self.assertIn("scale='min(1280,iw)':-2", argv)
            self.assertNotIn("shell", runner.call_args.kwargs)

    def test_pushes_background_catalog_previews_and_current_to_phone(self):
        catalog = {
            "current": {"id": "a" * 64, "type": "image", "path": "/wall/a.png"},
            "backgrounds": [
                {"id": "a" * 64, "label": "Aurora", "type": "image", "hasPreview": True},
                {"id": "b" * 64, "label": "Matrix", "type": "audio", "hasPreview": False},
            ],
        }
        requests = []

        class Response:
            status = 200
            def __init__(self, body=b"{}"):
                self.body = body
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return False
            def read(self):
                return self.body

        def opener(request, timeout):
            requests.append(request)
            if request.full_url.endswith("/omarchy/file"):
                return Response(b'{"path":"/sdcard/OhmLauncher/shared/aurora.jpg"}')
            return Response()

        with tempfile.TemporaryDirectory() as directory:
            preview = Path(directory) / "preview.jpg"
            preview.write_bytes(b"jpeg")
            with patch("link_server.read_omarchy_background_catalog", return_value=catalog), \
                    patch("link_server.background_preview_thumbnail", side_effect=[preview, None]):
                pushed = link_server.push_background_catalog_to_phone(
                    {"connected": True, "peerIp": "192.168.1.100", "peerPort": 8753}, opener
                )

        self.assertTrue(pushed)
        self.assertEqual(["POST", "PUT"], [request.method for request in requests])
        self.assertEqual("http://192.168.1.100:8753/omarchy/backgrounds/catalog", requests[-1].full_url)
        delivered = json.loads(requests[-1].data)
        self.assertEqual(catalog["current"], delivered["current"])
        self.assertEqual("/sdcard/OhmLauncher/shared/aurora.jpg", delivered["backgrounds"][0]["previewPath"])
        self.assertEqual("", delivered["backgrounds"][1]["previewPath"])
        self.assertNotIn("preview", delivered["backgrounds"][0])

    def test_processes_phone_background_selection_and_pushes_result_immediately(self):
        calls = []

        class Response:
            status = 200
            def __init__(self, body=b"{}"):
                self.body = body
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return False
            def read(self):
                return self.body

        def opener(request, timeout):
            calls.append(request)
            if request.full_url.endswith("/omarchy/backgrounds/selection"):
                return Response(json.dumps({"pending": True, "id": "e" * 64}).encode())
            return Response()

        state = {"connected": True, "peerIp": "192.168.1.100", "peerPort": 8753,
                 "peerToken": "phone-session-token"}
        theme = {"name": "Nord", "colors": {"accent": "#81a1c1"}}
        with patch("link_server.select_omarchy_background", return_value=True) as select, \
                patch("link_server.read_omarchy_theme", return_value=theme), \
                patch("link_server.push_theme_to_phone", return_value=True) as push:
            applied = link_server.process_phone_background_selection(state, opener)

        self.assertTrue(applied)
        select.assert_called_once_with("e" * 64)
        ack = calls[1]
        self.assertEqual("PUT", ack.method)
        self.assertTrue(ack.full_url.endswith("/omarchy/backgrounds/selection/ack"))
        self.assertEqual("phone-session-token", ack.get_header("X-omarchy-link-token"))
        self.assertEqual({"id": "e" * 64, "status": "applied"}, json.loads(ack.data))
        push.assert_called_once_with(state, theme, opener)

    def test_background_selection_poll_rejects_non_opaque_id_without_selecting(self):
        class Response:
            status = 200
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return False
            def read(self):
                return b'{"pending":true,"id":"../../evil"}'

        with patch("link_server.select_omarchy_background") as select:
            applied = link_server.process_phone_background_selection(
                {"connected": True, "peerIp": "127.0.0.1", "peerPort": 8753},
                lambda *_args, **_kwargs: Response(),
            )

        self.assertFalse(applied)
        select.assert_not_called()

    def test_background_catalog_signature_tracks_current_and_preview_metadata(self):
        catalog = {
            "current": {"id": "a" * 64, "type": "image", "path": "/wall/a.png"},
            "backgrounds": [{"id": "a" * 64}],
        }
        with tempfile.TemporaryDirectory() as directory:
            preview = Path(directory) / "preview.png"
            preview.write_bytes(b"one")
            with patch("link_server.read_omarchy_background_catalog", return_value=catalog), \
                    patch("link_server.servable_background_preview", return_value=preview):
                first = link_server.background_catalog_signature()
                catalog["current"] = {"id": "b" * 64, "type": "video", "path": "/wall/b.mp4"}
                second = link_server.background_catalog_signature()

        self.assertNotEqual(first, second)

    def test_sync_loop_polls_background_selection_and_deduplicates_catalog_push(self):
        class TwoIterations:
            def __init__(self):
                self.calls = 0
            def wait(self, _timeout):
                self.calls += 1
                return self.calls > 2

        state = {
            "connected": True,
            "peerIp": "192.168.1.100",
            "peerPort": 8753,
            "linkNonce": "connection-1",
        }
        payload = {"name": "Nord", "colors": {"accent": "#81a1c1"}}
        encoded = json.dumps(payload, sort_keys=True)
        with patch("link_server.read_omarchy_theme", return_value=payload), \
                patch("link_server.sync_theme_once", return_value=encoded), \
                patch("link_server.read_state", return_value=state), \
                patch("link_server.process_phone_theme_selection", return_value=False), \
                patch("link_server.process_phone_background_selection", return_value=False) as selection, \
                patch("link_server.theme_catalog_signature", return_value="theme-signature"), \
                patch("link_server.background_catalog_signature", return_value="background-signature"), \
                patch("link_server.push_theme_catalog_to_phone", return_value=True), \
                patch("link_server.push_background_catalog_to_phone", return_value=True) as push, \
                patch("link_server.log"):
            link_server.theme_sync_loop(TwoIterations())

        self.assertEqual(2, selection.call_count)
        push.assert_called_once_with(state)

    def test_background_http_endpoints_expose_contract_and_reject_path_selection(self):
        catalog = {
            "current": {"type": "audio", "path": "vhstape"},
            "backgrounds": [{
                "id": "b" * 64,
                "label": "VHS Tape",
                "type": "audio",
                "preview": "/omarchy/backgrounds/%s/preview" % ("b" * 64),
                "hasPreview": False,
            }],
        }
        server = link_server.ThreadingHTTPServer(("127.0.0.1", 0), link_server.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:%d" % server.server_port
        try:
            with patch("link_server.read_omarchy_background_catalog", return_value=catalog):
                with urllib.request.urlopen(base + "/omarchy/backgrounds") as response:
                    self.assertEqual(catalog, json.load(response))
            body = json.dumps({"id": "/tmp/evil"}).encode("utf-8")
            request = urllib.request.Request(
                base + "/omarchy/backgrounds/select",
                data=body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with patch("link_server.select_omarchy_background", return_value=False) as select:
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request)
                self.assertEqual(400, error.exception.code)
                select.assert_called_once_with("/tmp/evil")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_theme_catalog_matches_omarchy_precedence_and_preview_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "home"
            omarchy = Path(directory) / "omarchy"
            stock = omarchy / "themes"
            user = home / ".config/omarchy/themes"
            (stock / "nord/backgrounds").mkdir(parents=True)
            (stock / "nord/backgrounds/lake.jpg").write_bytes(b"stock")
            (stock / "tokyo-night").mkdir(parents=True)
            (stock / "tokyo-night/preview.png").write_bytes(b"tokyo")
            (user / "nord").mkdir(parents=True)
            (user / "nord/colors.toml").write_text('accent = "#81a1c1"\n', encoding="utf-8")
            current = home / ".local/state/omarchy/current"
            current.mkdir(parents=True)
            (current / "theme.name").write_text("nord\n", encoding="utf-8")

            catalog = link_server.read_omarchy_theme_catalog(home, omarchy)

            self.assertEqual("nord", catalog["current"])
            self.assertEqual(["nord", "tokyo-night"], [item["id"] for item in catalog["themes"]])
            nord = catalog["themes"][0]
            self.assertEqual("Nord", nord["label"])
            self.assertTrue(nord["preview"].endswith("/omarchy/themes/nord/preview"))
            self.assertEqual(
                stock / "nord/backgrounds/lake.jpg",
                link_server.theme_preview_path("nord", home, omarchy),
            )

    def test_theme_catalog_rejects_path_like_ids_and_selection_uses_no_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "home"
            omarchy = Path(directory) / "omarchy"
            (omarchy / "themes/nord").mkdir(parents=True)
            runner = MagicMock()
            runner.return_value.returncode = 0

            self.assertIsNone(link_server.resolve_theme("../nord", home, omarchy))
            self.assertFalse(link_server.select_omarchy_theme("../nord", home, omarchy, runner))
            self.assertTrue(link_server.select_omarchy_theme("nord", home, omarchy, runner))
            runner.assert_called_once_with(
                ["omarchy-theme-set", "nord"],
                check=False,
                timeout=120,
                stdout=link_server.subprocess.DEVNULL,
                stderr=link_server.subprocess.DEVNULL,
                env=unittest.mock.ANY,
            )

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
            background = theme / "backgrounds" / "lake.png"
            background.parent.mkdir()
            background.write_bytes(b"canonical-background")
            (theme.parent / "background").symlink_to(background)

            payload = link_server.read_omarchy_theme(home)

            self.assertEqual("Nord", payload["name"])
            self.assertEqual("dark", payload["mode"])
            self.assertEqual("#81a1c1", payload["colors"]["accent"])
            self.assertNotIn("invalid", payload["colors"])
            self.assertEqual("lake.png", payload["background"]["name"])
            self.assertEqual("image/png", payload["background"]["mime"])
            self.assertEqual(64, len(payload["background"]["sha256"]))

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

    def test_pushes_background_before_theme_and_passes_phone_path(self):
        with tempfile.TemporaryDirectory() as directory:
            background = Path(directory) / "lake.png"
            background.write_bytes(b"canonical-background")
            requests = []

            class Response:
                status = 200
                def __init__(self, body=b"{}"):
                    self.body = body
                def __enter__(self):
                    return self
                def __exit__(self, *_):
                    return False
                def read(self):
                    return self.body

            def opener(request, timeout):
                requests.append(request)
                if request.full_url.endswith("/omarchy/file"):
                    return Response(b'{"path":"/phone/shared/lake.png"}')
                return Response()

            payload = {
                "name": "Nord",
                "colors": {"accent": "#81a1c1"},
                "background": {"name": "lake.png", "mime": "image/png", "sha256": "0" * 64},
            }
            with patch("link_server.current_omarchy_background", return_value=background):
                self.assertTrue(link_server.push_theme_to_phone(
                    {"connected": True, "peerIp": "192.168.1.100", "peerPort": 8753},
                    payload,
                    opener,
                ))

            self.assertEqual(["POST", "PUT"], [request.method for request in requests])
            delivered = json.loads(requests[1].data)
            self.assertEqual("/phone/shared/lake.png", delivered["background"]["phonePath"])

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

        self.assertIn('property bool showLog: false', panel)
        self.assertIn('path: "/tmp/ls.log"', panel)
        self.assertIn('id: serverLogFile', panel)
        self.assertIn('enabled: true', panel)

    def test_panel_repairs_connection_state_and_can_hide_pairing_qr(self):
        panel = Path(__file__).with_name("Panel.qml").read_text(encoding="utf-8")
        bar = Path(__file__).with_name("BarWidget.qml").read_text(encoding="utf-8")

        self.assertIn('function refreshConnectionState()', panel)
        self.assertIn('"/omarchy/link"', panel)
        self.assertIn('onTriggered: root.refreshConnectionState()', panel)
        self.assertIn('property bool pairingDismissed: false', panel)
        self.assertIn('text: i18n.t("hideQr")', panel)
        self.assertIn('text: root.connected ? i18n.t("linkMore") : i18n.t("showQr")', panel)
        self.assertIn('readonly property color connectionColor: "#4ade80"', panel)
        self.assertIn('? button.activeColor', bar)

    def test_android_status_icon_repaints_theme_dependent_colors(self):
        icon = Path(__file__).with_name("AndroidIcon.qml").read_text(encoding="utf-8")
        bar = Path(__file__).with_name("BarWidget.qml").read_text(encoding="utf-8")

        self.assertIn("property color cutoutColor", icon)
        self.assertIn("onColorChanged: canvas.requestPaint()", icon)
        self.assertIn("onCutoutColorChanged: canvas.requestPaint()", icon)
        self.assertIn("cutoutColor: Color.background", bar)

    def test_panel_pulls_screen_frames_from_phone_instead_of_blocked_reverse_path(self):
        panel = Path(__file__).with_name("Panel.qml").read_text(encoding="utf-8")

        self.assertIn('base() + "/omarchy/screen/status?after="', panel)
        self.assertIn('base() + "/omarchy/screen/frame?sequence="', panel)
        self.assertNotIn('source: "file:///tmp/omarchy-screen.jpg"', panel)

    def test_screen_viewer_can_expand_inside_the_panel(self):
        panel = Path(__file__).with_name("Panel.qml").read_text(encoding="utf-8")

        self.assertIn("property bool screenExpanded: false", panel)
        self.assertIn("root.screenExpanded ? 620 : 280", panel)
        self.assertIn("root.screenExpanded ? 560 : 220", panel)
        self.assertIn('i18n.t(root.screenExpanded ? "screenReduce" : "screenExpand")', panel)


class ThemeBackgroundIdTest(unittest.TestCase):
    def _fixture(self, directory):
        home = Path(directory) / "home"
        omarchy = Path(directory) / "omarchy"
        stock = omarchy / "themes"
        user = home / ".config/omarchy/themes"
        for theme in ("nord", "tokyo-night"):
            (stock / theme).mkdir(parents=True)
            (stock / theme / "preview.png").write_bytes(b"preview-" + theme.encode())
            (stock / theme / "colors.toml").write_text('accent = "#81a1c1"\n', encoding="utf-8")
        (stock / "nord/backgrounds").mkdir()
        (stock / "nord/backgrounds/lake.jpg").write_bytes(b"lake")
        (stock / "nord/backgrounds/peak.jpg").write_bytes(b"peak")
        (stock / "tokyo-night/backgrounds").mkdir()
        (stock / "tokyo-night/backgrounds/bay.jpg").write_bytes(b"bay")
        current = home / ".local/state/omarchy/current"
        current.mkdir(parents=True)
        (current / "theme.name").write_text("nord\n", encoding="utf-8")
        return home, omarchy, stock

    def _entries(self, stock):
        rows = []
        for theme, name in (("nord", "lake.jpg"), ("nord", "peak.jpg"), ("tokyo-night", "bay.jpg")):
            path = stock / theme / "backgrounds" / name
            rows.append({
                "id": "%s-%s" % (theme, name),
                "_path": str(path),
            })
        return rows

    def test_same_name_background_wins_when_switching_theme(self):
        with tempfile.TemporaryDirectory() as directory:
            home, omarchy, stock = self._fixture(directory)
            current = home / ".local/state/omarchy/current"
            (current / "background").symlink_to(stock / "nord/backgrounds/peak.jpg")

            entries = self._entries(stock)

            self.assertEqual(
                "tokyo-night-bay.jpg",
                link_server.chosen_theme_background_id("tokyo-night", entries, home, omarchy),
            )

    def test_first_theme_background_wins_when_current_name_is_custom(self):
        with tempfile.TemporaryDirectory() as directory:
            home, omarchy, stock = self._fixture(directory)
            current = home / ".local/state/omarchy/current"
            outsider = home / "Pictures"
            outsider.mkdir()
            (outsider / "my-wall.png").write_bytes(b"custom")
            (current / "background").symlink_to(outsider / "my-wall.png")

            entries = self._entries(stock)

            self.assertEqual(
                "nord-lake.jpg",
                link_server.chosen_theme_background_id("nord", entries, home, omarchy),
            )

    def test_current_theme_background_wins_when_current_name_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            home, omarchy, stock = self._fixture(directory)
            current = home / ".local/state/omarchy/current"
            (current / "background").symlink_to(stock / "nord/backgrounds/peak.jpg")

            entries = self._entries(stock)

            self.assertEqual(
                "nord-peak.jpg",
                link_server.chosen_theme_background_id("nord", [
                    {"id": "nord-peak.jpg", "_path": str(stock / "nord/backgrounds/peak.jpg")},
                ], home, omarchy),
            )

    def test_theme_background_matches_the_applied_path_from_animated_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            home, omarchy, stock = self._fixture(directory)
            background = stock / "nord/backgrounds/lake.jpg"

            self.assertEqual(
                "nord-lake",
                link_server.chosen_theme_background_id("nord", [{
                    "id": "nord-lake",
                    "_path": "lake",
                    "_applied": str(background),
                }], home, omarchy),
            )

    def test_user_background_folder_participates_and_sorted_first_wins(self):
        with tempfile.TemporaryDirectory() as directory:
            home, omarchy, stock = self._fixture(directory)
            user_backgrounds = home / ".config/omarchy/backgrounds/tokyo-night"
            user_backgrounds.mkdir(parents=True)
            (user_backgrounds / "custom.png").write_bytes(b"custom")

            entries = self._entries(stock) + [
                {"id": "user-custom", "_path": str(user_backgrounds / "custom.png")},
            ]

            chosen = link_server.chosen_theme_background_id("tokyo-night", entries, home, omarchy)
            self.assertEqual("user-custom", chosen)

    def test_unknown_theme_or_missing_entry_yields_no_id(self):
        with tempfile.TemporaryDirectory() as directory:
            home, omarchy, stock = self._fixture(directory)

            self.assertEqual("", link_server.chosen_theme_background_id("nope", [], home, omarchy))
            self.assertEqual("", link_server.chosen_theme_background_id("nord", [], home, omarchy))

    def test_theme_catalog_push_includes_background_id(self):
        with tempfile.TemporaryDirectory() as directory:
            home, omarchy, stock = self._fixture(directory)
            state = {"connected": True, "peerIp": "127.0.0.1", "peerPort": 8753}
            delivered = {}

            class _Response:
                status = 200

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return False

                def read(self):
                    return b""

            def _opener(request, timeout=0):
                delivered["url"] = request.full_url
                delivered["body"] = json.loads(request.data.decode("utf-8"))
                return _Response()

            entries = self._entries(stock)
            with patch.object(link_server, "read_omarchy_theme_catalog") as catalog, \
                    patch.object(link_server, "_background_entries", return_value=entries), \
                    patch.object(link_server, "servable_theme_preview", return_value=None), \
                    patch.object(link_server, "push_file_to_phone", return_value=""), \
                    patch.object(link_server.Path, "home", return_value=home), \
                    patch.dict("os.environ", {"OMARCHY_PATH": str(omarchy)}):
                catalog.return_value = {
                    "current": "nord",
                    "themes": [
                        {"id": "nord", "label": "Nord", "palette": {}},
                        {"id": "tokyo-night", "label": "Tokyo Night", "palette": {}},
                    ],
                }
                self.assertTrue(link_server.push_theme_catalog_to_phone(state, _opener))

            themes = {item["id"]: item for item in delivered["body"]["themes"]}
            self.assertEqual("nord-lake.jpg", themes["nord"]["backgroundId"])
            self.assertEqual("tokyo-night-bay.jpg", themes["tokyo-night"]["backgroundId"])

    def test_theme_catalog_signature_tracks_current_background(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            omarchy = home / "omarchy"
            (omarchy / "themes/nord").mkdir(parents=True)
            (omarchy / "themes/nord/preview.png").write_bytes(b"preview")
            current = home / ".local/state/omarchy/current"
            current.mkdir(parents=True)
            (current / "theme.name").write_text("nord\n", encoding="utf-8")

            with patch.object(link_server.Path, "home", return_value=home), \
                    patch.object(link_server, "_DEFAULT_OMARCHY_PATH", omarchy), \
                    patch.dict("os.environ", {"OMARCHY_PATH": str(omarchy)}):
                before = link_server.theme_catalog_signature()
                (current / "background").symlink_to(omarchy / "themes/nord/preview.png")
                after = link_server.theme_catalog_signature()

            self.assertNotEqual(before, after)


if __name__ == "__main__":
    unittest.main()
