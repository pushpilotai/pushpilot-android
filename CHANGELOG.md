# Changelog

## 2.0.0 (unreleased)

First public release, on Maven Central as `ai.pushpilot:sdk:2.0.0`.

- Registers the app installation with PushPilot and forwards its FCM registration token or Firebase Installation ID.
- Shows PushPilot pushes itself (payload v2: an FCM data message `{source: "pushpilot", pushpilot: "<JSON>"}`), with an image and up to three buttons, in any language.
- Reports `delivered` and `clicked` events (with the button id), queued and sent in batches.
- Taps open the push's link, or hand it to a link handler set with `setLinkHandler`; `tappedNotification` returns the tapped push's data.
- `setExternalUserId`, `setAttributes`, `setTopics`, `logout` and `forget`.
- The backend address is configurable (default `https://pushpilot.ai`) for self-hosted PushPilot.
- Starts from the saved SDK key when a push arrives before the app has called `init`.
- No runtime dependency besides the Kotlin standard library; Android 6.0 (API 23) and later.
