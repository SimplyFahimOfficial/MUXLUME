"""Tests for the release gate and the dependency checks (python3 -m unittest discover -s scripts/ci).

The fixtures are the shapes GitHub's API and Gradle print; nothing here calls either.
"""
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import release_gate as gate  # noqa: E402

SHA = "a" * 40
OTHER = "b" * 40
SCAN = gate.SCAN_JOB
VULNS = gate.VULNS_STEP
LICENCES = gate.LICENCES_STEP


def run(id, conclusion="success", event="push", status="completed", started="2026-11-20T10:00:00Z", sha=SHA):
    return {"id": id, "event": event, "status": status, "conclusion": conclusion, "head_sha": sha, "run_started_at": started, "html_url": f"https://x/runs/{id}"}


def job(name, conclusion="success", failed=(), skipped=()):
    steps = [{"name": "Set up job", "conclusion": "success"}]
    steps += [{"name": s, "conclusion": "failure"} for s in failed]
    steps += [{"name": s, "conclusion": "skipped"} for s in skipped]
    return {"name": name, "conclusion": conclusion, "steps": steps}


def scan(conclusion="success", failed=(), skipped=()):
    return job(SCAN, conclusion, failed, skipped)


class ReleaseGateTest(unittest.TestCase):
    def check(self, workflow, runs, jobs=None):
        return gate.evaluate(workflow, runs, SHA, lambda r: (jobs or {}).get(r["id"], []))

    def test_the_full_scan_on_the_exact_commit_satisfies(self):
        self.assertTrue(self.check("security.yml", [run(1)], {1: [scan()]}).ok)

    def test_a_pull_request_success_does_not_count(self):
        # The run is green because the scan job is skipped on pull requests.
        v = self.check("security.yml", [run(1, event="pull_request")], {1: [scan("skipped")]})
        self.assertFalse(v.ok)
        self.assertEqual(v.title, "Release: no check run")
        self.assertFalse(self.check("android-ci.yml", [run(1, event="pull_request")]).ok)

    def test_a_green_run_without_a_successful_scan_job_does_not_count(self):
        v = self.check("security.yml", [run(1)], {1: [scan("skipped")]})
        self.assertEqual(v.title, "Release: dependency scan skipped")
        self.assertEqual(self.check("security.yml", [run(1)], {1: []}).title, "Release: dependency scan skipped")

    def test_the_newest_run_decides_when_an_older_one_passed(self):
        runs = [run(1, started="2026-11-18T10:00:00Z"), run(2, "failure", "schedule", started="2026-11-20T04:17:00Z")]
        v = self.check("security.yml", runs, {1: [scan()], 2: [scan("failure", failed=[VULNS])]})
        self.assertFalse(v.ok)
        self.assertEqual(v.title, "Release: vulnerable dependency")
        self.assertFalse(self.check("android-ci.yml", [run(1), run(2, "failure", "workflow_dispatch", started="2026-11-20T11:00:00Z")]).ok)

    def test_a_newer_green_run_replaces_an_older_failed_one(self):
        runs = [run(1, "failure", started="2026-11-20T10:00:00Z"), run(2, started="2026-11-20T10:30:00Z")]
        self.assertTrue(self.check("security.yml", runs, {2: [scan()]}).ok)

    def test_a_run_that_is_still_going_blocks_even_if_an_older_one_passed(self):
        runs = [run(1, started="2026-11-19T10:00:00Z"), run(2, None, "workflow_dispatch", "in_progress", "2026-11-20T10:00:00Z")]
        v = self.check("security.yml", runs, {1: [scan()]})
        self.assertEqual(v.title, "Release: check still running")

    def test_runs_of_other_commits_do_not_count(self):
        self.assertEqual(self.check("android-ci.yml", [run(1, sha=OTHER)]).title, "Release: no check run")

    def test_a_scan_that_could_not_run_is_told_apart_from_a_finding(self):
        sbom = self.check("security.yml", [run(1, "failure")], {1: [scan("failure", failed=["SBOM of the release APK"], skipped=["Download OSV-Scanner", VULNS])]})
        osv = self.check("security.yml", [run(1, "failure")], {1: [scan("failure", failed=["Scan the SBOM with OSV-Scanner"], skipped=[VULNS])]})
        vuln = self.check("security.yml", [run(1, "failure")], {1: [scan("failure", failed=[VULNS])]})
        lic = self.check("security.yml", [run(1, "failure")], {1: [scan("failure", failed=[LICENCES])]})
        self.assertEqual(sbom.title, "Release: dependency scan infrastructure failure")
        self.assertEqual(osv.title, "Release: dependency scan infrastructure failure")
        self.assertEqual(vuln.title, "Release: vulnerable dependency")
        self.assertEqual(lic.title, "Release: dependency licence")
        self.assertIn("SBOM of the release APK", sbom.message)
        self.assertIn("not a vulnerability finding", sbom.message)
        self.assertNotIn("not a vulnerability finding", vuln.message)
        self.assertFalse(any(v.ok for v in (sbom, osv, vuln, lic)))

    def test_an_outage_wins_over_a_finding_that_may_be_incomplete(self):
        v = self.check("security.yml", [run(1, "failure")], {1: [scan("failure", failed=["SBOM of the release APK", LICENCES])]})
        self.assertEqual(v.title, "Release: dependency scan infrastructure failure")
        self.assertIn("incomplete", v.message)

    def test_a_cancelled_scan_is_not_a_pass(self):
        v = self.check("security.yml", [run(1, "cancelled")], {1: [scan("cancelled")]})
        self.assertEqual(v.title, "Release: dependency scan infrastructure failure")

    def test_other_failures_are_named(self):
        v = self.check("security.yml", [run(1, "failure")], {1: [job("Secret scan (gitleaks)", "failure"), scan()]})
        self.assertEqual(v.title, "Release: check failed")
        self.assertIn("Secret scan (gitleaks)", v.message)

    def test_a_rerun_that_passed_counts_through_the_run_it_belongs_to(self):
        # The API reports the latest attempt of a run: attempt 2 of run 1 went green.
        self.assertTrue(self.check("security.yml", [dict(run(1), run_attempt=2)], {1: [scan()]}).ok)

    def main(self, runs_by_workflow, jobs=None, fail=False):
        def fetch(path):
            if fail:
                raise subprocess.CalledProcessError(1, "gh")
            if "/jobs" in path:
                return {"jobs": (jobs or {}).get(int(path.split("/runs/")[1].split("/")[0]), [])}
            wf = path.split("/workflows/")[1].split("/")[0]
            runs = runs_by_workflow.get(wf, [])
            return {"total_count": len(runs), "workflow_runs": runs}

        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = gate.main(["--repo", "o/r", "--sha", SHA], fetch=fetch)
        return status, out.getvalue()

    def test_the_command_passes_only_when_all_three_workflows_do(self):
        good = {"android-ci.yml": [run(1)], "security.yml": [run(2)], "codeql.yml": [run(3)]}
        self.assertEqual(self.main(good, {2: [scan()]})[0], 0)
        status, text = self.main(dict(good, **{"codeql.yml": []}), {2: [scan()]})
        self.assertEqual(status, 1)
        self.assertIn("codeql.yml", text)

    def test_the_command_reports_every_blocked_workflow(self):
        status, text = self.main({"android-ci.yml": [], "security.yml": [], "codeql.yml": []})
        self.assertEqual(status, 1)
        self.assertEqual(text.count("::error title=Release: no check run"), 3)

    def test_an_unreadable_api_is_not_reported_as_a_failed_check(self):
        status, text = self.main({}, fail=True)
        self.assertEqual(status, 2)
        self.assertIn("could not read the checks", text)

    def test_more_runs_than_one_page_is_an_error_not_a_guess(self):
        def fetch(path):
            return {"total_count": 150, "workflow_runs": [run(1)]}
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(gate.main(["--repo", "o/r", "--sha", SHA], fetch=fetch), 2)


