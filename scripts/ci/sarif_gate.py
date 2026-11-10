#!/usr/bin/env python3
"""Fail on CodeQL findings at or above a security severity, report the rest.

GitHub's code scanning shows the uploaded SARIF, but uploading needs code scanning to be
available on the repository (public, or GitHub Code Security on private repos). This gate works
either way: it reads the SARIF the analysis produced and decides from it.

Severity follows GitHub's mapping of CodeQL's security-severity score:
critical >= 9.0, high >= 7.0, medium >= 4.0, low > 0. Non-security problems with level "error"
count as high.

  sarif_gate.py <file.sarif> [--fail-at high]
"""
import argparse
import json
import os
import sys

LEVELS = ["low", "medium", "high", "critical"]


def severity(score, level):
    if score is None:
        return "high" if level == "error" else ("medium" if level == "warning" else "low")
    s = float(score)
    return "critical" if s >= 9 else "high" if s >= 7 else "medium" if s >= 4 else "low"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sarif")
    ap.add_argument("--fail-at", default="high", choices=LEVELS)
    a = ap.parse_args()
    data = json.load(open(a.sarif, encoding="utf-8"))
    rows = []
    for run in data.get("runs", []):
        rules = {}
        driver = run["tool"]["driver"]
        for r in driver.get("rules", []) + [r for e in run["tool"].get("extensions", []) for r in e.get("rules", [])]:
            rules[r["id"]] = r
        for res in run.get("results", []):
            if res.get("suppressions"):
                continue
            rule = rules.get(res.get("ruleId"), {})
            props = rule.get("properties", {})
            level = res.get("level") or rule.get("defaultConfiguration", {}).get("level", "warning")
            sev = severity(props.get("security-severity"), level)
            loc = res.get("locations", [{}])[0].get("physicalLocation", {})
            where = f"{loc.get('artifactLocation', {}).get('uri', '?')}:{loc.get('region', {}).get('startLine', '?')}"
            rows.append((sev, res.get("ruleId"), where, res.get("message", {}).get("text", "").split("\n")[0][:160]))
    rows.sort(key=lambda r: -LEVELS.index(r[0]))
    blocking = [r for r in rows if LEVELS.index(r[0]) >= LEVELS.index(a.fail_at)]
    lines = ["## CodeQL results", "", f"{len(rows)} finding(s); blocking at {a.fail_at} and above: {len(blocking)}", ""]
    if rows:
        lines += ["| Severity | Rule | Location | Message |", "| --- | --- | --- | --- |"]
        lines += [f"| {s} | `{r}` | `{w}` | {m.replace('|', '/')} |" for s, r, w, m in rows]
    text = "\n".join(lines)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write(text + "\n")
    for s, r, w, m in blocking:
        path, _, line = w.rpartition(":")
        print(f"::error file={path},line={line},title=CodeQL {s}: {r}::{m}")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
