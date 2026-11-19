# Changelog

## 2.2.0 (unreleased)

- In-app messages: the SDK fetches the installation's eligible in-app messages (`GET /api/sdk/v1/in-app/`) when the app comes to the foreground, at most once a minute, and caches each page with its images and fonts inlined before it may show. A slow phone (low-RAM, or under 3 GiB) gets a message's light page when it has one.
- Shows the first eligible `app_open` message once per foreground, and `PushPilot.showInApp(name)` shows the first eligible message with that trigger name from the cache, returning whether it is on screen. Respects each message's per-device cap (the larger of the SDK's and the server's count) and its dates, and never shows two at once.
- Messages show in a full-screen transparent web view over the current activity, locked down: JavaScript only, no file, content, storage, location, camera, microphone, zoom, new windows or autofill, every navigation cancelled, and a content security policy that allows outside requests only to the app's allowed origins.
- The page's `window.pushpilot` bridge: `close`, `open(url)` (to the link handler set with `setLinkHandler`, or `ACTION_VIEW`), `haptic`, `track`, `insets`, `target`, `platform`, `light`, `posture`/`onPosture` (always `flat` on Android, with the screen size); `island` is `null`.
- Coach marks: `PushPilot.markView(view, name)` and `unmarkView(name)` name views a `coach_mark` message points at; it shows only while that view is on screen.
- Reports `shown`, `clicked` (per `track`) and `dismissed` (closed without a `track`) to `POST /api/sdk/v1/in-app/events/`, queued on disk and batched like push events; `forget` drops them with the in-app cache.
- The library ships a consumer R8 rule that keeps the bridge method.
- `PushPilot.setCurrentActivity(activity)` for wrappers (Flutter, React Native) whose init runs after their activity resumed, so the first foreground still gets its `app_open` message and `showInApp` works. Native apps that call init in `Application.onCreate` don't need it.

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
