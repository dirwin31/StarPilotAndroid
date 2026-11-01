# Galaxy for Android

The Android companion for StarPilot, available through Google Play.
Application ID: `com.starpilot.galaxy`.

## License and contributions

**Source available under proprietary terms—not an open-source license.** You may
review the source, fork it on GitHub, and build or modify it locally to evaluate
changes and contribute back. You may install, use, back up, and update official
releases for personal or internal business use. Permission to modify or build the
source is limited to evaluation, testing, and contributing changes; it does not
permit ongoing personal or business use of modified builds. For ordinary use,
use an official release. Authorized maintainer and collaborator permissions and
other rights preserved by [LICENSE.txt](LICENSE.txt) remain unaffected.

Redistribution and unofficial app-store publishing require permission from the
applicable copyright holders. This includes free, paid, modified, and renamed
versions. GitHub viewing/forking permissions, third-party licenses, and valid
prior license grants remain unaffected.

Official releases are published by authorized maintainers. The
[project licensing terms](LICENSE.starpilot-team.md) describe release permissions
and arrangements for continued maintenance. Forking the source does not grant
permission to distribute builds or publish an unofficial app-store listing.

**Pull requests are welcome.** Submit changes against `Play-Dev`. New contributions
are subject to the contribution terms in [LICENSE.txt](LICENSE.txt), including
official proprietary releases and authorized sublicensing; contributors retain
ownership. Identify any third-party material and its license in your PR.
The [existing-contribution agreement](CONTRIBUTOR-AGREEMENT.md) provides a way for
holders to expressly cover earlier contributions; it requires their acceptance.

[Gradle-related notices](LICENSE.gradle) and
[bundled web dependency notices](app/src/main/assets/demo/web/vendor/notices/)
remain applicable to their respective components.

## Contents

- [License and contributions](#license-and-contributions)
- [Branches and shared code](#branches-and-shared-code)
- [Set up](#set-up)
- [Test and build locally](#test-and-build-locally)
- [Promote a release](#promote-a-release)

## Branches and shared code

| Branch | Purpose | Release destination |
| --- | --- | --- |
| `Play-Dev` | Play development | `Play-Distro` |
| `Play-Distro` | Stable Play edition; repository default | Google Play |

Shared screens, connections and fixes belong in `app/src/main`. Play-specific
code and permissions belong in `app/src/play`, behind `AppDistribution`.

## Set up

Install Git, Python 3, and Node.js for the repository tools and tests. GitHub CLI
is used by the PR and release commands below; it is not required to clone public
source or run local builds.

```sh
git clone https://github.com/dirwin31/StarPilotAndroid.git
cd StarPilotAndroid
git switch Play-Dev
```

Local Android builds also need JDK 17, Android SDK platform `android-37.0` and
build tools `37.0.0`. Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK,
or configure `sdk.dir` in an untracked `local.properties`. The app targets SDK 36.
Official signing credentials are not needed for debug tests or unsigned Play builds.

To contribute without repository write access, fork the repository and open a PR
from your fork into `Play-Dev`. Authenticate with `gh auth login` when using
GitHub CLI commands that require your account.

## Test and build locally

### Android unit tests and lint

```sh
./gradlew testPlayDebugUnitTest lintPlayRelease
```

### Release command, relay and demo tests

```sh
python3 -m unittest discover -s tests -p test_starpilot_cli.py
node --test push-relay/relay.test.js push-relay/server.test.js
node tests/test_demo_fixtures.mjs
```

### Build Play

```sh
./build_play.sh
```

The Play bundle remains unsigned unless upload credentials are supplied.
An unsigned AAB is a build artifact, not an installable APK. Device testing and
store publishing are handled separately by authorized maintainers.

### Refresh the bundled Galaxy web snapshot

```sh
./tools/snapshot_galaxy_web.sh /path/to/openpilot
```

Review the snapshot changes before committing. CI verifies
that Play excludes the APK installer permission. Device testing and Google Play
review remain separate release steps.

## Promote a release

For authorized release maintainers: increase `versionCode` and `versionName` in `app/build.gradle.kts` on the Dev
branch, commit and push, then open the matching promotion PR:

```sh
# Play development → stable Play
gh pr create --repo dirwin31/StarPilotAndroid --base Play-Distro --head Play-Dev
```

Review checks and merge the PR before publishing from `Play-Distro`.
