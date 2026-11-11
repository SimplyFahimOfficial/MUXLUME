#!/usr/bin/env python3
"""Security and release audit of a built APK, from the APK itself (the merged manifest).

Checks what the app actually ships, after manifest merging with every library:
  - package name, and optionally the expected versionName / versionCode;
  - not debuggable, not testOnly, minSdk floor;
  - the permission set matches the reviewed allowlist (a new permission fails until reviewed);
  - every exported component is either a launcher entry point or protected by a permission;
  - no user-installed CAs in the network security config; cleartext only as the reviewed
    exception below (IPTV hosts are user-defined; the app gates HTTP itself, see docs/SECURITY.md);
  - backups enabled only together with explicit backup and data extraction rules.

Prints a short report; with GITHUB_STEP_SUMMARY set, also writes it to the job summary.
Exit code 1 on any violation. Uses only aapt2 from the Android build tools.

  apk_audit.py --apk app.apk --package com.muxlume.app [--version-name 1.2.0] [--version-code 12]
"""
import argparse
import os
import re
import subprocess
import sys

# Reviewed permissions (see docs/SECURITY.md, "Permissions"). Adding one to the app, directly or
# through a library, fails this check until it is reviewed and added here.
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",  # user-configured playlists, provider APIs, artwork and streams
    "android.permission.ACCESS_NETWORK_STATE",  # merged from Media3 (network-type aware bandwidth estimation)
    "android.permission.FOREGROUND_SERVICE",  # media playback service (background audio, notification)
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",  # foreground service type for the above (API 34+)
    "android.permission.WAKE_LOCK",  # CPU/Wi-Fi awake only while playing (ExoPlayer wake mode)
}
# Reviewed exception (docs/SECURITY.md, "Cleartext HTTP"): the platform must permit cleartext because
# IPTV servers are user-defined at runtime; core/net/Http.kt refuses HTTP until the user opts in.
CLEARTEXT_REVIEWED = True
# Signature-level permission AndroidX defines for the app's own non-exported dynamic receivers.
OWN_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

MIN_SDK_FLOOR = 26
ANDROID_NS = "http://schemas.android.com/apk/res/android:"


def aapt2(build_tools, *args):
    return subprocess.run([os.path.join(build_tools, "aapt2"), "dump", *args], check=True, capture_output=True, text=True).stdout


def parse_manifest(tree):
    """Elements of the merged manifest as dicts: kind, depth, attrs, children (flat tree walk)."""
    root = {"kind": "root", "depth": -1, "attrs": {}, "children": []}
    stack = [root]
    for line in tree.splitlines():
        m = re.match(r"^(\s*)E: ([\w-]+)", line)
        if m:
            depth = len(m.group(1))
            while stack[-1]["depth"] >= depth:
                stack.pop()
            el = {"kind": m.group(2), "depth": depth, "attrs": {}, "children": []}
            stack[-1]["children"].append(el)
            stack.append(el)
            continue
        a = re.match(r"^\s*A: (\S+?)(?:\(0x[0-9a-f]+\))?=(.*)$", line)
        if a and len(stack) > 1:
            name = a.group(1).replace(ANDROID_NS, "")
            raw = a.group(2)
            q = re.match(r'^"([^"]*)"', raw)
            stack[-1]["attrs"][name] = q.group(1) if q else raw.split(" ")[0]
    return root


def walk(el):
    yield el
    for c in el["children"]:
        yield from walk(c)


def is_true(v):
    return v is not None and v.lower() in ("true", "0xffffffff", "-1")


