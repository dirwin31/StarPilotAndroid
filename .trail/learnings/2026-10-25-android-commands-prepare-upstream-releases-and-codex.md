---
title: Android commands prepare upstream releases and sync Dev branches
author: Codex
date: 2026-10-25
branch: Play-Dev
touches:
  - starpilot
  - tools/starpilot.py
  - tests/test_starpilot_cli.py
  - ".github/workflows/checks.yaml"
  - README.md
---

## Decided
The Android repository owns the upstream-pr command. It imports source from a published direct-v release tag and copies its already signed APK into a sparse StarPilot checkout, verifies the GitHub SHA-256 digest, and opens a PR through a writable fork against firestar5683/StarPilot:SecretGoodStarPilot. Contributors need Git, Python 3, and authenticated GitHub CLI; they do not need the Android SDK or the Direct signing key.

Both editions remain in the same source tree. Shared work belongs in app/src/main, and edition differences belong in the Direct and Play flavors behind AppDistribution. The sync-dev command opens a PR from one published Dev branch into the other. It skips equal trees to avoid bouncing merge commits. CI tests and lints both flavors and checks installer permissions.

## Tried and ruled out
Rebuilding a signed APK on every contributors machine would require sharing the signing key and could mismatch the published release. The command instead imports the exact released source and installer. It does not automatically merge, force-push, or promote Dev changes into Distro branches.

## Watch out
The target branch must already contain the AppSplit Android subtree. A live upstream release PR also needs a published Direct release with a newer version name and code, plus working GitHub CLI authentication. Existing open PRs are reused. Release tags must stay immutable. End-to-end tests use real local Git repositories and mocked GitHub calls; no upstream PR or physical-device deployment was exercised during implementation.