# A trimmed `gradle :app:dependencies --configuration fdroidReleaseRuntimeClasspath` report.
CLEAN = """
------------------------------------------------------------
Project ':app'
------------------------------------------------------------

fdroidReleaseRuntimeClasspath - Runtime classpath of '/fdroidRelease'.
+--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21
|    +--- org.jetbrains:annotations:13.0 -> 23.0.0
|    \\--- org.jetbrains.kotlin:kotlin-stdlib-common:2.4.21 (c)
+--- androidx.core:core-ktx:1.19.1
|    \\--- androidx.core:core:1.19.1
+--- com.google.zxing:core:3.5.4
\\--- com.google.guava:guava:33.8.0-android (c)

(c) - A dependency constraint, not a dependency. The dependency affected by the constraint occurs elsewhere in the tree.
(*) - Indicates repeated occurrences of a transitive dependency subtree.

A web-based, searchable dependency report is available by adding the --scan option.
"""
UNRESOLVED = CLEAN.replace("+--- com.google.zxing:core:3.5.4", "+--- com.example.missing:lib:1.0 FAILED\n+--- com.google.zxing:core:3.5.4")
PROPRIETARY = CLEAN.replace("+--- com.google.zxing:core:3.5.4", "+--- com.google.zxing:core:3.5.4\n|    \\--- com.google.android.gms:play-services-base:18.0.0")
GRADLE_ERROR = "FAILURE: Build failed with an exception.\n* What went wrong:\nConfiguration with name 'fdroidReleaseRuntimeClasspath' not found.\n"


