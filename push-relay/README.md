# Galaxy sentry relay

See [SETUP.md](SETUP.md) for setup and operation.

`POST /v1/sentry` accepts only a token, allowlisted app ID and event kind, bounded event ID and label, and integer event time. It sends a fixed data-only HIGH priority FCM message with a ten-minute TTL. No token or event payload is logged or persisted. Only hashed-token hourly counters are kept, in memory with a two-hour expiry; OAuth access tokens are cached in memory.

The limit is 30 requests per token per hour per relay process. It is an abuse deterrent, not an exact quota, and it resets on restart. Duplicate delivery is handled on the phone using the event ID.

`relay.js` holds the logic and `server.js` is the small Node HTTP wrapper (`npm start`). Run `npm test`. Tests use a fake FCM transport and never send notifications. An FCM-specific `UNREGISTERED` or `INVALID_ARGUMENT` maps to 410; general payload/configuration errors remain 502 so configuration failures cannot delete all phone registrations.

Android app IDs are built in. iOS bundle IDs come from `IOS_APP_IDS` and get a visible APNs alert instead of the data-only Android message.
