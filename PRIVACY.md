# Galaxy Privacy Policy

Galaxy (the StarPilot Galaxy app, package `com.starpilot.galaxy`) is a companion app for a comma device running StarPilot. This policy explains what the app does with your information.

_Last updated: 2026-11-05_

## The short version

- Galaxy has no ads, no analytics and no tracking.
- Galaxy has no accounts with us. It connects to **your own** comma device.
- We do not sell your data or share it for advertising.

## What the app handles

**Connection to your comma.** Galaxy connects to your device over the StarPilot Wi-Fi, your local network, Bluetooth, or the Remote route through the Galaxy remote relay. Your Galaxy session, App key and Bluetooth pairing are stored on your phone only. Remote traffic passes through that relay to reach your device.

**Location (optional).** If you turn on phone GPS sharing, Galaxy sends your phone's precise location to your own comma device while sharing is on. It runs as a foreground service with a visible notification. Location is not sent to us. Bluetooth scanning is declared as never used to derive location.

**Shared places.** When you share a place from a maps app to Galaxy, Galaxy sends the shared text (the place's name, address and link) to your comma to start the route, over the same connections. Google Maps short links are opened on your phone, only on Google's servers, to read the full link. When the link has no coordinates, the comma looks the place up with the map provider you set up, Mapbox or Google. If your comma is not reachable, the shared text stays on your phone for up to 12 hours and is sent when your phone next connects to the comma; sharing a new place replaces it. A notification shows the result if notifications are allowed.

**Notifications (optional).** If you enable Sentry alerts, Galaxy registers a push token (Firebase Cloud Messaging) so your comma can notify your phone. The token and alert events pass through Firebase and a small push relay that forwards them and keeps no data. Without Firebase, alerts use polling while the app is connected.

**Camera (optional).** Used only to scan a Galaxy App key QR code. Images are not saved or sent anywhere.

**Network and Bluetooth.** Used to find and connect to your comma. Galaxy can search your local Wi-Fi network for a StarPilot device.

**Support reports (optional).** If you choose **Report a problem**, you can sign in with Discord in your browser and tap **Send report** to submit your description and diagnostics through a reporting relay hosted on Cloudflare. Sign-in requests Discord's `identify` permission; it does not request your email or join a server. The public support thread includes your description and Discord identity. Only include information you are comfortable sharing publicly. The relay may use Cloudflare Workers AI to create a short title from your description. Diagnostics are not sent to that model.

The diagnostics attachment goes to a separate private maintainer channel in Discord. It contains the report UUID, app version/build, phone model and Android version/build/security patch, UTC and local capture time with timezone, connection status and errors, all permissions requested by the installed app with their current grant status, app notification access, install-app special access when requested, Bluetooth radio/session state, Galaxy/Tunnel/music setup and Android audio bond status, cached screen availability, and GPS-sharing/notification enablement status. When available, it includes the comma's software version, branch, and commit with the observation time and whether that information came from an earlier connection. Up to 300 recent events describe Bluetooth scan, connection, service discovery, pairing/authentication, music bonding, and connection failures, including numeric Android error codes. This event history and observed software information are held in memory for the current app process; a saved report preserves the captured snapshot. The app removes URLs, IP and Bluetooth addresses, and recognized credentials from events. It does not attach location, recordings, session cookies, App keys, passwords, or OAuth tokens. You can inspect the exact attachment in the saved report. The public thread contains a link to private diagnostics; Discord channel permissions control who can open it. Follow-ups go to an existing Android report thread you select. Maintainers may post relevant build updates in that thread.

Discord credentials and any pending report are encrypted on your phone using Android Keystore and excluded from app backups. Pending reports retain the original contents and can be retried only with the original Discord account for six days. An unconfirmed delivery is not automatically repeated. Clearing a saved report removes only the local copy. Disconnecting removes local Discord credentials and attempts to revoke access. You can also revoke the connection in Discord's Authorized Apps settings. Reports already sent remain in Discord until removed there; contact the maintainers to request deletion. The relay stores delivery receipts for seven days and retains report-to-thread mappings and notification receipts for follow-ups. Its support dashboard also retains reporter name and Discord ID, report title and UUID, report date, and the public thread link in an Android-specific index accessible to authorized Android release operators. That metadata has no automatic seven-day expiry; contact the maintainers to request deletion. The index does not contain the diagnostics attachment, passwords, or OAuth tokens. The relay does not retain diagnostic contents or OAuth credentials in its report database. Cloudflare and Discord process submitted reports to deliver this optional feature.

## What we collect

We (StarPilotHQ) do not run analytics or advertising SDKs. If you explicitly submit a support report, maintainers receive the information described above. The advertising ID permission is removed from the app.

Third parties involved in the optional features above: Google Firebase Cloud Messaging (push delivery), subject to [Google's privacy policy](https://policies.google.com/privacy); [Discord](https://discord.com/privacy) (support sign-in and report hosting); and [Cloudflare](https://www.cloudflare.com/privacypolicy/) (support relay and report title generation).

## Storage and security

Keys, sessions and pairing data are stored on your phone. Remote connections use HTTPS. Removing the pairing or uninstalling the app deletes this data from your phone.

## Children

Galaxy is not directed at children under 13 and does not knowingly collect their information.

## Changes

If this policy changes, the updated version and date will be posted at this address.

## Contact

Questions: open an issue at https://github.com/StarPilotHQ
