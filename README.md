# Galaxy for Android

The Android companion for StarPilot. One shared codebase builds two editions:

| Edition | Application ID | Installation and updates |
| --- | --- | --- |
| Galaxy | `link.firestar.galaxy` | Google Play |
| Galaxy Direct | `link.firestar.companion` | Signed APK from comma over local Wi-Fi/LAN |

Android development lives in this private repository. Upstream StarPilot receives
only the released Direct APK and its version file. App source, Trail notes, agent
settings and Android Git history stay here.

## Contents

- [Branches and shared code](#branches-and-shared-code)
- [Set up](#set-up)
- [Test and build locally](#test-and-build-locally)
- [Sync development branches](#sync-development-branches)
- [Promote a release](#promote-a-release)
- [Publish Direct or Play](#publish-direct-or-play)
- [Open the upstream StarPilot PR](#open-the-upstream-starpilot-pr)
- [Release configuration](#release-configuration)

## Branches and shared code

| Branch | Purpose | Release destination |
| --- | --- | --- |
| `Play-Dev` | Play development | `Play-Distro` |
| `Play-Distro` | Stable Play edition; repository default | Google Play |
| `Direct-Dev` | Direct development | `Direct-Distro` |
| `Direct-Distro` | Stable Direct edition | Upstream StarPilot |

Both Gradle flavors remain in every branch. Shared screens, connections and fixes
belong in `app/src/main`. Edition-specific code and permissions belong in
`app/src/direct` or `app/src/play`, behind `AppDistribution`. Direct installs
updates from comma; Play opens Google Play. Merge shared changes between Dev
branches without deleting either flavor. Each edition can release independently.

The old `main` and `play-store` branches are retained for history.

## Set up

For the PR commands, install Git, Python 3 and GitHub CLI. Your GitHub account
needs access to this private repo and a writable StarPilot fork.

```sh
gh auth login --hostname github.com
gh repo clone dirwin31/StarPilotAndroid
cd StarPilotAndroid
```

Show command options:

```sh
./starpilot --help
./starpilot sync-dev --help
./starpilot upstream-pr --help
```

Local Android builds also need JDK 17, Android SDK platform `android-37.0` and
build tools `37.0.0`. The app targets SDK 36. Opening an upstream release PR uses
a published APK, so it needs no Android SDK or signing key.

## Test and build locally

### Android unit tests and lint

```sh
./gradlew testDirectDebugUnitTest testPlayDebugUnitTest lintDirectRelease lintPlayRelease
```

### Release command, relay and demo tests

```sh
python3 -m unittest discover -s tests -p test_starpilot_cli.py
node --test push-relay/relay.test.js push-relay/server.test.js
node tests/test_demo_fixtures.mjs
```

### Build Direct

```sh
./build_apk.sh
```

Uses the existing Direct signing key and leaves the standalone APK under `app/build`.

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

Review the snapshot changes before committing. CI checks both flavors and verifies
that Play excludes the APK installer permission. Device testing and Google Play
review remain separate release steps.

## Sync development branches

After committing and pushing new work, run **one** command for the branch that
contains it:

| Direction | Command |
| --- | --- |
| Play → Direct | `./starpilot sync-dev --from Play-Dev` |
| Direct → Play | `./starpilot sync-dev --from Direct-Dev` |

Preview a sync without opening a PR:

```sh
./starpilot sync-dev --from Play-Dev --dry-run
```

The command compares GitHub branches, reuses an existing sync PR and skips
identical trees. Review and merge after both flavor checks pass.

## Promote a release

Increase `versionCode` and `versionName` in `app/build.gradle.kts` on the Dev
branch, commit and push, then open the matching promotion PR:

```sh
# Play development → stable Play
gh pr create --repo dirwin31/StarPilotAndroid --base Play-Distro --head Play-Dev

# Direct development → stable Direct
gh pr create --repo dirwin31/StarPilotAndroid --base Direct-Distro --head Direct-Dev
```

Run only the promotion you intend to release. Review checks and merge the PR
before publishing from its Distro branch.

## Publish Direct or Play

Choose **one** publication method below. Replace `1.2.1` with the committed
`versionName`. Release tags must be unique and remain unchanged after publication.

### Option A: publish by tag

```sh
git fetch origin

# Direct release
git tag direct-v1.2.1 origin/Direct-Distro
git push origin direct-v1.2.1

# Play release
git tag play-v1.2.1 origin/Play-Distro
git push origin play-v1.2.1
```

Run only the commands for the edition being released. The workflow validates the
stable branch and version, runs tests, and builds that edition.

### Option B: run the release workflow manually

```sh
# Direct release
gh workflow run release.yaml --repo dirwin31/StarPilotAndroid \
  --ref Direct-Distro -f tag=direct-v1.2.1

# Play release to internal testing
gh workflow run release.yaml --repo dirwin31/StarPilotAndroid \
  --ref Play-Distro -f tag=play-v1.2.1 -f play_track=internal
```

Play tracks are `internal`, `alpha`, `beta`, `production`, or `none` to skip
Play Console upload. Dev branches cannot publish through this workflow.

### Check release progress

```sh
gh run list --repo dirwin31/StarPilotAndroid --workflow release.yaml
gh release view direct-v1.2.1 --repo dirwin31/StarPilotAndroid
gh release view play-v1.2.1 --repo dirwin31/StarPilotAndroid
```

## Open the upstream StarPilot PR

Once the Direct release is published, run:

```sh
./starpilot upstream-pr direct-v1.2.1 --fork dirwin31/openpilot
```

Replace `dirwin31/openpilot` with your writable StarPilot fork if needed.
The PR targets **`firestar5683/StarPilot:SecretGoodStarPilot`** and changes exactly:

| File under `openpilot/starpilot/galaxy/bin/` | Content |
| --- | --- |
| `starpilot-companion.apk` | Published, signed Direct APK |
| `starpilot-companion.apk.version` | Release version |

The command verifies the download against GitHub's SHA-256 digest, checks that the
release tag belongs to `Direct-Distro`, and requires a newer version than upstream
ships. It creates a branch in your fork and opens a PR; it does not merge it.
Repeated runs reuse an existing open PR.

### Preview against upstream

```sh
./starpilot upstream-pr direct-v1.2.1 --fork dirwin31/openpilot --dry-run
```

### Preview against AppSplit

```sh
./starpilot upstream-pr direct-v1.2.1 --fork dirwin31/openpilot \
  --target dirwin31/openpilot --base AppSplit --dry-run
```

### Keep the preview in a chosen directory

```sh
./starpilot upstream-pr direct-v1.2.1 --fork dirwin31/openpilot \
  --dry-run --work-dir /tmp/starpilot-release-review
```

The directory must not already exist. Dry runs prepare a local commit without
pushing or opening a PR. Use a real published tag newer than the target's version.
The target needs the Galaxy installer and version file; AppSplit does not need
to merge first.

After the upstream PR merges and comma updates, Galaxy offers the new installer
to Direct phones over local Wi-Fi/LAN. Play phones update through Google Play.
Publishing an Android release alone does not update upstream StarPilot.

The old StarPilot-side `companion/sync.sh` source import is disabled. Use the
APK-only PR command above for distribution.

## Release configuration

Configure these secrets in the **`android-release`** GitHub environment:

| Edition | Required secrets |
| --- | --- |
| Direct | `COMPANION_KEYSTORE_BASE64`, `COMPANION_KEY_PASSWORD` |
| Play signing | `COMPANION_UPLOAD_KEYSTORE_BASE64`, `COMPANION_UPLOAD_KEY_PASSWORD` |
| Play Console upload | `PLAY_SERVICE_ACCOUNT_JSON` |

Preserve Direct's existing signing key so updates retain pairing data. Never
commit signing keys. Without the Play service account, the signed AAB is published
to GitHub only. Uploads default to internal testing.

See [Play and push relay setup](push-relay/SETUP.md) for Firebase, upload keys,
Play Console declarations and review requirements.