class DepsCheckTest(unittest.TestCase):
    def tool(self, *args):
        p = subprocess.run([sys.executable, os.path.join(HERE, "deps_check.py"), *args], capture_output=True, text=True)
        return p.returncode, p.stdout + p.stderr

    def fdroid(self, text):
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as fh:
            fh.write(text)
        try:
            return self.tool("fdroid", "--report", fh.name)
        finally:
            os.unlink(fh.name)

    def test_a_clean_resolved_graph_passes(self):
        self.assertEqual(self.fdroid(CLEAN)[0], 0)

    def test_a_prohibited_dependency_fails_as_a_finding(self):
        code, out = self.fdroid(PROPRIETARY)
        self.assertEqual(code, 1)
        self.assertIn("proprietary dependency", out)

    def test_an_unresolved_dependency_fails_as_an_incomplete_graph(self):
        code, out = self.fdroid(UNRESOLVED)
        self.assertEqual(code, 2)
        self.assertIn("incomplete", out)
        self.assertNotIn("proprietary dependency::", out)

    def test_an_empty_report_or_a_gradle_error_is_incomplete_not_clean(self):
        self.assertEqual(self.fdroid("")[0], 2)
        self.assertEqual(self.fdroid(GRADLE_ERROR)[0], 2)
        self.assertEqual(self.tool("fdroid", "--report", os.path.join(HERE, "no-such-report.txt"))[0], 2)

    def test_a_prohibited_dependency_is_reported_even_if_the_graph_is_also_incomplete(self):
        self.assertEqual(self.fdroid(PROPRIETARY.replace("+--- androidx", "+--- x.y:z:1 FAILED\n+--- androidx"))[0], 1)

    def test_a_mention_outside_the_tree_is_not_a_dependency(self):
        self.assertEqual(self.fdroid(CLEAN + "\nNote: com.google.firebase is not used.\n")[0], 0)

    def test_a_missing_sbom_or_scan_report_is_not_a_finding(self):
        missing = os.path.join(HERE, "no-such-file.json")
        for args in (("licenses", "--sbom", missing), ("vulns", "--osv", missing)):
            code, out = self.tool(*args)
            self.assertEqual(code, 2, out)
            self.assertNotIn("Traceback", out)
            self.assertIn("not a finding", out)

    def test_a_high_vulnerability_is_a_finding(self):
        report = {"results": [{"packages": [{"package": {"name": "a:b", "version": "1"}, "groups": [{"ids": ["GHSA-x"], "max_severity": "8.1"}]}]}]}
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump(report, fh)
        try:
            self.assertEqual(self.tool("vulns", "--osv", fh.name, "--fail-at", "high")[0], 1)
        finally:
            os.unlink(fh.name)


if __name__ == "__main__":
    unittest.main()
