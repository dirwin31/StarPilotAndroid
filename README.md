# Galaxy for Android

The Android companion for StarPilot, available through Google Play.
Application ID: `com.starpilot.galaxy`.

## License and contributions

**Proprietary software — not open source. Pull requests are welcome.** You may
review the source, fork it on GitHub, and build or modify it locally to evaluate
changes and contribute back to this repository, under [the proprietary terms](LICENSE.txt).

**Redistributing the source or builds, or publishing builds to any app store,
is not allowed without prior written permission from the applicable copyright
holders.** This includes free, paid, modified, and renamed versions, and uploads
to Google Play, the Apple App Store, F-Droid, or other distribution services.
GitHub viewing/forking rights and third-party license rights remain unaffected.
Official releases are published by the project or its authorized distributors.

By submitting a PR, you agree to the contribution terms in [LICENSE.txt](LICENSE.txt),
which allow maintainers to incorporate and distribute your contribution in official
proprietary releases; you retain ownership of your contribution. Identify any
third-party code and its license in your PR.

[Gradle-related notices](LICENSE.gradle) and [bundled web dependency notices](app/src/main/assets/demo/web/vendor/notices/)
are preserved separately and do not provide an open-source license for the app.

## Contents

- [License and contributions](#license-and-contributions)
- [Branches and shared code](#branches-and-shared-code)
- [Set up](#set-up)
- [Test and build locally](#test-and-build-locally)
- [Promote a release](#promote-a-release)
- [Publish to Play](#publish-to-play)
- [Release configuration](#release-configuration)

## Branches and shared code

| Branch | Purpose | Release destination |
| --- | --- | --- |
| `Play-Dev` | Play development | `Play-Distro` |
| `Play-Distro` | Stable Play edition; repository default | Google Play |

Shared screens, connections and fixes belong in `app/src/main`. Play-specific
code and permissions belong in `app/src/play`, behind `AppDistribution`.

The old `main` and `play-store` branches are retained for history.

## Set up

Install Git, Python 3 and GitHub CLI for repository tooling and pull requests.

```sh
gh auth login --hostname github.com
gh repo clone dirwin31/StarPilotAndroid
cd StarPilotAndroid
```

Local Android builds also need JDK 17, Android SDK platform `android-37.0` and
build tools `37.0.0`. The app targets SDK 36.

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
See [implementation notes](IMPLEMENTATION.md) for remaining device checks.

### Refresh the bundled Galaxy web snapshot

```sh
./tools/snapshot_galaxy_web.sh /path/to/openpilot
```

Review the snapshot changes before committing. CI verifies
that Play excludes the APK installer permission. Device testing and Google Play
review remain separate release steps.

## Promote a release

Increase `versionCode` and `versionName` in `app/build.gradle.kts` on the Dev
branch, commit and push, then open the matching promotion PR:

```sh
# Play development → stable Play
gh pr create --repo dirwin31/StarPilotAndroid --base Play-Distro --head Play-Dev
```

Review checks and merge the PR before publishing from `Play-Distro`.

## Publish to Play

These instructions are for authorized release maintainers only; they do not grant
permission to redistribute the app or publish a store listing.

Choose **one** publication method below. Replace `1.2.1` with the committed
`versionName`. Release tags must be unique and remain unchanged after publication.

### Option A: publish by tag

```sh
git fetch origin

# Play release
git tag play-v1.2.1 origin/Play-Distro
git push origin play-v1.2.1
```

The workflow validates the stable branch and version, runs tests, and builds
the Play edition.

### Option B: run the release workflow manually

```sh
# Play release to internal testing
gh workflow run release.yaml --repo dirwin31/StarPilotAndroid \
  --ref Play-Distro -f tag=play-v1.2.1 -f play_track=internal
```

Play tracks are `internal`, `alpha`, `beta`, `production`, or `none` to skip
Play Console upload. Dev branches cannot publish through this workflow.

### Check release progress

```sh
gh run list --repo dirwin31/StarPilotAndroid --workflow release.yaml
gh release view play-v1.2.1 --repo dirwin31/StarPilotAndroid
```

## Release configuration

Configure these secrets in the **`android-release`** GitHub environment:

| Edition | Required secrets |
| --- | --- |
| Play signing | `COMPANION_UPLOAD_KEYSTORE_BASE64`, `COMPANION_UPLOAD_KEY_PASSWORD` |
| Play Console upload | `PLAY_SERVICE_ACCOUNT_JSON` |

Never commit signing keys. Without the Play service account, the signed AAB is published
to GitHub only. Uploads default to internal testing.

See [Play and push relay setup](push-relay/SETUP.md) for Firebase, upload keys,
Play Console declarations and review requirements.