def resource_paths(resources):
    """Resource id → file path in the APK (resource paths are shortened in release builds)."""
    out = {}
    cur = None
    for line in resources.splitlines():
        r = re.match(r"^\s*resource (0x[0-9a-f]+) (\S+)", line)
        if r:
            cur = r.group(1)
            continue
        f = re.match(r"^\s*\(\) \(file\) (\S+)", line)
        if f and cur:
            out[cur] = f.group(1)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--package", required=True)
    ap.add_argument("--version-name")
    ap.add_argument("--version-code", type=int)
    ap.add_argument("--build-tools", default=os.environ.get("BUILD_TOOLS", ""))
    a = ap.parse_args()
    bt = a.build_tools
    problems, notes = [], []

    badging = aapt2(bt, "badging", a.apk)
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    name, vcode, vname = pkg.group(1), int(pkg.group(2)), pkg.group(3)
    if name != a.package:
        problems.append(f"package is {name}, expected {a.package}")
    if a.version_name is not None and vname != a.version_name:
        problems.append(f"versionName is {vname}, expected {a.version_name}")
    if a.version_code is not None and vcode != a.version_code:
        problems.append(f"versionCode is {vcode}, expected {a.version_code}")
    min_sdk = int(re.search(r"minSdkVersion:'(\d+)'", badging).group(1))
    target_sdk = int(re.search(r"targetSdkVersion:'(\d+)'", badging).group(1))
    if min_sdk < MIN_SDK_FLOOR:
        problems.append(f"minSdk {min_sdk} is below {MIN_SDK_FLOOR}")
    if "application-debuggable" in badging:
        problems.append("the APK is debuggable")
    abis = re.search(r"native-code: (.*)", badging)
    notes.append(f"{name} {vname} ({vcode}), minSdk {min_sdk}, targetSdk {target_sdk}")
    notes.append("native ABIs: " + (abis.group(1).replace("'", "") if abis else "none (no native code)"))

    perms = set(re.findall(r"uses-permission: name='([^']+)'", badging))
    unexpected = sorted(p for p in perms if p not in ALLOWED_PERMISSIONS and not (p.startswith(name) and p.endswith(OWN_PERMISSION_SUFFIX)))
    if unexpected:
        problems.append("permissions not in the reviewed list (scripts/ci/apk_audit.py, docs/SECURITY.md): " + ", ".join(unexpected))
    notes.append(f"permissions: {len(perms)} ({', '.join(sorted(p.rsplit('.', 1)[-1] for p in perms))})")

    root = parse_manifest(aapt2(bt, "xmltree", a.apk, "--file", "AndroidManifest.xml"))
    app = next((e for e in walk(root) if e["kind"] == "application"), None)
    if app is None:
        problems.append("no <application> element")
        app = {"attrs": {}, "children": []}
    attrs = app["attrs"]
    if is_true(attrs.get("debuggable")):
        problems.append("android:debuggable=true")
    if is_true(attrs.get("testOnly")):
        problems.append("android:testOnly=true")
    if is_true(attrs.get("usesCleartextTraffic")):
        problems.append("android:usesCleartextTraffic=true (use the network security config, see docs/SECURITY.md)")
    if is_true(attrs.get("allowBackup")) or "allowBackup" not in attrs:
        if "dataExtractionRules" not in attrs or "fullBackupContent" not in attrs:
            problems.append("backups are enabled without both dataExtractionRules and fullBackupContent rules")
        else:
            notes.append("backups: enabled, limited by dataExtractionRules and fullBackupContent")

    paths = resource_paths(aapt2(bt, "resources", a.apk))
    nsc = attrs.get("networkSecurityConfig", "").lstrip("@")
    if not nsc:
        problems.append("no networkSecurityConfig")
    else:
        path = paths.get(nsc)
        xml = aapt2(bt, "xmltree", a.apk, "--file", path) if path else ""
        before = len(problems)
        if not xml:
            problems.append("network security config resource not found in the APK")
        # aapt2 prints "A: cleartextTrafficPermitted=true", with or without a resource id.
        cleartext = bool(re.search(r"cleartextTrafficPermitted(?:\(0x[0-9a-f]+\))?=(?:true|\(type 0x12\)0xffffffff)", xml))
        if cleartext and not CLEARTEXT_REVIEWED:
            problems.append("network security config permits cleartext traffic")
        if re.search(r'src[^=]*="user"', xml):
            problems.append("network security config trusts user-installed CAs")
        if len(problems) == before:
            notes.append("network security config: no user CAs; cleartext " + ("permitted (reviewed exception, gated in-app)" if cleartext else "off"))

    exported = []
    for el in walk(app):
        if el["kind"] not in ("activity", "activity-alias", "service", "receiver", "provider"):
            continue
        ex = el["attrs"].get("exported")
        has_filter = any(c["kind"] == "intent-filter" for c in el["children"])
        if not (is_true(ex) or (ex is None and has_filter)):
            continue
        perm = el["attrs"].get("permission") or (el["attrs"].get("readPermission") if el["kind"] == "provider" else None)
        launcher = el["kind"] in ("activity", "activity-alias") and any(
            c["kind"] == "intent-filter" and any(g["kind"] == "category" and g["attrs"].get("name") == "android.intent.category.LAUNCHER" for g in c["children"])
            for c in el["children"]
        )
        label = f"{el['kind']} {el['attrs'].get('name')}"
        if perm:
            exported.append(f"{label} (permission {perm})")
        elif launcher:
            exported.append(f"{label} (launcher)")
        else:
            problems.append(f"exported without a permission and not a launcher entry point: {label}")
    notes.append(f"exported components: {len(exported)}")
    notes.extend("  " + e for e in exported)

    report = ["## APK audit", ""] + [f"- {n}" for n in notes if n]
    report += ["", "**Result: PASS**" if not problems else "**Result: FAIL**"] + [f"- {p}" for p in problems]
    text = "\n".join(report)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(text + "\n")
    for p in problems:
        print(f"::error title=APK audit::{p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
