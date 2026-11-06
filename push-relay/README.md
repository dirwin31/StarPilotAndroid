# Galaxy sentry relay

A Cloudflare Worker that forwards parked Sentry alerts from comma to phones: Android through Firebase, iPhone directly through Apple.

- **Hosting it:** [HOSTING.md](HOSTING.md), step by step.
- **Firebase, Apple, release and testing:** [SETUP.md](SETUP.md).

`worker.js` routes requests, `enrollment.js` holds per-comma credentials in a SQLite Durable Object, `relay.js` sends to Firebase and `apns.js` sends to Apple. `operator.mjs` checks a deployment and revokes a comma. `server.js` is the legacy v1 Node relay. Run `npm test`. The tests use fake providers and never send anything.

The relay accepts only an allowlisted app ID, event kind, bounded event ID and label, and an integer event time. It never stores raw phone tokens or alert contents. A dead phone token returns 410 and removes that phone; configuration errors return 502, so a broken key can't delete every registration.
