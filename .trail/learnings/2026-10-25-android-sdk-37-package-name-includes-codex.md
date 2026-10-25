---
title: Android SDK 37 package name includes the minor version
author: Codex
date: 2026-10-25
branch: Play-Dev
touches:
  - ".github/workflows/checks.yaml"
  - ".github/workflows/release.yaml"
---

## Decided
Android CI and release workflows must install platforms;android-37.0 with build-tools;37.0.0. The app continues compiling against API 37 and targeting API 36. Googles current repository2-3.xml and the installed SDK package.xml both identify the base platform as platforms;android-37.0.

## Tried and ruled out
The first flavor CI runs failed before compilation because sdkmanager was asked for platforms;android-37, which is not a package in Googles index. Inspecting the exact package path resolved the failure without changing app SDK levels.

## Watch out
Use package paths from SDK repository metadata rather than deriving them from compileSdk. Initial PR tooling tests passed on GitHub; SDK setup failed independently of those tests.
