#!/usr/bin/env python3
import json
import subprocess
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import MagicMock, patch

import link_server


class NotificationForwardingTest(unittest.TestCase):
    def request(self, payload, content_type="application/json"):
        server = ThreadingHTTPServer(("127.0.0.1", 0), link_server.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
            request = urllib.request.Request(
                "http://127.0.0.1:%d/omarchy/notify" % server.server_port,
                data=body,
                headers={"Content-Type": content_type},
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=2) as response:
                    return response.status, json.loads(response.read())
            except urllib.error.HTTPError as error:
                return error.code, json.loads(error.read())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_pushes_notification_to_connected_phone_with_post(self):
        opener = MagicMock()
        response = opener.return_value.__enter__.return_value
        response.status = 200
        response.read.return_value = b'{"ok":true,"id":"notice-1"}'
        payload = {
            "id": "notice-1",
            "title": "Build finished",
            "message": "All tests passed",
            "source": "omarchy-hermes",
            "level": "success",
            "channel": "development",
            "timestamp": "2026-09-13T12:00:00Z",
        }

        result = link_server.push_notification_to_phone(
            {"connected": True, "peerIp": "192.168.1.100", "peerPort": 8753},
            payload,
            opener,
        )

        self.assertEqual({"ok": True, "id": "notice-1"}, result)
        request = opener.call_args.args[0]
        self.assertEqual("POST", request.method)
        self.assertEqual("http://192.168.1.100:8753/omarchy/notify", request.full_url)
        self.assertEqual(payload, json.loads(request.data))

    def test_rejects_title_longer_than_120_characters(self):
        with self.assertRaisesRegex(ValueError, "title must be at most 120 characters"):
            link_server.validate_notification({"message": "hello", "title": "x" * 121})

    def test_validates_notification_schema_and_remaining_bounds(self):
        invalid_payloads = [
            ({}, "message is required"),
            ({"message": "x" * 4001}, "message must be at most 4000 characters"),
            ({"message": "ok", "source": "x" * 81}, "source must be at most 80 characters"),
            ({"message": "ok", "channel": "x" * 81}, "channel must be at most 80 characters"),
            ({"message": "ok", "id": "x" * 129}, "id must be at most 128 characters"),
            ({"message": "ok", "timestamp": "x" * 65}, "timestamp must be at most 64 characters"),
            ({"message": "ok", "level": "urgent"}, "level must be one of"),
            ({"message": 7}, "message must be a string"),
            ({"message": "ok", "extra": "no"}, "unknown field: extra"),
        ]
        for payload, error in invalid_payloads:
            with self.subTest(payload=payload), self.assertRaisesRegex(ValueError, error):
                link_server.validate_notification(payload)

        payload = {"message": "ok", "level": "warning", "source": "hermes"}
        self.assertEqual(payload, link_server.validate_notification(payload))

    def test_notify_endpoint_discovers_phone_persists_it_and_forwards(self):
        discovered = {
            "connected": True,
            "peerIp": "192.168.1.100",
            "peerPort": 8753,
            "peerName": "OhmLauncher",
        }
        payload = {"id": "notice-1", "message": "done", "level": "success"}
        with (
            patch.object(link_server, "read_state", return_value={"connected": False}),
            patch.object(link_server, "discover_phone", return_value=discovered),
            patch.object(link_server, "write_state") as write_state,
            patch.object(
                link_server,
                "push_notification_to_phone",
                return_value={"ok": True, "id": "notice-1"},
            ) as push,
        ):
            status, reply = self.request(payload)

        self.assertEqual(200, status)
        self.assertEqual(
            {"ok": True, "delivered": True, "phone": {"ok": True, "id": "notice-1"}},
            reply,
        )
        push.assert_called_once_with(discovered, payload)
        write_state.assert_called_once_with({**discovered, "linkPort": link_server.PORT})

    def test_notify_endpoint_rejects_oversized_request_body(self):
        status, reply = self.request({"message": "x" * 9000})

        self.assertEqual(413, status)
        self.assertEqual({"ok": False, "error": "payload_too_large"}, reply)

    def test_notify_endpoint_requires_json_content_type(self):
        status, reply = self.request({"message": "hello"}, content_type="text/plain")

        self.assertEqual(415, status)
        self.assertEqual({"ok": False, "error": "content_type_must_be_json"}, reply)

    def test_cli_helper_posts_hermes_notification(self):
        received = []

        class Receiver(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers["Content-Length"])
                received.append((self.path, json.loads(self.rfile.read(length))))
                body = b'{"ok":true,"delivered":true}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).with_name("omarchy_notify.py")),
                    "--url", "http://127.0.0.1:%d" % server.server_port,
                    "--title", "Task finished",
                    "--level", "success",
                    "--channel", "agent",
                    "All checks passed",
                ],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("/omarchy/notify", received[0][0])
        self.assertEqual("All checks passed", received[0][1]["message"])
        self.assertEqual("omarchy-hermes", received[0][1]["source"])
        self.assertEqual("success", received[0][1]["level"])
        self.assertEqual({"ok": True, "delivered": True}, json.loads(result.stdout))


if __name__ == "__main__":
    unittest.main()
