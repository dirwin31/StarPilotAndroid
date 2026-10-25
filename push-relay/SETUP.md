# Maintainer setup

The app works without Firebase or a relay: enabled alerts use polling while connected and a 15-minute Android job. Android schedules background work opportunistically, so this does not promise immediate delivery.

## Firebase

1. Create a Firebase project on the Spark plan, without Analytics.
2. Register both Android IDs: `link.firestar.companion` and `link.firestar.galaxy`.
3. Download the project's `google-services.json` with both clients and put it in `app/`. This config is not a secret; commit it. Both flavors automatically include Messaging when the file exists. Without it they compile the polling implementation.
4. Enable Firebase Cloud Messaging API (V1) in Google Cloud.
5. Create a service account named `galaxy-push-relay` with **Firebase Cloud Messaging API Admin**. Create a JSON key. Keep that key private: never commit it or send it to another maintainer.

## Cloudflare

1. Install Wrangler with `npm i -g wrangler`, then `wrangler login` using your account.
2. In `push-relay`, run `wrangler kv namespace create RATE`. Replace the namespace ID in `wrangler.toml`.
3. Set `FCM_PROJECT_ID` in `[vars]` to your Firebase project ID.
4. Run `wrangler secret put FCM_SERVICE_ACCOUNT` and paste the private service-account JSON at its prompt.
5. Run `wrangler deploy`. Save the resulting `https://galaxy-push-relay.<account>.workers.dev` origin. A custom `push.firestar.link` route is optional.
6. Give that origin to the StarPilot maintainer to set `APP_PUSH_RELAY_URL` in the normal StarPilot source. Sync the app using `companion/sync.sh`. The normal StarPilot branch update ships everything to comma; no SSH, environment variables, Params or hand-placed device files are needed.

## Test

Install a Firebase-enabled build, enable Sentry alerts, and connect to Galaxy. Connections → Notification details shows acknowledgement and lets you copy this phone's token. Keep it private.

From a terminal, read the token without putting it in shell history, then send a test:

```sh
read -r FCM_TEST_TOKEN
export FCM_TEST_TOKEN
python3 - <<'PY' | curl --fail-with-body -H 'Content-Type: application/json' --data-binary @- https://YOUR-RELAY/v1/sentry
import json, os, time, uuid
print(json.dumps({'token': os.environ['FCM_TEST_TOKEN'], 'appId': 'link.firestar.galaxy', 'kind': 'test', 'eventId': uuid.uuid4().hex, 'wallTimeNs': time.time_ns(), 'label': 'Relay test'}))
PY
unset FCM_TEST_TOKEN
```

Expect 200 on acceptance, 410 for a dead/invalid FCM registration, 429 when rate limited, and 502 for a relay/FCM configuration failure. A 200 means FCM accepted the message; it does not prove the phone displayed it.

In Galaxy → Sentry, enable this phone, use Test, and check the delivery result. Repeat over LAN, Remote and Bluetooth. With the relay unavailable, use Simulate event while parked and check polling. Do not force-stop the Android app to test ordinary background delivery: Android blocks a force-stopped package until it is opened again.

## Operate

- Rotate credentials by creating a new service-account key, running `wrangler secret put FCM_SERVICE_ACCOUNT` again, deploying, then deleting the old key.
- Inspect failures with `wrangler tail`; do not add token/body logging.
- Review current [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) and [KV limits](https://developers.cloudflare.com/kv/platform/limits/) before rollout. KV has separate read/write quotas; do not assume the Workers daily request allowance is the usable push quota.
- Run `wrangler delete` to turn off the relay. Polling remains available.

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
