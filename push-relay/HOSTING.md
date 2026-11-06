# Running the Sentry push relay

This Worker forwards parked Sentry alerts from comma devices to phones through Firebase: Android directly, and iPhone by converting the phone's Apple token to a Firebase token. It stores no photos, video or locations. It is sized for about 500 commas.

## Set up (one time)

Already done: the relay runs at `https://galaxy-sentry-relay.dannyirwin31.workers.dev` on Danny's Cloudflare account, and the admin secret is in his Keychain as `galaxy-relay-admin`. These steps are for rebuilding it.

1. **Install Node.js 20 or newer.** Check with `node --version`.
2. **Run the tests** from `push-relay`: `npm test`. Nothing is sent anywhere.
3. **Sign in to Cloudflare:** `npx wrangler@4 login`. Workers Paid ($5/month) is recommended at this size; Free works but has daily request and storage-write caps.
4. **Deploy:** `npx wrangler@4 deploy`. The printed `https://galaxy-sentry-relay.<account>.workers.dev` address is the **relay URL**.
5. **Add the secrets:**

   ```sh
   npx wrangler@4 secret put FCM_SERVICE_ACCOUNT < /path/to/starpilot-sentry-….json
   openssl rand -hex 32          # copy the output, then paste it at the next prompt
   npx wrangler@4 secret put ENROLLMENT_ADMIN_SECRET
   ```

   Keep the admin secret in a password manager. It is for revoking commas, never for users.
6. **Check it:**

   ```sh
   export RELAY_URL=https://galaxy-sentry-relay.<account>.workers.dev
   read -r -s ENROLLMENT_ADMIN_SECRET && export ENROLLMENT_ADMIN_SECRET
   node operator.mjs check
   ```

   Expect five `PASS` lines and `Relay check passed.`
7. **Try one comma** before shipping: run `node operator.mjs invite`, enter the code and relay URL in Galaxy → Sentry → Custom relay, then **Send test** to an Android phone and an iPhone.
8. **Ship it:** set `DEFAULT_RELAY_URL` in `openpilot/starpilot/sentry_mode/relay_config.py` to the relay URL (no path). Users then just tap **Enable alerts**.

iPhone delivery also needs the iOS app registered in the Firebase project with Nate's APNs key uploaded. See [SETUP.md](SETUP.md).

## Day to day

- **Update the code:** `npx wrangler@4 deploy` from this folder. Never rename the Worker, change `class_name` or delete the Durable Object; enrolled commas live there.
- **Revoke one comma:** `node operator.mjs revoke <installation-id>` (the ID is shown in Galaxy → Sentry), with the variables from step 6 set.
- **Replace the Firebase key:** run the `secret put FCM_SERVICE_ACCOUNT` command with the new file, run `node operator.mjs check`, then delete the old key in Google Cloud.
- **Watch usage:** Cloudflare dashboard → Workers & Pages → `galaxy-sentry-relay` → Metrics. The relay never logs phone tokens or alert contents.
- **Turn it off:** disable the Worker in the dashboard. Phones fall back to checking while connected, and turning it back on keeps every enrollment.

## Limits

Set in `wrangler.jsonc` under `vars`. Raise one and redeploy if users hit it. A comma that hits a limit retries on its own.

| Setting | Value | Meaning |
| --- | --- | --- |
| `AUTO_ENROLL_DAILY_LIMIT` | 500 | New commas per day across all users |
| `AUTO_ENROLL_IP_DAILY_LIMIT` | 20 | New commas per day from one network address (carrier networks share addresses) |
| `MAX_INSTALLATIONS` | 5000 | Lifetime enrollments, including resets and re-enrollments |
| `DAILY_SEND_LIMIT` | 20000 | Alerts per day across all users |
| Per comma | 60/hour | Built in |
| `INGRESS` rate limit | 300/minute | Requests per network address |

Anyone can enroll a device automatically. These limits cap abuse; they don't prove ownership.
