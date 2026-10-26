---
title: Galaxy WebView password field keyboard fix
author: Danny
date: 2026-10-26
branch: Play-Dev
cost: { minutes: 4, tokens: 43038 }
touches:
  - app/src/main/AndroidManifest.xml
  - app/src/main/java/link/firestar/companion/MainActivity.kt
---

## Decided
The Galaxy password field is an HTML input in galaxy.js, rendered in a WebView. The fix lives in MainActivity.showWeb (all routes pass through it): make the WebView focusable in touch mode and request focus on touch. MainActivity also gets windowSoftInputMode=adjustResize.

## Tried and ruled out
WindowInsets.Type.ime() for padding: needs API 30 and minSdk is 26. ConnectScreen's showSoftInput only covers the native address field, not the WebView.

## Watch out
The fix is unverified on a device; only compileDirectDebugKotlin was run. Builds need JAVA_HOME set to Android Studio's jbr and ANDROID_HOME=~/Library/Android/sdk.
