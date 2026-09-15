#!/usr/bin/env python3
"""Send an Omarchy Link notification to the connected OhmLauncher phone."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

from link_server import NOTIFICATION_LEVELS, validate_notification


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("message", nargs="?", help="message text; reads stdin when omitted")
    parser.add_argument("--url", default=os.environ.get("OMARCHY_LINK_URL", "http://127.0.0.1:8753"),
                        help="Omarchy Link server base URL")
    parser.add_argument("--id")
    parser.add_argument("--title")
    parser.add_argument("--source", default="omarchy-hermes")
    parser.add_argument("--level", choices=NOTIFICATION_LEVELS, default="info")
    parser.add_argument("--channel")
    parser.add_argument("--timestamp")
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    message = args.message if args.message is not None else sys.stdin.read()
    payload = {
        key: value
        for key, value in {
            "id": args.id,
            "title": args.title,
            "message": message,
            "source": args.source,
            "level": args.level,
            "channel": args.channel,
            "timestamp": args.timestamp,
        }.items()
        if value is not None
    }
    try:
        payload = validate_notification(payload)
    except ValueError as error:
        print(json.dumps({"ok": False, "error": "invalid_request", "detail": str(error)}),
              file=sys.stderr)
        return 2

    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        args.url.rstrip("/") + "/omarchy/notify",
        data=body,
        headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            reply = response.read().decode("utf-8") or "{}"
            print(reply)
            return 0
    except urllib.error.HTTPError as error:
        reply = error.read().decode("utf-8", "replace")
        print(reply or json.dumps({"ok": False, "error": "request_failed",
                                   "status": error.code}), file=sys.stderr)
        return 1
    except (OSError, ValueError) as error:
        print(json.dumps({"ok": False, "error": "server_unavailable",
                          "detail": str(error)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
