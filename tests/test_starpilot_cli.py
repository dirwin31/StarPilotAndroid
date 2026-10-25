import argparse
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("starpilot_cli", Path(__file__).parents[1] / "tools/starpilot.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True, stderr=subprocess.DEVNULL).strip()


def initialize(path, branch):
    path.mkdir()
    git(path, "init", "-q", "-b", branch)
    git(path, "config", "user.name", "Release test")
    git(path, "config", "user.email", "release@example.invalid")


class ReleaseRunner(cli.Runner):
    def __init__(self, app, target, apk):
        self.app, self.target, self.apk = app, target, apk
        self.calls = []
        self.prs = []
        self.missing_split = False
        self.corrupt_download = False
        self.extra_staged_path = False
        self.push_fails = False
        self.ahead = 1
        self.identical_trees = False
        self.digest = "sha256:" + hashlib.sha256(apk).hexdigest()

    def api(self, endpoint):
        self.calls.append(("api", endpoint))
        if "/contents/" in endpoint:
            if self.missing_split:
                raise cli.CommandError("404 Not Found")
            return {"content": base64.b64encode(git(self.app, "show", "direct-v1.2.0:app/build.gradle.kts").encode()).decode()}
        if "/releases/tags/" in endpoint:
            return {"tag_name": "direct-v1.2.0", "draft": False, "prerelease": False,
                    "assets": [{"name": "galaxy-direct.apk", "state": "uploaded",
                                "digest": self.digest, "size": len(self.apk)}]}
        if "/compare/" in endpoint:
            if "direct-v" in endpoint:
                result = subprocess.run(["git", "-C", str(self.app), "merge-base", "--is-ancestor", "direct-v1.2.0", "Direct-Distro"])
                return {"status": "ahead" if result.returncode == 0 else "diverged"}
            return {"ahead_by": self.ahead}
        if "/commits/" in endpoint:
            return {"commit": {"tree": {"sha": "same" if self.identical_trees else endpoint.rsplit("/", 1)[-1]}}}
        return {"permissions": {"push": True}}

    def run(self, *args, cwd=None):
        self.calls.append(args)
        if args[:3] == ("gh", "pr", "list"):
            return json.dumps(self.prs)
        if args[:3] == ("gh", "pr", "create"):
            return "https://github.com/example/StarPilot/pull/123"
        if args[:3] == ("gh", "release", "download"):
            directory = Path(args[args.index("--dir") + 1])
            (directory / "galaxy-direct.apk").write_bytes(b"corrupt" if self.corrupt_download else self.apk)
            return ""
        if args[:2] == ("git", "clone"):
            command = list(args)
            command[-2] = str(self.app if "StarPilotAndroid" in args[-2] else self.target)
            return super().run(*command, cwd=cwd)
        if args == ("git", "config", "user.name"):
            return "Release test"
        if args == ("git", "config", "user.email"):
            return "release@example.invalid"
        if args[:4] == ("git", "diff", "--cached", "--name-only") and self.extra_staged_path:
            return "unrelated.txt"
        if args[0] == "git" and "push" in args:
            if self.push_fails:
                raise cli.CommandError("Push rejected")
            return ""
        return super().run(*args, cwd=cwd)


class UpstreamTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.app = self.directory / "app"
        self.target = self.directory / "upstream"
        initialize(self.app, "Direct-Distro")
        (self.app / "app").mkdir()
        (self.app / "app/build.gradle.kts").write_text('versionCode = 32\nversionName = "1.1.30"\n')
        (self.app / "SYNCED.md").write_text("Android source belongs to its own repo.\n")
        for flavor in ("main", "direct", "play"):
            path = self.app / "app/src" / flavor
            path.mkdir(parents=True)
            (path / "feature.txt").write_text(flavor)
        git(self.app, "add", ".")
        git(self.app, "commit", "-qm", "Initial app")
        self.initial = git(self.app, "rev-parse", "HEAD")
        initialize(self.target, cli.BASE)
        (self.target / "unrelated.txt").write_text("Preserve upstream content\n")
        binary = self.target / cli.APK
        binary.parent.mkdir(parents=True)
        binary.write_bytes(b"old APK")
        (self.target / (cli.APK + ".version")).write_text("1.1.30\n")
        git(self.target, "add", ".")
        git(self.target, "commit", "-qm", "Initial upstream")
        git(self.target, "subtree", "add", "--prefix=" + cli.PREFIX, str(self.app), self.initial, "--squash")
        (self.app / "app/build.gradle.kts").write_text('versionCode = 33\nversionName = "1.2.0"\n')
        (self.app / "app/src/direct/feature.txt").write_text("Direct installer")
        (self.app / "app/src/play/feature.txt").write_text("Play Store updates")
        git(self.app, "add", ".")
        git(self.app, "commit", "-qm", "New release")
        git(self.app, "tag", "direct-v1.2.0")
        self.runner = ReleaseRunner(self.app, self.target, b"PK\x03\x04published signed APK")
        self.args = argparse.Namespace(tag="direct-v1.2.0", target=cli.UPSTREAM, base=cli.BASE,
                                       app_repo=cli.APP_REPO, fork="example/openpilot", dry_run=True,
                                       work_dir=str(self.directory / "prepared"))

    def prepare(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            cli.upstream_pr(self.args, self.runner)
        return output.getvalue()

    def published(self):
        return any(c[:3] == ("gh", "pr", "create") or c[0] == "git" and "push" in c for c in self.runner.calls)

    def test_dry_run_changes_only_apk_and_version_without_importing_private_history(self):
        private = self.app / ".trail/private.md"
        private.parent.mkdir()
        private.write_text("PRIVATE TRAIL SENTINEL")
        git(self.app, "add", ".")
        git(self.app, "commit", "-qm", "Private notes")
        git(self.app, "tag", "-f", "direct-v1.2.0")
        before = git(self.target, "rev-parse", "HEAD")
        self.assertIn("no push or PR", self.prepare())
        prepared = self.directory / "prepared/starpilot"
        self.assertEqual(git(prepared, "rev-parse", "HEAD:" + cli.PREFIX), git(self.target, "rev-parse", "HEAD:" + cli.PREFIX))
        self.assertEqual((prepared / cli.APK).read_bytes(), self.runner.apk)
        self.assertEqual((prepared / (cli.APK + ".version")).read_text(), "1.2.0\n")
        self.assertEqual((prepared / "unrelated.txt").read_text(), "Preserve upstream content\n")
        self.assertEqual(git(self.target, "rev-parse", "HEAD"), before)
        self.assertFalse(self.published())
        self.assertEqual(set(git(prepared, "diff", "--name-only", before, "HEAD").splitlines()), {cli.APK, cli.APK + ".version"})
        self.assertEqual(git(prepared, "rev-list", "--count", before + "..HEAD"), "1")
        self.assertNotIn(".trail", git(prepared, "rev-list", "--objects", "HEAD"))

    def test_publish_pushes_only_release_branch_and_targets_exact_upstream_branch(self):
        self.args.dry_run = False
        self.assertIn("/pull/123", self.prepare())
        push = next(c for c in self.runner.calls if c[0] == "git" and "push" in c)
        self.assertEqual(push[-2:], ("https://github.com/example/openpilot.git", "HEAD:refs/heads/android/direct-v1.2.0"))
        self.assertNotIn("--force", push)
        create = next(c for c in self.runner.calls if c[:3] == ("gh", "pr", "create"))
        self.assertEqual(create[create.index("--repo") + 1], cli.UPSTREAM)
        self.assertEqual(create[create.index("--base") + 1], "SecretGoodStarPilot")
        self.assertEqual(create[create.index("--head") + 1], "example:android/direct-v1.2.0")
        self.assertIn("SHA-256", Path(create[create.index("--body-file") + 1]).read_text())

    def test_existing_pr_is_reused_without_download_or_build(self):
        self.runner.prs = [{"url": "existing", "headRepositoryOwner": {"login": "example"}}]
        self.assertEqual(self.prepare().strip(), "existing")
        self.assertFalse(Path(self.args.work_dir).exists())

    def test_missing_installer_stops_preparation(self):
        git(self.target, "rm", cli.APK)
        git(self.target, "commit", "-qm", "No installer")
        with self.assertRaisesRegex(cli.CommandError, "Galaxy installer"):
            self.prepare()
        self.assertFalse(self.published())

    def test_play_release_cannot_be_shipped_as_direct(self):
        self.args.tag = "play-v1.2.0"
        with self.assertRaisesRegex(cli.CommandError, "Direct release tag"):
            self.prepare()
        self.assertEqual(self.runner.calls, [])

    def test_missing_digest_stops_before_clone(self):
        self.runner.digest = None
        with self.assertRaisesRegex(cli.CommandError, "SHA-256 digest"):
            self.prepare()
        self.assertFalse(Path(self.args.work_dir).exists())

    def test_corrupt_download_never_pushes(self):
        self.runner.corrupt_download = True
        with self.assertRaisesRegex(cli.CommandError, "incomplete"):
            self.prepare()
        self.assertFalse(self.published())

    def test_same_size_apk_with_wrong_digest_is_rejected(self):
        path = self.directory / "corrupt.apk"
        path.write_bytes(b"X" * len(self.runner.apk))
        with self.assertRaisesRegex(cli.CommandError, "release digest"):
            cli.verify_asset(path, {"size": len(self.runner.apk), "digest": self.runner.digest})

    def test_tag_outside_direct_stable_branch_is_rejected(self):
        git(self.app, "update-ref", "refs/heads/Direct-Distro", self.initial)
        with self.assertRaises(cli.CommandError):
            self.prepare()
        self.assertFalse(self.published())

    def test_equal_version_is_not_reissued(self):
        (self.target / (cli.APK + ".version")).write_text("1.2.0\n")
        git(self.target, "add", ".")
        git(self.target, "commit", "-qm", "Already updated")
        with self.assertRaisesRegex(cli.CommandError, "newer Direct version"):
            self.prepare()
        self.assertFalse(self.published())

    def test_unrelated_staged_changes_never_push(self):
        self.runner.extra_staged_path = True
        with self.assertRaisesRegex(cli.CommandError, "outside the APK"):
            self.prepare()
        self.assertFalse(self.published())

    def test_failed_push_does_not_open_a_pr(self):
        self.args.dry_run = False
        self.runner.push_fails = True
        with self.assertRaisesRegex(cli.CommandError, "Push rejected"):
            self.prepare()
        self.assertFalse(any(c[:3] == ("gh", "pr", "create") for c in self.runner.calls))


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.runner = ReleaseRunner(None, None, b"")
        self.args = argparse.Namespace(source="Play-Dev", app_repo=cli.APP_REPO, dry_run=False,
                                       work_dir=str(Path(self.temp.name) / "pr"))

    def sync(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            cli.sync_dev(self.args, self.runner)
        return output.getvalue()

    def test_play_changes_open_pr_into_direct_development(self):
        self.sync()
        create = next(c for c in self.runner.calls if c[:3] == ("gh", "pr", "create"))
        self.assertEqual(create[create.index("--base") + 1], "Direct-Dev")
        self.assertEqual(create[create.index("--head") + 1], "Play-Dev")

    def test_direct_changes_open_pr_into_play_development(self):
        self.args.source = "Direct-Dev"
        self.sync()
        create = next(c for c in self.runner.calls if c[:3] == ("gh", "pr", "create"))
        self.assertEqual(create[create.index("--base") + 1], "Play-Dev")

    def test_up_to_date_destination_does_not_open_pr(self):
        self.runner.ahead = 0
        self.assertIn("already contains", self.sync())
        self.assertFalse(Path(self.args.work_dir).exists())

    def test_dry_run_does_not_open_pr(self):
        self.args.dry_run = True
        self.assertIn("Would open", self.sync())
        self.assertFalse(Path(self.args.work_dir).exists())

    def test_identical_files_do_not_bounce_merge_commits_between_dev_branches(self):
        self.runner.identical_trees = True
        self.assertIn("identical files", self.sync())
        self.assertFalse(Path(self.args.work_dir).exists())

    def test_existing_sync_pr_is_reused(self):
        self.runner.prs = [{"url": "existing", "headRepositoryOwner": {"login": "dirwin31"}}]
        self.assertEqual(self.sync().strip(), "existing")
        self.assertFalse(any(c[0] == "api" for c in self.runner.calls))


if __name__ == "__main__":
    unittest.main()
