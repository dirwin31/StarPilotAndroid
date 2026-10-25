---
title: Release PR checks distinguish CI from app promotion
author: Codex
date: 2026-10-25
branch: Play-Dev
touches:
  - ".github/workflows/checks.yaml"
  - tools/starpilot.py
  - tests/test_starpilot_cli.py
---

## Decided
The Android release tooling and its CI are consistent with the shared-flavor release policy. CI runs on branch pushes to test code; branch promotion is a maintainer-reviewed merge. The upstream command downloads the published signed APK and never invokes Gradle. Test APK data is a fixture used to verify byte-for-byte preservation and digest failures.

## Tried and ruled out
The first trail check produced keyword-based false positives against the earlier learning: it mistook CI branch filters for promotion and mentions of published APKs for rebuilding them. The implementation preserves both policies. The commit records why those warnings are not actual conflicts.

## Watch out
Keep the preceding release-tool learning as historical context. Treat its phrases about rebuilding and automatic promotion as behavior rules rather than bans on mentioning releases or listing branches in CI.
