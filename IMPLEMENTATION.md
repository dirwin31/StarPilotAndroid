# Galaxy Android release handoff

The Direct and Play apps share this repository. Direct keeps `link.firestar.companion`, its existing signing key and pairing storage; Play uses `link.firestar.galaxy`. Both target SDK 36. The default landing page is Galaxy Home. Remote keys are encrypted, verified before saving, and exposed only through authenticated Galaxy routes. Automatic connections prefer Wi-Fi/LAN, Remote, then Bluetooth. Demo assets are a bundled snapshot, with synthetic fixtures and no real networking.

`push-relay/SETUP.md` covers Firebase, the VPS relay, upload-key creation, Play Console declarations and physical verification. No Firebase config is checked in until it is supplied; polling works meanwhile. Do not upload the Play application before confirming its permanent ID.

Build with JDK 17 and Android SDK 37:

```sh
./gradlew testDirectDebugUnitTest testPlayDebugUnitTest lintDirectRelease lintPlayRelease
./build_apk.sh
./build_play.sh
node --test push-relay/relay.test.js push-relay/server.test.js
node tests/test_demo_fixtures.mjs
```

The Direct build script signs with the existing companion key. The Play bundle remains unsigned unless upload credentials are supplied. Never commit signing keys. The standalone Direct APK remains under `app/build`; from the StarPilot subtree the script updates Galaxy's shipped APK and version file.

Refresh the bundled web snapshot using `tools/snapshot_galaxy_web.sh /path/to/openpilot`. `tools/capture_demo_fixtures.py` optionally refreshes the allowlisted fixtures over LAN and scrubs private values; review the resulting schemas and rerun the fixture tests before committing. The checked-in fixtures are synthetic and require no device capture.

Integration verification uses StarPilot's companion host tests plus Galaxy/sentry tests with native Params in its Linux ARM64 Docker builder. `companion/sync.sh` accepts a local app repo path as its second argument for unpublished testing. Git subtree creates its sync merge commit; the script does not make an additional artifact commit.

Release gates still outstanding: create/configure Firebase and deploy the relay; create the Play upload key; publish the privacy policy and declarations; verify upgrade over 1.1.30, Android 34–36 navigation/insets, route changes, background delivery and the airplane-mode demo on phones. No device deployment or Play upload is performed by the implementation work.
