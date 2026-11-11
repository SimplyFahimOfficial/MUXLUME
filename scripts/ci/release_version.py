#!/usr/bin/env python3
"""Check a release tag against the version in app/build.gradle.kts.

  release_version.py <tag> [--previous-build-file path]

- The tag must be vMAJOR.MINOR.PATCH, optionally with a pre-release suffix (v1.2.0-rc.1).
- versionName in app/build.gradle.kts must equal the tag without the "v".
- versionCode must be a positive integer and, when the previous release's build file is given,
  strictly greater than its versionCode (Android refuses to update to a lower or equal code).

Writes version_name, version_code and prerelease to GITHUB_OUTPUT when set.
"""
import argparse
import os
import re
import sys

TAG = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?$")


def read_version(path):
    text = open(path, encoding="utf-8").read()
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.M)
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)", text, re.M)
    if not name or not code:
        raise SystemExit(f"::error title=Release version::{path} has no literal versionName/versionCode; this check needs them.")
    return name.group(1), int(code.group(1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tag")
    ap.add_argument("--build-file", default="app/build.gradle.kts")
    ap.add_argument("--previous-build-file")
    a = ap.parse_args()
    errors = []
    m = TAG.match(a.tag)
    if not m:
        errors.append(f"tag '{a.tag}' is not vMAJOR.MINOR.PATCH[-prerelease]")
    name, code = read_version(a.build_file)
    if m and name != a.tag[1:]:
        errors.append(f"versionName is '{name}' but the tag is '{a.tag}'; bump versionName in {a.build_file} before tagging")
    if code < 1:
        errors.append("versionCode must be positive")
    if a.previous_build_file and os.path.exists(a.previous_build_file):
        prev_name, prev_code = read_version(a.previous_build_file)
        if code <= prev_code:
            errors.append(f"versionCode {code} is not greater than the previous release's {prev_code} ({prev_name})")
    for e in errors:
        print(f"::error title=Release version::{e}")
    if errors:
        return 1
    prerelease = "true" if m.group(4) else "false"
    print(f"Release {name} (versionCode {code}), prerelease={prerelease}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as fh:
            fh.write(f"version_name={name}\nversion_code={code}\nprerelease={prerelease}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
