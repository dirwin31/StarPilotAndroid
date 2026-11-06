# Push relay setup

The app works without Firebase or a relay: enabled alerts use polling while connected and a 15-minute Android job. Android schedules background work opportunistically, so this does not promise immediate delivery.

## Launch checklist

1. **Firebase (done):** project `starpilot-sentry` on the Spark plan, Android app `com.starpilot.galaxy`, Cloud Messaging API (V1) enabled, and service account `galaxy-push-relay` with **Firebase Cloud Messaging API Admin**.
2. **iPhone in Firebase:** in the Firebase console, add an Apple app with bundle ID `link.galaxy.bluetooth.natehuby76` (skip the SDK and plist steps). Then, under Project settings → Cloud Messaging → Apple app configuration, upload Nate's APNs auth key (`.p8`, Key ID and Team ID). To keep the key with Nate, add him to the Firebase project and let him upload it. The iOS app itself needs no Firebase SDK.
3. **Relay:** deploy and check it with [HOSTING.md](HOSTING.md).
4. **StarPilot:** set `DEFAULT_RELAY_URL` in `openpilot/starpilot/sentry_mode/relay_config.py` to the relay URL (origin only, no path) and ship the release.
5. **Android release:** store the client config once with `gh secret set GOOGLE_SERVICES_JSON --repo dirwin31/StarPilotAndroid --env android-release < app/google-services.json`. The release workflow writes it before building the Play bundle and fails if it is missing. Keep the file out of git: the Direct flavor's ID isn't in it, so committing it breaks the Direct unit tests.
6. **iOS release:** Nate enables Push Notifications for the bundle ID and builds the iOS PR. Debug uses sandbox APNs; Release (TestFlight and App Store) uses production.
7. **Test:** see [Test](#test).

Users then tap **Enable alerts** in Galaxy → Sentry or in the app. The phone asks for notification permission, and comma enrolls itself and registers the phone. There are no codes or URLs to type.

## How it fits together

Each comma generates its own sending credential on enrollment and keeps it private. Phones keep their own push tokens. The Worker stores only credential digests, phone-token fingerprints and short-lived delivery receipts. Camera media stays on comma. Android gets a data-only Firebase message. The iPhone app registers its Apple token; the Worker converts it to a Firebase token and Firebase delivers a visible alert without photos through Apple. Tapping either opens Sentry events.

If the relay rejects a phone (for example, an app ID it doesn't know), comma skips that phone and keeps alerting the others. The phone retries when it next reconnects.

Comma controls in Galaxy → Sentry: **Rotate credential** replaces the comma's credential (the old one keeps working for up to ten minutes). **Revoke relay access** stops sending at once and retries the remote revoke if offline. **Forget locally** does not revoke remotely; use `node operator.mjs revoke` for that.

For a private deployment, set `AUTO_ENROLL` to `"false"`, then create single-use codes with `node operator.mjs invite` and enter them under Galaxy's Custom relay.

## Legacy private pilot

Old comma builds can still use the v1 secret-path Worker if the operator sets a 64-hex `RELAY_PATH_SECRET` and adds a KV namespace bound as `RATE`. Its base URL is `https://<worker>/<secret>`, and old comma code appends `/v1/sentry`. Never publish that URL. Move each comma to enrollment, then delete the secret. The VPS relay below is also v1 only.

## Relay on a VPS

Any Linux server with Node 20+ and HTTPS works. The relay holds no data and has no dependencies.

1. Copy the `push-relay` folder to `/opt/galaxy-push-relay` and create `/etc/galaxy-push-relay/service-account.json` (root-owned, mode 600) holding the private service-account JSON.
2. Install `galaxy-push-relay.service` into `/etc/systemd/system/`, set `FCM_PROJECT_ID` in it, then run `systemctl daemon-reload && systemctl enable --now galaxy-push-relay`. It listens on `127.0.0.1:8787` only.
3. Put HTTPS in front of it. `Caddyfile.example` exposes only `/v1/sentry`. Never expose port 8787 directly.
4. Save the HTTPS address in Galaxy's legacy private relay setting. Comma appends `/v1/sentry`.

Rotate the key by replacing the file, running `systemctl restart galaxy-push-relay`, then deleting the old key. Read failures with `journalctl -u galaxy-push-relay`. The relay never logs tokens or bodies.

## Test

- **Relay alone:** `node operator.mjs check` (see HOSTING.md step 6). To push to a real Android phone, copy its token from Connections → Notification details and run `read -r -s RELAY_TEST_TOKEN && export RELAY_TEST_TOKEN` before the check.
- **End to end:** on a release with `DEFAULT_RELAY_URL` set, enable alerts on each phone, then use **Send test** in Galaxy → Sentry. Repeat with the app in the background and the phone off comma's Wi-Fi while comma has internet. A 200 from the relay means Firebase accepted the message, not that the phone showed it.
- **Without the relay:** use Simulate event while parked and check polling.

Do not force-stop the Android app to test background delivery. Android blocks a force-stopped app until it is opened again.

## Play release

Confirm `com.starpilot.galaxy` before the first upload. Create the upload key yourself with:

```sh
keytool -genkeypair -alias upload -keyalg RSA -keysize 4096 -validity 10000 -keystore ~/.starpilot/companion-upload.jks
```

Set `COMPANION_UPLOAD_KEYSTORE` and `COMPANION_UPLOAD_KEY_PASSWORD` in the release environment, then run `./build_play.sh`. Without these credentials the bundle is unsigned and cannot be uploaded. Enroll in Play App Signing, upload to Internal testing, and review the pre-launch report before production.

Supply the privacy-policy URL, foreground-service declarations/videos for connectedDevice and location, foreground location disclosure, and data safety declarations for location shared to the user's comma and tokens/events processed by Firebase and the relay. No ads or analytics are included. Describe phone internet sharing and the need for a comma running StarPilot. Reviewer access: tap **No comma? Explore the demo**, then **Explore app features**; all demo data is simulated and remains offline.

Physical verification remains required: Direct upgrade over 1.1.30 without losing pairings, side-by-side launchers, API 34–36 back/insets, route failover with page preservation, background push, and airplane-mode demo.

## Automatic Play publishing

Pushing a tag like `v1.2.1` (it must equal `versionName` in `app/build.gradle.kts`) builds, creates the GitHub Release and uploads the signed bundle to Play's internal track. Manual runs can pick another track, or `none`. Raise `versionCode` for every upload.

One-time setup:

1. Create the app in Play Console and upload the first bundle by hand. The API cannot create apps.
2. In Play Console, Setup, API access, link a Google Cloud project and create a service account with a JSON key. Invite it under Users and permissions with Release apps to testing tracks (add production when ready).
3. Store the JSON: `gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo dirwin31/StarPilotAndroid --env android-release < key.json`. Without it the Play step is skipped.
