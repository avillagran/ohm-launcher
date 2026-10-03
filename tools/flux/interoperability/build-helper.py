#!/usr/bin/env python3
"""Build the interoperability peer against an explicit Flux source checkout.

Copies only Go sources into a temporary module; does not alter the checkout,
start a daemon, or load runtime identities. Requires Go from --go or PATH.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--flux-repo", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--go", default="go")
parser.add_argument("--goarch", default="amd64")
args = parser.parse_args()
repo = args.flux_repo.resolve(strict=True)
helper = Path(__file__).with_name("helper.go")
if not helper.is_file() or not (repo / "go.mod").is_file():
    parser.error("helper.go and a Flux Go module are required")
args.output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="ohm-flux-interop-build-") as temporary:
    stage = Path(temporary)
    for name in ("go.mod", "go.sum"):
        shutil.copy2(repo / name, stage / name)
    for name in ("cmd", "internal"):
        shutil.copytree(repo / name, stage / name)
    target = stage / "cmd" / "ohm-flux-interop-helper"
    target.mkdir(exist_ok=True)
    shutil.copy2(helper, target / "main.go")
    environment = dict(os.environ, CGO_ENABLED="0", GOOS="linux", GOARCH=args.goarch)
    subprocess.run(
        [args.go, "build", "-trimpath", "-ldflags", "-s -w", "-o", str(args.output.resolve()), "./cmd/ohm-flux-interop-helper"],
        cwd=stage, env=environment, check=True,
    )
