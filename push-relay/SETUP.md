# Push relay setup

The app works without Firebase or a relay: enabled alerts use polling while connected and a 15-minute Android job. Android schedules background work opportunistically, so this does not promise immediate delivery.

## Firebase

1. Create a Firebase project on the Spark plan, without Analytics.
2. Register both Android IDs: `link.firestar.companion` and `link.firestar.galaxy`.
3. Download the project's `google-services.json` with both clients and put it in `app/`. This config is not a secret; commit it. Both flavors automatically include Messaging when the file exists. Without it they compile the polling implementation.
4. Enable Firebase Cloud Messaging API (V1) in Google Cloud.
5. Create a service account named `galaxy-push-relay` with **Firebase Cloud Messaging API Admin**. Create a JSON key. Keep that key private: never commit it or share it.

## iOS push (optional)

Firebase delivers to iPhones through Apple's push service, using the same relay.

1. Join the Apple Developer Program. In Certificates, Identifiers & Profiles, enable the Push Notifications capability on the iOS app's identifier and create an APNs Auth Key (.p8). Note its Key ID and your Team ID.
2. In Firebase, Project settings, Cloud Messaging, upload that key under the iOS app configuration. Register the iOS bundle ID as an iOS app in the project.
3. Set the relay's `IOS_APP_IDS` (comma-separated bundle IDs) in `galaxy-push-relay.service`. The default is the bundle ID currently in the iOS project, `link.galaxy.bluetooth.natehuby76`; change it to the published one. An empty value turns iOS off. The same IDs must be in `APP_IDS` in StarPilot's `sentry_mode/notifications.py`, or comma will reject the subscription.
4. The relay sends iOS pushes as a visible alert (title, body, `thread-id` sentry), not a silent data message, because iOS delays or drops silent pushes. Android keeps the data-only message.
5. The iOS app must add the Firebase Messaging SDK, ask for notification permission, and send its FCM token with the iOS bundle ID to Galaxy's `appSubscribe` action, like the Android app. That app work is separate and not done yet. The sentry photo needs an iOS notification service extension, since the `mutable-content` flag only allows one.

Test on a physical iPhone; the simulator cannot receive remote pushes.

## Relay on a VPS

Any Linux server with Node 20+ and HTTPS works; the existing `galaxy.firestar.link` server is the natural home. The relay holds no data and has no dependencies.

1. Copy the `push-relay` folder to `/opt/galaxy-push-relay` and create `/etc/galaxy-push-relay/service-account.json` (root-owned, mode 600) holding the private service-account JSON.
2. Install `galaxy-push-relay.service` into `/etc/systemd/system/`, set `FCM_PROJECT_ID` in it to the Firebase project ID, then run `systemctl daemon-reload && systemctl enable --now galaxy-push-relay`. It listens on `127.0.0.1:8787` only. The unit runs as a throwaway user and reads the key through systemd credentials.
3. Put HTTPS in front of it. `Caddyfile.example` exposes only `/v1/sentry` at `https://push.firestar.link`; Caddy gets the certificate itself. With nginx, proxy `/v1/sentry` to `127.0.0.1:8787` the same way. Never expose port 8787 directly.
4. Set `APP_PUSH_RELAY_URL` in the StarPilot source to `https://push.firestar.link/v1/sentry`. Sync the app using `companion/sync.sh`. The normal StarPilot branch update ships everything to comma; no SSH, environment variables, Params or hand-placed device files are needed.

Run it by hand to try it: `FCM_PROJECT_ID=... FCM_SERVICE_ACCOUNT_FILE=key.json node server.js`.

## Test

Install a Firebase-enabled build, enable Sentry alerts, and connect to Galaxy. Connections → Notification details shows acknowledgement and lets you copy this phone's token. Keep it private.

From a terminal, read the token without putting it in shell history, then send a test:

```sh
read -r FCM_TEST_TOKEN
export FCM_TEST_TOKEN
python3 - <<'PY' | curl --fail-with-body -H 'Content-Type: application/json' --data-binary @- https://push.firestar.link/v1/sentry
import json, os, time, uuid
print(json.dumps({'token': os.environ['FCM_TEST_TOKEN'], 'appId': 'link.firestar.galaxy', 'kind': 'test', 'eventId': uuid.uuid4().hex, 'wallTimeNs': time.time_ns(), 'label': 'Relay test'}))
PY
unset FCM_TEST_TOKEN
```

Expect 200 on acceptance, 410 for a dead/invalid FCM registration, 429 when rate limited, and 502 for a relay/FCM configuration failure. A 200 means FCM accepted the message; it does not prove the phone displayed it.

In Galaxy → Sentry, enable this phone, use Test, and check the delivery result. Repeat over LAN, Remote and Bluetooth. With the relay unavailable, use Simulate event while parked and check polling. Do not force-stop the Android app to test ordinary background delivery: Android blocks a force-stopped package until it is opened again.

## Operate

- Rotate credentials by creating a new service-account key, replacing `/etc/galaxy-push-relay/service-account.json`, running `systemctl restart galaxy-push-relay`, then deleting the old key.
- Inspect failures with `journalctl -u galaxy-push-relay`. The relay never logs tokens or bodies; do not add that.
- Rate limits live in memory, so a restart resets them. That is acceptable for an abuse deterrent.
- Update by copying new files and restarting. Run `systemctl disable --now galaxy-push-relay` to turn it off. Polling remains available.

## Play release

Confirm `link.firestar.galaxy` before the first upload. Create the upload key yourself with:

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
