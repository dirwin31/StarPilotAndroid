#!/usr/bin/env python3
"""Prepare Android release and development pull requests."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import quote

APP_REPO = "dirwin31/StarPilotAndroid"
UPSTREAM = "firestar5683/StarPilot"
BASE = "SecretGoodStarPilot"
PREFIX = "openpilot/starpilot/companion/android"
APK = "openpilot/starpilot/galaxy/bin/starpilot-companion.apk"
DEV_BRANCHES = ("Play-Dev", "Direct-Dev")
ROOT = Path(__file__).resolve().parents[1]


class CommandError(Exception):
    pass


class Runner:
    def run(self, *args, cwd=None):
        env = dict(os.environ, GH_PROMPT_DISABLED="1", GIT_TERMINAL_PROMPT="0", GIT_LFS_SKIP_SMUDGE="1")
        result = subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True)
        if result.returncode:
            raise CommandError(result.stderr.strip() or result.stdout.strip() or f"{args[0]} failed")
        return result.stdout.strip()

    def api(self, endpoint):
        return json.loads(self.run("gh", "api", endpoint))


def repository(value):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", value):
        raise argparse.ArgumentTypeError("Use OWNER/REPO")
    return value


def version(text):
    name = re.search(r'versionName\s*=\s*"([0-9]+(?:\.[0-9]+){1,3})"', text)
    code = re.search(r"versionCode\s*=\s*([0-9]+)", text)
    if not name or not code:
        raise CommandError("Cannot read the Android release version")
    return name[1], int(code[1])


def existing_pr(runner, repo, base, branch, owner):
    prs = json.loads(runner.run("gh", "pr", "list", "--repo", repo, "--base", base,
                                "--head", branch, "--state", "open", "--json", "url,headRepositoryOwner"))
    return next((pr["url"] for pr in prs if pr["headRepositoryOwner"]["login"] == owner), None)


def open_pr(runner, repo, base, head, title, body, directory):
    body_file = directory / "pr-body.md"
    body_file.write_text(body)
    return runner.run("gh", "pr", "create", "--repo", repo, "--base", base,
                      "--head", head, "--title", title, "--body-file", str(body_file))


def release_asset(release, tag):
    if release.get("tag_name") != tag or release.get("draft") or release.get("prerelease"):
        raise CommandError("Select a published stable Direct release")
    assets = [a for a in release.get("assets", []) if a.get("name") == "galaxy-direct.apk"]
    if len(assets) != 1 or assets[0].get("state") != "uploaded":
        raise CommandError("The release must contain one uploaded galaxy-direct.apk")
    asset = assets[0]
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", asset.get("digest") or ""):
        raise CommandError("GitHub did not provide a SHA-256 digest for this APK")
    if not 0 < asset.get("size", 0) <= 100 * 1024 * 1024:
        raise CommandError("The released APK has an invalid size")
    return asset


def verify_asset(path, asset):
    if not path.is_file() or path.stat().st_size != asset["size"]:
        raise CommandError("The APK download is incomplete")
    if "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest() != asset["digest"]:
        raise CommandError("The APK does not match GitHub's release digest")


def workspace(value):
    if value:
        directory = Path(value).resolve()
        directory.mkdir(parents=True, exist_ok=False)
        return directory
    return Path(tempfile.mkdtemp(prefix="starpilot-pr-"))


def upstream_pr(args, runner):
    if not re.fullmatch(r"direct-v[0-9]+(?:\.[0-9]+){1,3}", args.tag):
        raise CommandError("Use a Direct release tag, for example direct-v1.2.1")
    try:
        runner.api(f"repos/{args.target}/contents/{PREFIX}/SYNCED.md?ref={quote(args.base, safe='')}")
    except CommandError as exc:
        raise CommandError(f"Cannot find the Android split on {args.target}:{args.base}. "
                           "Merge AppSplit first and check repository access.") from exc
    fork = runner.api(f"repos/{args.fork}")
    if not fork.get("permissions", {}).get("push"):
        raise CommandError("You need push access to --fork; use a writable StarPilot fork")
    owner = args.fork.split("/")[0]
    branch = f"android/{args.tag}"
    found = existing_pr(runner, args.target, args.base, branch, owner)
    if found:
        print(found)
        return
    asset = release_asset(runner.api(f"repos/{args.app_repo}/releases/tags/{args.tag}"), args.tag)
    directory = workspace(args.work_dir)
    print(f"Preparing update in {directory}", flush=True)
    app = directory / "android"
    target = directory / "starpilot"
    runner.run("git", "clone", "--single-branch", "--branch", args.tag, "--no-tags",
               f"https://github.com/{args.app_repo}.git", str(app))
    runner.run("git", "fetch", "--no-tags", "origin", "Direct-Distro:refs/remotes/origin/Direct-Distro", cwd=app)
    runner.run("git", "merge-base", "--is-ancestor", "HEAD", "origin/Direct-Distro", cwd=app)
    name, code = version((app / "app/build.gradle.kts").read_text())
    if args.tag != "direct-v" + name:
        raise CommandError("The release tag does not match its Android source version")
    source_commit = runner.run("git", "rev-parse", "HEAD", cwd=app)
    runner.run("gh", "release", "download", args.tag, "--repo", args.app_repo,
               "--pattern", "galaxy-direct.apk", "--dir", str(directory))
    downloaded = directory / "galaxy-direct.apk"
    verify_asset(downloaded, asset)
    runner.run("git", "clone", "--filter=blob:none", "--no-checkout", "--single-branch", "--no-tags",
               "--branch", args.base, f"https://github.com/{args.target}.git", str(target))
    runner.run("git", "sparse-checkout", "set", "--cone", PREFIX, str(Path(APK).parent), cwd=target)
    runner.run("git", "checkout", "-b", branch, "origin/" + args.base, cwd=target)
    if not (target / PREFIX / "SYNCED.md").is_file():
        raise CommandError("The target branch changed; its Android split is missing")
    old_name, old_code = version((target / PREFIX / "app/build.gradle.kts").read_text())
    if code <= old_code or name == old_name:
        raise CommandError(f"Upstream already has Android {old_name} (code {old_code}). "
                           "Publish a newer Direct version with a higher versionCode first.")
    for field in ("name", "email"):
        identity = runner.run("git", "config", "user." + field, cwd=ROOT)
        runner.run("git", "config", "user." + field, identity, cwd=target)
    runner.run("git", "subtree", "pull", "--prefix=" + PREFIX, str(app), source_commit, "--squash", cwd=target)
    shutil.copyfile(downloaded, target / APK)
    (target / (APK + ".version")).write_text(name + "\n")
    runner.run("git", "add", "--", PREFIX, APK, APK + ".version", cwd=target)
    paths = runner.run("git", "diff", "--cached", "--name-only", cwd=target).splitlines()
    if any(not (p.startswith(PREFIX + "/") or p in (APK, APK + ".version")) for p in paths):
        raise CommandError("The update includes files outside the Android source and installer")
    if not paths:
        print("The target already contains this update")
        return
    runner.run("git", "diff", "--cached", "--check", cwd=target)
    runner.run("git", "commit", "-m", f"Companion: Ship Galaxy Direct {name}", cwd=target)
    if args.dry_run:
        print(f"Prepared {branch}; no push or PR. Review {target}")
        return
    runner.run("git", "-c", "core.askPass=", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential",
               "push", f"https://github.com/{args.fork}.git", f"HEAD:refs/heads/{branch}", cwd=target)
    print(open_pr(runner, args.target, args.base, f"{owner}:{branch}",
                  f"Companion: Update Galaxy Direct to {name}",
                  f"Ships Galaxy Direct {name} with the source from its published Android release.\n\n"
                  "The downloaded installer matches GitHub's SHA-256 digest. No device software was rebuilt.\n", directory))


def sync_dev(args, runner):
    destination = next(b for b in DEV_BRANCHES if b != args.source)
    repo_owner = args.app_repo.split("/")[0]
    found = existing_pr(runner, args.app_repo, destination, args.source, repo_owner)
    if found:
        print(found)
        return
    comparison = runner.api(f"repos/{args.app_repo}/compare/{destination}...{args.source}")
    if comparison.get("ahead_by", 0) == 0:
        print(f"{destination} already contains {args.source}")
        return
    source_tree = runner.api(f"repos/{args.app_repo}/commits/{args.source}")["commit"]["tree"]["sha"]
    destination_tree = runner.api(f"repos/{args.app_repo}/commits/{destination}")["commit"]["tree"]["sha"]
    if source_tree == destination_tree:
        print(f"{args.source} and {destination} already have identical files")
        return
    if args.dry_run:
        print(f"Would open {args.source} → {destination}; no PR created")
        return
    directory = workspace(args.work_dir)
    print(open_pr(runner, args.app_repo, destination, args.source,
                  f"Android: Sync {args.source} into {destination}",
                  "Shares development changes between the Android editions. Edition-specific behavior stays "
                  "in the Direct and Play flavors. Both flavors must pass CI before merging.\n", directory))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    upstream = commands.add_parser("upstream-pr", help="Ship a published Direct release through an upstream PR")
    upstream.add_argument("tag", help="Published direct-v<version> release")
    upstream.add_argument("--fork", type=repository, required=True, help="Writable StarPilot fork, e.g. dirwin31/openpilot")
    upstream.add_argument("--target", type=repository, default=UPSTREAM)
    upstream.add_argument("--base", default=BASE)
    sync = commands.add_parser("sync-dev", help="Open a PR into the other Android Dev branch")
    sync.add_argument("--from", dest="source", choices=DEV_BRANCHES, required=True)
    for command in (upstream, sync):
        command.add_argument("--app-repo", type=repository, default=APP_REPO)
        command.add_argument("--dry-run", action="store_true", help="Prepare locally without pushing or opening a PR")
        command.add_argument("--work-dir", help="New directory to keep prepared files for review")
    args = parser.parse_args(argv)
    try:
        for tool in ("git", "gh"):
            if shutil.which(tool) is None:
                raise CommandError(f"Install {tool} before running this command")
        runner = Runner()
        try:
            runner.run("gh", "auth", "status", "--hostname", "github.com")
        except CommandError as exc:
            raise CommandError("GitHub CLI authentication failed. Run gh auth login --hostname github.com, "
                               "then retry. Check DNS if authentication still fails.") from exc
        (upstream_pr if args.command == "upstream-pr" else sync_dev)(args, runner)
    except (CommandError, OSError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
