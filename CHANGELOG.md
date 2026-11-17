# Changelog

## 2.1.1 (unreleased)

- Live activities: `handleMessage` draws, updates and ends PushPilot live activities as one ongoing notification per activity on a new "Live updates" channel, from the activity's layout. On Android 16 they are promoted Live Updates when the push asks and the user allows it (status chip, segmented progress, tracker icon, timer).
- Reports `live_started`, `live_dismissed` and taps on live activities; never shows an activity again after the user swipes it away.
- Sync sends a `live` object (supported, enabled, promoted).
- Ships the PushPilot icon list as Material Symbols vector drawables (Apache-2.0).
- Adds the `POST_PROMOTED_NOTIFICATIONS` permission and a receiver to the manifest.
- Fixed: every live push crashed the app on Android (the `{{token}}` pattern had a bare `}`, which Android's ICU regex engine rejects). A unit test now holds every SDK regex to ICU's rules.
- `handleMessage`, the tap activity, the receivers and the SDK thread now contain any `Throwable` from PushPilot's own code: logged without the push's contents, never thrown into the app.
- `live_started` is reported only after the notification is posted.
- Fixed: a row holding columns drew only its first column (the "Sports score" template showed only the home side). A row or column inside a row is now only as wide as its blocks, and so is every box inside such a one.
- Collapsed, a live notification draws one line from the layout's compact island blocks instead of the lock layout cut off at the collapsed height.
- An end with no removal time keeps the final state for 4 hours after the end, as iOS does, with a timeout; before, it was removed at once.
- `PushPilot.status(callback)` gives a status screen the installation id, the live flags of the last accepted sync and the last sync result; it holds no token.

## 2.0.0 (2026-11-17)

First public release, on Maven Central as `ai.pushpilot:sdk:2.0.0`.

- Registers the app installation with PushPilot and forwards its FCM registration token or Firebase Installation ID.
- Shows PushPilot pushes itself (payload v2: an FCM data message `{source: "pushpilot", pushpilot: "<JSON>"}`), with an image and up to three buttons, in any language.
- Reports `delivered` and `clicked` events (with the button id), queued and sent in batches.
- Taps open the push's link, or hand it to a link handler set with `setLinkHandler`; `tappedNotification` returns the tapped push's data.
- `setExternalUserId`, `setAttributes`, `setTopics`, `logout` and `forget`.
- The backend address is configurable (default `https://pushpilot.ai`) for self-hosted PushPilot.
- Starts from the saved SDK key when a push arrives before the app has called `init`.
- No runtime dependency besides the Kotlin standard library; Android 6.0 (API 23) and later.
