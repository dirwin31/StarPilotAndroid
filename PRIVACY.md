# Galaxy Privacy Policy

Galaxy (the StarPilot Galaxy app, package `com.starpilot.galaxy`) is a companion app for a comma device running StarPilot. This policy explains what the app does with your information.

_Last updated: 2026-11-01_

## The short version

- Galaxy has no ads, no analytics and no tracking.
- Galaxy has no accounts with us. It connects to **your own** comma device.
- We do not sell your data or share it for advertising.

## What the app handles

**Connection to your comma.** Galaxy connects to your device over the StarPilot Wi-Fi, your local network, Bluetooth, or the Remote route through `galaxy.firestar.link`. Your Galaxy session, App key and Bluetooth pairing are stored on your phone only. Remote traffic passes through `galaxy.firestar.link` to reach your device.

**Location (optional).** If you turn on phone GPS sharing, Galaxy sends your phone's precise location to your own comma device while sharing is on. It runs as a foreground service with a visible notification. Location is not sent to us. Bluetooth scanning is declared as never used to derive location.

**Notifications (optional).** If you enable Sentry alerts, Galaxy registers a push token (Firebase Cloud Messaging) so your comma can notify your phone. The token and alert events pass through Firebase and a small push relay (`push.firestar.link`) that forwards them and keeps no data. Without Firebase, alerts use polling while the app is connected.

**Camera (optional).** Used only to scan a Galaxy App key QR code. Images are not saved or sent anywhere.

**Network and Bluetooth.** Used to find and connect to your comma. Galaxy can search your local Wi-Fi network for a StarPilot device.

## What we collect

We (StarPilotHQ) do not collect personal information through the app, and we do not run analytics or advertising SDKs. The advertising ID permission is removed from the app.

Third parties involved only in the optional features above: Google Firebase Cloud Messaging (push delivery), subject to [Google's privacy policy](https://policies.google.com/privacy).

## Storage and security

Keys, sessions and pairing data are stored on your phone. Connections to `galaxy.firestar.link` use HTTPS. Removing the pairing or uninstalling the app deletes this data from your phone.

## Children

Galaxy is not directed at children under 13 and does not knowingly collect their information.

## Changes

If this policy changes, the updated version and date will be posted at this address.

## Contact

Questions: open an issue at https://github.com/StarPilotHQ
