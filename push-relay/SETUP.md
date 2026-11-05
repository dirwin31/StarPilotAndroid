# Push relay setup

The app works without Firebase or a relay: enabled alerts use polling while connected and a 15-minute Android job. Android schedules background work opportunistically, so this does not promise immediate delivery.

## Firebase

1. Create a Firebase project on the Spark plan, without Analytics.
2. Register both Android IDs: `link.firestar.companion` and `com.starpilot.galaxy`.
3. Download the project's `google-services.json` with both clients and put it in `app/`. This config is not a secret; commit it. Both flavors automatically include Messaging when the file exists. Without it they compile the polling implementation. For a push-enabled release, add `-PrequireSentryPush=true` to the Gradle build; it fails early if this file is absent. Register both package IDs in the same Firebase project used by the relay.
4. Enable Firebase Cloud Messaging API (V1) in Google Cloud.
5. Create a service account named `galaxy-push-relay` with **Firebase Cloud Messaging API Admin**. Create a JSON key. Keep that key private: never commit it or share it.

## iOS push without Firebase

Nate's iOS app uses APNs directly. No Firebase SDK or Google configuration is needed on iOS.

1. Enable Push Notifications for `link.galaxy.bluetooth.natehuby76` in the Apple developer account and regenerate the signing profile.
2. Create an APNs `.p8` signing key that permits this app and the needed environments. Give its Key ID, Team ID and private key securely to the Worker operator. The key never belongs in either app or on comma.
3. Keep `IOS_APP_IDS` equal to the app's bundle ID. This ID and both Android IDs above are already accepted by the current StarPilot comma code. Changing to another bundle ID may require a comma change.
4. Build the iOS PR. Debug uses development/sandbox APNs; Release uses production APNs (including TestFlight). `APS_ENVIRONMENT` sets both the entitlement and token routing. Verify the final signed entitlement matches the app's `SentryAPNsEnvironment` before distributing a custom build.
5. Enable phone alerts in the app's Settings or Galaxy's Sentry page. The app registers an `apns.production.<hex>` or `apns.sandbox.<hex>` token with comma. The Worker sends a visible alert; tapping it opens Sentry events after connecting. This version does not attach camera photos to iOS notifications.

## Cloudflare Worker with per-comma enrollment

**Must complete before phone push works:** create Firebase configuration for Android, obtain Nate's APNs key for iOS, deploy this Worker, install the StarPilot enrollment PR, and set the hosted URL once in the StarPilot release. Users then enable alerts with one action. These account credentials and live deployment are not included in the repository. Nate can use his existing Apple developer membership; no additional Apple membership is needed for his app.

The public Worker URL contains no secret. On the user’s Enable alerts action, each comma generates and privately stores a separate sending credential without an invitation. Phones retain their provider tokens; neither phone receives the comma credential. The Worker stores credential digests, destination fingerprints and bounded delivery receipts in a SQLite-backed Durable Object. Camera media remains on comma. The operator can revoke one comma without disrupting others.

1. Use Node 20+ and Cloudflare's Workers Free plan. From this directory run `npx wrangler@4 login`. The SQLite Durable Object binding and migration are already configured. Run `npx wrangler@4 kv namespace create RATE` and replace the RATE namespace placeholder in `wrangler.jsonc` (KV is used only by the optional legacy pilot).
2. Replace `FCM_PROJECT_ID`, `APNS_TEAM_ID`, `APNS_KEY_ID`, and `IOS_APP_IDS` in `wrangler.jsonc`. Keep the existing bundle ID for Nate's app. For Android-only operation set `IOS_APP_IDS` to an empty string; iOS-only operation needs no Firebase credentials.
3. Put provider keys in Worker secrets, never source, either app, or comma:

   ```sh
   npx wrangler@4 secret put FCM_SERVICE_ACCOUNT < /secure/service-account.json
   npx wrangler@4 secret put APNS_PRIVATE_KEY < /secure/AuthKey.p8
   openssl rand -hex 32
   npx wrangler@4 secret put ENROLLMENT_ADMIN_SECRET
   ```

   Paste the generated 64-character admin secret into Wrangler's prompt and retain it in the operator password manager. Skip provider secrets for unused platforms. The admin secret is exclusively for the operator; do not give it to users. Leave `RELAY_PATH_SECRET` unset unless deliberately maintaining an old private pilot.
4. Run `npm test` and `npx wrangler@4 deploy`. Keep observability disabled. The migration creates SQLite storage automatically. Retain the resulting `https://galaxy-sentry-relay.<account>.workers.dev` address; no custom domain is required.
5. Set `DEFAULT_RELAY_URL` in the StarPilot PR's `openpilot/starpilot/sentry_mode/relay_config.py` to the deployed public HTTPS origin, then ship that StarPilot release. This is a one-time maintainer setting; users never type it. No production URL is guessed while hosting is undeployed. Both apps discover readiness from comma, so neither app needs its own URL. Keep `AUTO_ENROLL` set to `"true"` in Wrangler.
6. Users connect to comma and tap **Enable alerts** in Galaxy, or enable Sentry phone alerts in the native app. The phone requests notification permission. Comma enrolls itself, saves its private credential, registers the phone destination and retries interrupted work. There are no invitation codes, accounts, copied tokens or endpoint fields in the normal flow. A release without a configured host reports that hosting is not ready. Existing enabled phones require an explicit enable action to opt into automatic enrollment.
7. Install Firebase-enabled Android and APNs-enabled iOS builds. Allow notifications, enable alerts on both phones, and send tests. Wait for relay enrollment before expecting delivery. Each comma supports eight registered destinations; event sending is limited to 60 attempts/hour/installation. Ingress limiting is approximate; installation authorization and quotas use transactional storage.
8. Verify each platform with the app backgrounded and the phone away from comma's Wi-Fi, while comma has internet. Verify wrong credentials cannot send, revoke one of two enrolled commas, and confirm only the revoked device stops. A successful provider response is acceptance, not proof of notification display.

