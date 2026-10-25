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
| `Direct-Distro` | Stable Direct edition; the source StarPilot imports |
| `Direct-Dev` | Development destined for `Direct-Distro` |

Both editions remain in every branch as Gradle flavors. Branches decide when a
change is released, not which source files exist. Keep shared fixes in sync
between the Dev branches, then merge each into its matching Distro branch after
testing. The old `main` and `play-store` branches are retained for history and
existing checkouts; new work uses the four branches above.

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

From StarPilot's AppSplit checkout:

```sh
openpilot/starpilot/companion/sync.sh Direct-Distro
```

This imports the app using Git subtree, refreshes demo assets, and builds
`openpilot/starpilot/galaxy/bin/starpilot-companion.apk` and its `.version` file.
Review and commit generated changes, then push the StarPilot branch. Comma gets
that installer when it updates to the new StarPilot revision; the phone can then
install it from Galaxy over local Wi-Fi/LAN. Android GitHub releases do not
automatically update StarPilot.

To test local development, use `sync.sh Direct-Dev /path/to/StarPilotAndroid`;
that local repository must contain the requested branch. See `IMPLEMENTATION.md`
for build commands and remaining device checks.
