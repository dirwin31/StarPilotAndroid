# Galaxy sentry relay

Deploy `worker.js` with `wrangler.jsonc` for a shared Cloudflare relay. See [setup](SETUP.md) for the operator handoff, Android Firebase setup and direct Apple APNs setup. Install the StarPilot enrollment PR, configure the hosted URL once for the release, and users simply enable alerts. The normal flow needs no invitation or URL entry. The old shared-secret v1 pilot remains optional.

Native iOS tokens prefixed `apns.production.` or `apns.sandbox.` go directly to Apple. Android and legacy Firebase tokens use FCM. Apple credentials are optional for Android-only deployments.

See [SETUP.md](SETUP.md) for setup and operation.

The provider adapter behind both versions accepts only a token, allowlisted app ID and event kind, bounded event ID and label, and integer event time. It sends a fixed data-only HIGH priority FCM message with a ten-minute TTL. Raw phone tokens and event bodies are not persisted. v2 stores per-installation credential digests, destination fingerprints and delivery receipts in SQLite-backed Durable Object storage; receipt IDs expire after a day and are pruned on subsequent sends. Invitations are pruned by an hourly alarm. Provider authorization tokens are cached in memory.

v2 enforces 60 attempts per installation per hour transactionally and scopes destinations to that installation. The optional legacy v1 limit is approximately 30 requests per token per hour. It is an abuse deterrent, not an exact quota; KV is eventually consistent, and VPS memory counters reset on restart. Duplicate delivery is handled on the phone using the event ID.

`relay.js` holds the logic and `server.js` is the small Node HTTP wrapper (`npm start`). Run `npm test`. Tests use a fake FCM transport and never send notifications. An FCM-specific `UNREGISTERED` or `INVALID_ARGUMENT` maps to 410; general payload/configuration errors remain 502 so configuration failures cannot delete all phone registrations.

Android app IDs are built in. iOS bundle IDs come from `IOS_APP_IDS` and get a visible APNs alert instead of the data-only Android message.
