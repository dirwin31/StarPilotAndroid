# Galaxy for Android

The Android companion for StarPilot. One shared codebase builds two editions:

| Edition | Application ID | Installation and updates |
| --- | --- | --- |
| Galaxy | `link.firestar.galaxy` | Google Play |
| Galaxy Direct | `link.firestar.companion` | Signed APK from comma over local Wi-Fi/LAN |

## Branches

| Branch | Purpose |
| --- | --- |
| `Play-Distro` | Stable Play edition; repository default |
| `Play-Dev` | Development destined for `Play-Distro` |
| `Direct-Distro` | Stable Direct edition; the APK StarPilot distributes |
| `Direct-Dev` | Development destined for `Direct-Distro` |

Both editions remain in every branch as Gradle flavors. Branches decide when a
change is released, not which source files exist. The old `main` and `play-store`
branches are retained for history and existing checkouts.

## Keeping the editions together

Put shared screens, connections and fixes in `app/src/main`. Put edition-specific
code and permissions in `app/src/direct` or `app/src/play`, behind the shared
`AppDistribution` interface. For example, Direct installs updates from comma;
Play opens Google Play. Both implementations travel together when branches merge,
but Gradle includes only the selected flavor in each app.

After pushing work to either Dev branch, open a sync PR into the other:

```sh
./starpilot sync-dev --from Play-Dev
./starpilot sync-dev --from Direct-Dev
```

Run the command for the branch containing the new work. It compares published
branches, reuses an existing sync PR, and skips branches with identical files.
Review and merge the sync PR after both flavor checks pass. Then promote each
Dev branch into its matching Distro branch when that edition is ready to release.
Do not maintain different shared source files on Play and Direct branches, or
resolve edition differences by deleting the other flavor's implementation.

CI tests and lints both flavors and checks that Play excludes the APK installer
permission. This checks compilation and permissions; it does not replace device
testing or Google Play review. Maintainers can require these checks in branch rules.

## Releases

Tag a commit on the matching stable branch as `play-v<version>` or
`direct-v<version>`, for example `play-v1.2.0`. The version must match
`versionName` in `app/build.gradle.kts`. The release workflow validates the stable
branch, tests the shared app, and builds only the selected edition. Alternatively,
run it manually from `Play-Distro` or `Direct-Distro` with the matching tag.
Dev branches cannot publish through this workflow. Legacy `v*` tags no longer
trigger it.

The `android-release` environment needs `COMPANION_KEYSTORE_BASE64` and
`COMPANION_KEY_PASSWORD` for Direct, or `COMPANION_UPLOAD_KEYSTORE_BASE64` and
`COMPANION_UPLOAD_KEY_PASSWORD` for Play. Preserve Direct's existing signing key
so users can upgrade without losing pairing data.

`PLAY_SERVICE_ACCOUNT_JSON` additionally enables Play Console uploads; without
it the signed AAB is published to GitHub only. Uploads default to internal
testing. A manual run can select another track or `none` to skip uploading.
Play Console setup and review remain separate; see `push-relay/SETUP.md`.

## Shipping Direct with StarPilot

App maintainers can open the upstream update PR from this repository. Install
Python 3, Git and GitHub CLI, authenticate with `gh auth login --hostname github.com`,
and have a writable StarPilot fork. No Android SDK or signing key is needed for
this command. First publish a Direct release; for example, for `direct-v1.2.1`:

```sh
./starpilot upstream-pr direct-v1.2.1 --fork dirwin31/openpilot
```

Replace the fork with your writable fork. The command downloads the published
signed APK and verifies its SHA-256 against GitHub. The PR changes exactly two
files: Galaxy's installer APK and its `.version` file. It requires a newer version
than the installed release and a tag on Direct-Distro. Android source, Git history,
Trail notes and agent settings stay in the private Android repository. Your GitHub
login needs read access to that repo and write access to your StarPilot fork.

The command opens a PR against `firestar5683/StarPilot:SecretGoodStarPilot`.
It never merges or pushes directly upstream. The target only needs the existing
Galaxy installer and version file; AppSplit does not need to merge first.
To prepare a local review without publishing anything:

```sh
./starpilot upstream-pr direct-v1.2.1 --fork dirwin31/openpilot \
  --target dirwin31/openpilot --base AppSplit --dry-run
```

Use a tag that actually exists and is newer than the target. `--dry-run` keeps the
prepared checkout locally without pushing or creating a PR; `--work-dir` selects
a new directory for review. Repeating a published update reuses its existing open
PR. Review any merge conflicts; the command does not force-push or overwrite an
existing release branch. Keep published release tags immutable.

Once the upstream PR merges and comma updates, Galaxy offers the shipped installer
to Direct phones over local Wi-Fi/LAN. Play phones continue updating through
Google Play. A GitHub app release alone does not change what upstream ships.

The existing StarPilot-side `openpilot/starpilot/companion/sync.sh` remains available
for maintainers who want to import `Direct-Distro` and rebuild locally with their
signing key. The app-side PR command deliberately reuses the already signed APK.

To test local development, use `sync.sh Direct-Dev /path/to/StarPilotAndroid`;
that local repository must contain the requested branch. See `IMPLEMENTATION.md`
for build commands and remaining device checks.
