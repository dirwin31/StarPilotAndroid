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

## Pair Galaxy remote access

Tap **Remote** and choose **Try automatic import** while the phone and StarPilot
are on the same LAN. Galaxy shows progress as it finds the device, opens a local
session, fetches the App key, and saves pairing. Remote access must already be
enabled on StarPilot under **Galaxy → Access**.

Off LAN, choose **Scan QR code** and scan the **App key** QR code on that screen,
or paste the key. Camera access is requested only when scanning. QR scanning runs
on-device using [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
([Apache-2.0 license](https://github.com/journeyapps/zxing-android-embedded/blob/v4.3.0/COPYING)).

Keys are encrypted with Android Keystore and retained across app restarts, even
when internet verification is pending. Automatic connection tries LAN before
Remote. **Forget remote key** removes the saved key and disables automatic import
until you pair again or choose **Import remote key from LAN** in Connections.

The automatic-import prompt has a **Don’t ask again** action at the bottom of its choices. This preference
survives app restarts; Remote then opens the paste/scan options directly. Manual
LAN import and **Show automatic import prompt** remain available in Connections.

## Send a support report

Open **More options → Connection status → Report a problem**. Connect with Discord
in your browser, describe the problem, and choose a new thread or one of your
existing Android report threads. Tap **Send report** to capture diagnostics and submit the report directly.
You can inspect the captured diagnostics afterward using **Review saved report**.

Reports include the app/build and phone/OS identity, UTC/local time and timezone,
the report UUID, every permission requested by the installed app and its current grant status,
notification access and applicable install-app access, selected and active connection, current status and last error,
Galaxy/Tunnel/music setup, Android audio bond, permissions/radio state, and a
bounded Bluetooth connection/pairing timeline. Available comma software
version/branch/commit is labeled with its observation time; data from an earlier
connection is marked as potentially belonging to a different comma. Music bond
status does not establish the current audio output. Events and observed software
are kept in memory for this app process; saved retries retain the original report.

The app uses a Discord report relay. Worker version 13 or newer is
required for Android platform handling. Discord redirects through the Worker's
HTTPS callback back into the app. State, issuer, expiry,
and PKCE are checked; all secrets and webhooks stay on the Worker. The app needs
an internet connection and uses a validated network for relay requests even when
Galaxy is bound to the comma's offline Wi-Fi.

A saved report retains its original UUID and contents, encrypted on the phone.
Only definite rejections can be retried, under the original Discord account for
six days. Check Discord before discarding an unconfirmed delivery. Disconnecting
Discord keeps the saved report; clearing it never deletes posts from Discord.
See [the privacy policy](PRIVACY.md) for visibility and retention details.

Before shipping, check real browser sign-in/return, sending, private attachments,
follow-ups, and thread opening on a phone against the deployed Worker. Local
mock tests cannot establish live Discord or Cloudflare configuration.

### Maintainer support dashboard

Worker version 19 provides the Android support dashboard.
The relay owner generates a one-time Android setup link; the Android maintainer
uses it to create a dashboard password. Dashboard sign-in is separate from the
app's Discord sign-in. Release keys and dashboard credentials belong with release
operators and must never be included in the app.

Confirmed Android reports and follow-ups are indexed automatically. The dashboard
can import older reports, show commits from `dirwin31/StarPilotAndroid` on
`Play-Dev`, and draft an update for a selected report thread. Review each draft
and confirm the recipient can download the stated version/build before sending
a notice. A commit or successful build alone does not establish availability.

Contact the relay owner for setup, permissions, and release-notice details.

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
