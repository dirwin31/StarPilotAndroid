# Hosting the Sentry push relay

This Worker forwards parked Sentry alerts from comma devices to phones: Android through Firebase, iPhone directly through Apple. It stores no photos, video or locations. It is sized for about 500 commas.

## What you receive

Get these through a password manager or another private channel, never chat or email:

- This `push-relay` folder.
- The Firebase service-account key: `starpilot-sentry-….json`.
- iPhone keys are **on hold**. Cloudflare Workers can't open the HTTP/2 connection Apple requires, so the iPhone route is being changed. Skip the Apple secrets in step 5 until Danny confirms. Android doesn't need them.

## Set up (one time)

1. **Install Node.js 20 or newer.** Check with `node --version`.

2. **Run the tests.** Unzip the folder, open a terminal in `push-relay`, and run:

   ```sh
   npm test
   ```

   Every test should pass. Nothing is sent anywhere.

3. **Sign in to Cloudflare.** Create a free account at dash.cloudflare.com if you don't have one. We recommend the Workers Paid plan ($5/month) at this size. Free also works, but it has daily request and storage-write caps that a busy day of alerts could reach. Then run:

   ```sh
   npx wrangler@4 login
   ```

4. **Deploy:**

   ```sh
   npx wrangler@4 deploy
   ```

   Copy the address it prints, for example `https://galaxy-sentry-relay.<your-account>.workers.dev`. That is the **relay URL**.

5. **Add the secrets.** Run each command from `push-relay`. Each one reads a file or asks you to paste a value.

   ```sh
   npx wrangler@4 secret put FCM_SERVICE_ACCOUNT < /path/to/starpilot-sentry-….json
   openssl rand -hex 32          # copy the output, then paste it at the next prompt
   npx wrangler@4 secret put ENROLLMENT_ADMIN_SECRET
   ```

   Only if Danny tells you the iPhone route needs them, also run:

   ```sh
   npx wrangler@4 secret put APNS_PRIVATE_KEY < /path/to/AuthKey_<KeyID>.p8
   npx wrangler@4 secret put APNS_TEAM_ID       # paste the 10-character Team ID
   npx wrangler@4 secret put APNS_KEY_ID        # paste the 10-character Key ID
   ```

   Save the admin secret in your password manager. You need it for step 6 and for revoking a comma later. Don't give it to users.

6. **Check the deployment:**

   ```sh
   export RELAY_URL=https://galaxy-sentry-relay.<your-account>.workers.dev
   read -r -s ENROLLMENT_ADMIN_SECRET && export ENROLLMENT_ADMIN_SECRET   # paste the admin secret
   node operator.mjs check
   ```

   You should see five `PASS` lines and `Relay check passed.` The check enrolls a temporary test device, makes sure Firebase accepts your key, then deletes the test device. If the Firebase line fails, the service-account key or `FCM_PROJECT_ID` in `wrangler.jsonc` is wrong.

7. **Send the relay URL to Danny.** He sets it in the StarPilot release, and comma devices then enroll themselves when users tap **Enable alerts**. Nobody types the URL into an app. Also share the admin secret with Danny privately, so either of you can revoke a comma.

8. **Delete your local copies** of the `.json` and `.p8` key files. Cloudflare keeps the only copy it needs.

Before Danny ships the URL to everyone, try one comma first: run `node operator.mjs invite`, enter the code and relay URL under Galaxy → Sentry → Custom relay, then use **Send test** to an Android phone.

## Day to day

- **Update the code:** replace the folder's files with the new version and run `npx wrangler@4 deploy` again. Never rename the Worker, change `class_name` or delete the Durable Object. Enrolled commas are stored there and would all stop working.
- **Revoke one comma:** ask the user for the installation ID shown in Galaxy → Sentry, then run `node operator.mjs revoke <installation-id>` with `RELAY_URL` and `ENROLLMENT_ADMIN_SECRET` set as in step 6.
- **Replace a key:** run the same `secret put` command with the new file, run `node operator.mjs check`, then delete the old key in Google Cloud or the Apple developer account.
- **Watch usage:** Cloudflare dashboard → Workers & Pages → `galaxy-sentry-relay` → Metrics. The relay never logs phone tokens or alert contents. Keep it that way.
- **Turn it off:** in the dashboard, disable the Worker. Phones fall back to checking for alerts while connected. Turning it back on keeps every enrollment.

## Limits

Set in `wrangler.jsonc` under `vars`. Raise one and redeploy if users hit it. A comma that hits a limit retries on its own.

| Setting | Value | Meaning |
| --- | --- | --- |
| `AUTO_ENROLL_DAILY_LIMIT` | 500 | New commas per day across all users |
| `AUTO_ENROLL_IP_DAILY_LIMIT` | 20 | New commas per day from one network address (carrier networks share addresses) |
| `MAX_INSTALLATIONS` | 5000 | Lifetime enrollments. Resets and re-enrollments count, so this leaves headroom for 500 commas |
| `DAILY_SEND_LIMIT` | 20000 | Alerts per day across all users |
| Per comma | 60/hour | Built in. Alerts per comma per hour |
| `INGRESS` rate limit | 300/minute | Requests per network address |

Anyone can enroll a device automatically. These limits cap the damage a bad actor could do; they don't prove ownership.
