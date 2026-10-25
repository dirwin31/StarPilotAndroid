# Galaxy sentry relay

See [SETUP.md](SETUP.md) for maintainer setup and operation.

`POST /v1/sentry` accepts only a token, allowlisted app ID and event kind, bounded event ID and label, and integer event time. It sends a fixed data-only HIGH priority FCM message with a ten-minute TTL. No token or event payload is logged or persisted. KV stores only hashed-token hourly counters with a two-hour expiry; OAuth access tokens are cached in memory.

The KV limit is 30 requests per token per hour. KV is eventually consistent, so concurrent requests and requests across regions can exceed that limit; it is an abuse deterrent, not an exact global quota. Use a Durable Object if an exact global limit becomes necessary. Duplicate delivery is handled on the phone using the event ID.

Run `npm test`. Tests use a fake FCM transport and never send notifications. An FCM-specific `UNREGISTERED` or `INVALID_ARGUMENT` maps to 410; general payload/configuration errors remain 502 so configuration failures cannot delete all phone registrations.
