#!/usr/bin/env python3
"""Decide whether the checks a release needs passed on the exact commit being released.

  release_gate.py --repo owner/name --sha <commit>

For each of android-ci.yml, security.yml and codeql.yml, the newest run on that commit started by a push,
a schedule or a manual start decides. Not counted:

  - pull request runs: they check the merge commit of a pull request, not the commit itself, and
    security.yml skips the shipped-dependency scan there;
  - an older green run when a newer run on the same commit failed, is still running, or was cancelled
    (a weekly scan that finds a new advisory in an unchanged commit must block it);
  - a green run whose shipped-dependency job was skipped or missing.

A failed scan says which kind of failure it was, so that nobody "fixes" an outage by changing
dependencies, or re-runs a finding: the scan did not complete (infrastructure: SBOM or scanner
did not run), or it completed and found something (a vulnerability, a licence).

Exit status: 0 satisfied, 1 blocked, 2 the runs could not be read.
"""
import argparse
import json
import os
import subprocess
import sys

# Events that run a workflow on exactly the commit. pull_request is deliberately absent.
EVENTS = {"push", "schedule", "workflow_dispatch"}

WORKFLOWS = ["android-ci.yml", "security.yml", "codeql.yml"]

# security.yml: the job that must have run and passed, and the steps whose failure is a finding.
# Any other failed step (checkout, Java, Gradle, SBOM, downloading or running OSV-Scanner) means
# the scan did not complete. Keep these names in step with security.yml.
SCAN_JOB = "Shipped dependencies (OSV, licences)"
VULNS_STEP = "Known vulnerabilities (fails at high and critical)"
LICENCES_STEP = "Licences"
REQUIRED_JOBS = {"security.yml": [SCAN_JOB]}


class Verdict:
    def __init__(self, ok, title="", message=""):
        self.ok, self.title, self.message = ok, title, message

    def __repr__(self):
        return f"Verdict(ok={self.ok}, title={self.title!r})"


def newest_applicable(runs, sha):
    """The run that decides: the latest start among runs of this exact commit from an applicable event."""
    mine = [r for r in runs if r.get("head_sha") == sha and r.get("event") in EVENTS]
    return max(mine, key=lambda r: (r.get("run_started_at") or r.get("created_at") or "", r.get("id", 0)), default=None)


def failed_steps(job):
    return [s["name"] for s in job.get("steps", []) if s.get("conclusion") not in ("success", "skipped", None)]


def scan_failure(job, run):
    """Say what kind of failure a non-green dependency scan job was."""
    where = run.get("html_url", "")
    conclusion = job.get("conclusion")
    if conclusion == "skipped":
        return Verdict(False, "Release: dependency scan skipped", f"the shipped-dependency job did not run ({where}).")
    steps = failed_steps(job)
    findings = {VULNS_STEP, LICENCES_STEP}
    broken = [s for s in steps if s not in findings]
    if broken or not steps:
        extra = " Findings from the steps that did run may be incomplete." if set(steps) & findings else ""
        return Verdict(
            False, "Release: dependency scan infrastructure failure",
            f"the dependency scan did not complete (step: {', '.join(broken) or 'unknown'}). This is not a vulnerability finding: "
            f"re-run the failed jobs of {where}, or fix the scan.{extra}",
        )
    if VULNS_STEP in steps:
        return Verdict(False, "Release: vulnerable dependency", f"OSV-Scanner reported high or critical vulnerabilities in the shipped dependencies ({where}). Upgrade them.")
    return Verdict(False, "Release: dependency licence", f"a shipped dependency has a licence that is not allowed or not reviewed ({where}).")


def evaluate(workflow, runs, sha, jobs_of):
    """[runs]: the workflow runs listed for the commit; [jobs_of](run): the jobs (with steps) of that run's latest attempt."""
    run = newest_applicable(runs, sha)
    if run is None:
        return Verdict(
            False, "Release: no check run",
            f"no {workflow} run on {sha} was started by a push, a schedule or a manual start. Pull request runs do not count. "
            "Wait for the checks on main, then re-run.",
        )
    if run.get("status") != "completed":
        return Verdict(False, "Release: check still running", f"the newest {workflow} run on {sha} is {run.get('status')} ({run.get('html_url', '')}).")
    required = REQUIRED_JOBS.get(workflow, [])
    if run.get("conclusion") != "success":
        jobs = jobs_of(run)
        scan = next((j for j in jobs if j.get("name") == SCAN_JOB), None)
        if scan is not None and scan.get("conclusion") != "success":
            return scan_failure(scan, run)
        bad = [j["name"] for j in jobs if j.get("conclusion") not in ("success", "skipped")]
        return Verdict(
            False, "Release: check failed",
            f"the newest {workflow} run on {sha} ended {run.get('conclusion')} ({run.get('html_url', '')}); failed: {', '.join(bad) or 'unknown'}. "
            "An older green run does not count.",
        )
    if required:
        jobs = jobs_of(run)
        for name in required:
            job = next((j for j in jobs if j.get("name") == name), None)
            if job is None or job.get("conclusion") != "success":
                return Verdict(False, "Release: dependency scan skipped", f"the {workflow} run succeeded without a successful '{name}' job ({run.get('html_url', '')}).")
    return Verdict(True, message=f"{workflow}: {run.get('html_url', '')}")


def gh_api(path):
    out = subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def main(argv=None, fetch=gh_api):
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--sha", required=True)
    a = ap.parse_args(argv)
    blocked = []
    for wf in WORKFLOWS:
        try:
            listing = fetch(f"repos/{a.repo}/actions/workflows/{wf}/runs?head_sha={a.sha}&per_page=100")
            runs = listing.get("workflow_runs", [])
            if listing.get("total_count", len(runs)) > len(runs):
                raise ValueError(f"{listing['total_count']} runs, more than one page")
            verdict = evaluate(wf, runs, a.sha, lambda r: fetch(f"repos/{a.repo}/actions/runs/{r['id']}/jobs?per_page=100").get("jobs", []))
        except (subprocess.CalledProcessError, ValueError, KeyError, OSError) as e:
            print(f"::error title=Release: could not read the checks::{wf}: {e.__class__.__name__}. Re-run when the GitHub API answers.")
            return 2
        if verdict.ok:
            print(f"ok   {verdict.message}")
        else:
            print(f"::error title={verdict.title}::{verdict.message}")
            blocked.append(f"- {verdict.title}: {verdict.message}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary and blocked:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("## Release blocked\n\n" + "\n".join(blocked) + "\n")
    return 1 if blocked else 0


if __name__ == "__main__":
    sys.exit(main())
