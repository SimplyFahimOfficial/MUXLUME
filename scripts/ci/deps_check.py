#!/usr/bin/env python3
"""Dependency checks on the CycloneDX SBOM of the release APK (what actually ships).

  deps_check.py vulns  --osv osv.json [--only changed.txt] [--fail-at high]
      Read OSV-Scanner JSON output; fail on vulnerabilities at or above --fail-at
      (CVSS: critical >= 9, high >= 7, medium >= 4). With --only, only packages listed in that file
      (purls, one per line: the ones a pull request adds or changes) can block; the rest is reported.

  deps_check.py licenses --sbom bom.json [--only changed.txt]
      Fail if a component's licence is not in the allowed list (or none is declared).

  deps_check.py diff --base base.json --head head.json --out changed.txt
      Compare two SBOMs; write the purls added or changed by head; summarise added, changed and
      removed components.

Every subcommand prints a Markdown report and appends it to GITHUB_STEP_SUMMARY when set.
"""
import argparse
import json
import os
import sys

LEVELS = ["unknown", "low", "medium", "high", "critical"]

# FLOSS licences acceptable for the shipped app (and for F-Droid). A new licence fails until reviewed.
ALLOWED_LICENSES = {"Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "EPL-1.0", "EPL-2.0", "MPL-2.0", "CC0-1.0", "Unicode-3.0", "Unicode-DFS-2016"}
# Reviewed exceptions: licence *names* (no SPDX id) for specific groups. Only the Play in-app update library
# and the Google Play services pieces it needs, which are in the `play` build alone: the `fdroid` build must
# stay free of them, and android-ci.yml checks that on fdroidReleaseRuntimeClasspath. Anything else without an
# SPDX id needs a review.
REVIEWED_NAMED = {
    "Android Software Development Kit License": ["com.google.android.gms"],
    "Play Core Software Development Kit Terms of Service": ["com.google.android.play"],
}


def report(lines):
    text = "\n".join(lines)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write(text + "\n\n")


def read_only(path):
    if not path:
        return None
    with open(path, encoding="utf-8") as fh:
        return {l.strip() for l in fh if l.strip()}


def level(score):
    try:
        s = float(score)
    except (TypeError, ValueError):
        return "unknown"
    return "critical" if s >= 9 else "high" if s >= 7 else "medium" if s >= 4 else "low"


def purl_key(purl):
    """pkg:maven/group/name@version?type=jar → pkg:maven/group/name@version"""
    return purl.split("?")[0]


def cmd_vulns(a):
    data = json.load(open(a.osv, encoding="utf-8"))
    only = read_only(a.only)
    rows = []
    for res in data.get("results", []):
        for p in res.get("packages", []):
            pk = p["package"]
            name, ver = pk["name"], pk["version"]
            key = f"pkg:maven/{name.replace(':', '/')}@{ver}"
            for g in p.get("groups", []):
                sev = level(g.get("max_severity"))
                rows.append((sev, name, ver, ", ".join(g.get("aliases") or g.get("ids", [])), only is None or key in only))
    rows.sort(key=lambda r: -LEVELS.index(r[0]))
    blocking = [r for r in rows if r[4] and LEVELS.index(r[0]) >= LEVELS.index(a.fail_at)]
    lines = ["## Known vulnerabilities in shipped dependencies", "", f"{len(rows)} vulnerability group(s); blocking ({a.fail_at} and above"
             + (", in added or changed dependencies" if only is not None else "") + f"): {len(blocking)}", ""]
    if rows:
        lines += ["| Severity | Package | Version | Advisories | Can block |", "| --- | --- | --- | --- | --- |"]
        lines += [f"| {s} | `{n}` | {v} | {ids} | {'yes' if b else 'no (unchanged)'} |" for s, n, v, ids, b in rows]
    report(lines)
    for s, n, v, ids, _ in blocking:
        print(f"::error title=Vulnerable dependency ({s})::{n}:{v} — {ids}. Upgrade it, or document a reviewed exception in docs/SECURITY.md.")
    return 1 if blocking else 0


def licenses_of(comp):
    out = []
    for l in comp.get("licenses") or []:
        lic = l.get("license") or {}
        out.append(lic.get("id") or lic.get("name") or l.get("expression") or "?")
    return out


def allowed(group, lic):
    if lic in ALLOWED_LICENSES:
        return True
    groups = REVIEWED_NAMED.get(lic)
    return bool(groups) and any(group == g or group.startswith(g + ".") for g in groups)


def cmd_licenses(a):
    bom = json.load(open(a.sbom, encoding="utf-8"))
    only = read_only(a.only)
    bad, counts = [], {}
    for c in bom.get("components", []):
        lics = licenses_of(c)
        for l in lics or ["(none declared)"]:
            counts[l] = counts.get(l, 0) + 1
        if only is not None and purl_key(c.get("purl", "")) not in only:
            continue
        # One allowed licence is enough for dual-licensed components.
        if not lics or not any(allowed(c.get("group", ""), l) for l in lics):
            bad.append((c.get("purl", c.get("name")), ", ".join(lics) or "(none declared)"))
    lines = ["## Dependency licences", "", "| Licence | Components |", "| --- | --- |"]
    lines += [f"| {k} | {v} |" for k, v in sorted(counts.items(), key=lambda kv: -kv[1])]
    lines += ["", f"Not allowed or not reviewed{' (added or changed only)' if only is not None else ''}: {len(bad)}"]
    lines += [f"- `{p}`: {l}" for p, l in bad]
    report(lines)
    for p, l in bad:
        print(f"::error title=Dependency licence needs review::{p} is licensed {l}. Allowed: scripts/ci/deps_check.py ALLOWED_LICENSES.")
    return 1 if bad else 0


def components(path):
    bom = json.load(open(path, encoding="utf-8"))
    out = {}
    for c in bom.get("components", []):
        purl = purl_key(c.get("purl", ""))
        if not purl:
            continue
        base = purl.rsplit("@", 1)[0]
        out[base] = purl
    return out


def cmd_diff(a):
    base, head = components(a.base), components(a.head)
    added = sorted(head[k] for k in head.keys() - base.keys())
    removed = sorted(base[k] for k in base.keys() - head.keys())
    changed = sorted((base[k], head[k]) for k in head.keys() & base.keys() if base[k] != head[k])
    with open(a.out, "w", encoding="utf-8") as fh:
        for p in added + [h for _, h in changed]:
            fh.write(p + "\n")
    lines = ["## Dependency changes in this pull request (shipped in the APK)", ""]
    if not (added or removed or changed):
        lines.append("No change to the release runtime dependencies.")
    lines += [f"- added `{p}`" for p in added]
    lines += [f"- changed `{b}` → `{h.rsplit('@', 1)[1]}`" for b, h in changed]
    lines += [f"- removed `{p}`" for p in removed]
    report(lines)
    return 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("vulns")
    v.add_argument("--osv", required=True)
    v.add_argument("--only")
    v.add_argument("--fail-at", default="high", choices=LEVELS[1:])
    l = sub.add_parser("licenses")
    l.add_argument("--sbom", required=True)
    l.add_argument("--only")
    d = sub.add_parser("diff")
    d.add_argument("--base", required=True)
    d.add_argument("--head", required=True)
    d.add_argument("--out", required=True)
    a = ap.parse_args()
    return {"vulns": cmd_vulns, "licenses": cmd_licenses, "diff": cmd_diff}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
