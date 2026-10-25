---
title: Distribute only the APK and version while Android stays private
author: Danny
date: 2026-10-25
branch: Play-Distro
touches:
  - IMPLEMENTATION.md
  - README.md
  - tests/test_starpilot_cli.py
  - tools/starpilot.py
  - AGENTS.md
---

## Decided
Keep StarPilotAndroid private. Distro handoff opens a public StarPilot PR containing only the signed Direct APK and its version file. Trail, agent settings, app source and private Git history stay in Android. This supersedes earlier source subtree release guidance.
## Tried and ruled out
Whole-repository subtree imports can publish Trail and private history even if files are deleted afterward. Use an ordinary artifact-only commit instead.
## Watch out
Authenticate with access to the private app releases. Keep published tags immutable and use a newer Direct release. Never import Android source history into the public release branch.