Automatic enrollment is public, not hardware attestation or proof of ownership. Enforced defaults are 5 new installations per IP/day, 100 per relay/day, 1,000 total automatic installations (including revoked installations), and 1,000 send attempts per relay/day, plus 60/hour per installation. Configure these Wrangler variables for capacity; they fail closed at the limit. Daily hashed IP records expire and are pruned. Attackers can consume capacity or enroll new identities; accounts or attestation would be needed to prevent that. Keep the deployment on Free and monitor capacity. Revoked IDs remain tombstoned so retries cannot reactivate them.

For a private deployment, set `AUTO_ENROLL` to `"false"` and use `node operator.mjs invite` with `RELAY_URL` and `ENROLLMENT_ADMIN_SECRET` in the operator environment. Enter that code through Galaxy's Custom relay controls. Normal users of the public deployment do not need this tool.

Use Galaxy's **Rotate credential** control for an individual comma. Rotation retains the old credential for at most ten minutes and retires it on confirmation. **Revoke relay access** stops sending locally immediately and retries remote revocation if offline. **Forget locally** is not remote revocation; use the operator tool if credentials have already been lost:

```sh
export RELAY_URL=https://galaxy-sentry-relay.YOUR-ACCOUNT.workers.dev
read -r -s ENROLLMENT_ADMIN_SECRET
export ENROLLMENT_ADMIN_SECRET
node operator.mjs revoke INSTALLATION_ID_FROM_GALAXY
unset ENROLLMENT_ADMIN_SECRET
```

Provider key rotation is separate: replace the Worker secret, test delivery, then retire the old Apple/Google key. Do not delete the Durable Object namespace to update code. Deploy new code with the same binding/class and migration history to retain enrolled devices. Disabling the Worker stops delivery; restoring it preserves credentials. Never downgrade enrolled devices to anonymous sending.

### Cost and limits

[FCM is a no-cost Firebase product](https://firebase.google.com/pricing). Use Spark; no Analytics, database, Cloud Functions, or paid Firebase extension is needed. [Cloudflare supports SQLite Durable Objects on Workers Free](https://developers.cloudflare.com/durable-objects/platform/pricing/); stay on Free and monitor its request, CPU and storage limits. Free limits can stop service; free hosting is not unlimited delivery. A workers.dev address avoids domain registration costs. Nate supplies the existing Apple developer membership and signing assets. Play distribution uses the existing Play developer account. No provider account or deployment is created automatically by these PRs.

### Legacy private pilot

Old comma builds can still use the original v1 secret-path Worker when the operator explicitly sets a 64-hex `RELAY_PATH_SECRET`. Its base URL is `https://<worker>/<secret>`, with `/v1/sentry` appended by old comma code. Never publish that URL as a default. Migrate each comma to enrollment, then delete the shared secret. The VPS section below is legacy v1 only and does not implement enrollment.

## Relay on a VPS

Any Linux server with Node 20+ and HTTPS works; the existing `galaxy.firestar.link` server is the natural home. The relay holds no data and has no dependencies.

1. Copy the `push-relay` folder to `/opt/galaxy-push-relay` and create `/etc/galaxy-push-relay/service-account.json` (root-owned, mode 600) holding the private service-account JSON.
2. Install `galaxy-push-relay.service` into `/etc/systemd/system/`, set `FCM_PROJECT_ID` in it to the Firebase project ID, then run `systemctl daemon-reload && systemctl enable --now galaxy-push-relay`. It listens on `127.0.0.1:8787` only. The unit runs as a throwaway user and reads the key through systemd credentials.
3. Put HTTPS in front of it. `Caddyfile.example` exposes only `/v1/sentry` at `https://push.firestar.link`; Caddy gets the certificate itself. With nginx, proxy `/v1/sentry` to `127.0.0.1:8787` the same way. Never expose port 8787 directly.
4. Save `https://push.firestar.link` in Galaxy’s HTTPS push relay URL setting. Comma appends `/v1/sentry`; no source change is required. This legacy VPS entry point does not implement the Worker’s secret path. Use the Worker deployment above for the shared hosted service.

Run it by hand to try it: `FCM_PROJECT_ID=... FCM_SERVICE_ACCOUNT_FILE=key.json node server.js`.

## Test

Install a Firebase-enabled build, enable Sentry alerts, and connect to Galaxy. Connections → Notification details shows acknowledgement and lets you copy this phone's token. Keep it private.

From a terminal, read the token without putting it in shell history, then send a test:

```sh
read -r FCM_TEST_TOKEN
export FCM_TEST_TOKEN
python3 - <<'PY' | curl --fail-with-body -H 'Content-Type: application/json' --data-binary @- https://push.firestar.link/v1/sentry
import json, os, time, uuid
print(json.dumps({'token': os.environ['FCM_TEST_TOKEN'], 'appId': 'com.starpilot.galaxy', 'kind': 'test', 'eventId': uuid.uuid4().hex, 'wallTimeNs': time.time_ns(), 'label': 'Relay test'}))
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
