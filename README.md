# PushPilot Android SDK

Registers your app's installation with PushPilot and forwards its FCM push token, so you can send pushes to individual users from the PushPilot dashboard.

- Kotlin, Android 6.0 (API 23) and later.
- No Firebase dependency and no other runtime dependency besides the Kotlin standard library. Your app keeps its own Firebase setup and hands the SDK its FCM token.
- Never asks for notification permission, never starts a service, and collects no hardware identifier.
- All network and disk work runs on one background thread. No method throws; problems are logged at debug level under the tag `PushPilot`. The push token and SDK key are never logged.

## Install

```kotlin
dependencies {
    implementation("ai.pushpilot:sdk:1.0.0")
}
```

The artifact is not published to a public repository yet. To use it locally, run `./gradlew :sdk:publishToMavenLocal` and add `mavenLocal()` to your repositories.

## Integrate

1. **Initialise in `Application.onCreate`** with the SDK key from the dashboard's Apps page. You can call it before Firebase initialises. Calling it again does nothing, and calls made before it are queued until it runs.

   ```kotlin
   class MyApp : Application() {
       override fun onCreate() {
           super.onCreate()
           PushPilot.init(this, "pp_sdk_...")
           FirebaseMessaging.getInstance().token.addOnSuccessListener { PushPilot.setPushToken(it) }
       }
   }
   ```

2. **Forward every new token** from your own `FirebaseMessagingService`, and tell PushPilot pushes apart from your others:

   ```kotlin
   class MyMessagingService : FirebaseMessagingService() {
       override fun onNewToken(token: String) = PushPilot.setPushToken(token)

       override fun onMessageReceived(message: RemoteMessage) {
           if (PushPilot.handleMessage(message.data)) {
               // A PushPilot push. Show it or route it as you like.
           }
       }
   }
   ```

3. **Identify the user** after login with `PushPilot.setExternalUserId(userId)` and call `PushPilot.logout()` when they log out.

4. **Ask for notification permission yourself** (on Android 13+, `POST_NOTIFICATIONS`). The SDK never prompts. It reads the current state each time one of your activities resumes, including when the permission dialog closes, so a grant or a revoke in Settings is sent without any extra code.

Backups need no setup. The SDK keeps its state in `ai.pushpilot.sdk.json` in the app's no-backup directory (`Context.getNoBackupFilesDir()`). Android always leaves that directory out of Auto Backup and device-to-device transfer, so a restored or cloned phone never reuses another phone's installation id. Your own backup rules don't need to mention it, and the SDK adds nothing to your manifest except the `INTERNET` permission.

The `sample/` app shows all of the above.

## Public methods

All methods are static on `PushPilot` (`@JvmStatic`, so Java callers use `PushPilot.init(...)` too).

| Method | What it does |
|---|---|
| `init(context, sdkKey, baseUrl = null)` | Starts the SDK. `baseUrl` defaults to `PushPilot.DEFAULT_BASE_URL` (`https://pushpilot.ai`); pass your own for a self-hosted server. |
| `setPushToken(token: String?)` | Stores the FCM token and syncs it. `null` clears it, and the device is then unreachable. |
| `setExternalUserId(id: String?)` | Links this installation to your own user id (at most 255 characters). One user can have many installations. `null` clears it. |
| `setAttributes(map: Map<String, Any?>)` | Merges custom attributes. Values must be `String`, `Number` or `Boolean`; pass datetimes as RFC 3339 strings, and pass `null` to remove an attribute. Only attributes declared on the dashboard are stored; the server drops the others with a warning. |
| `setTopics(topics: List<String>)` | Replaces the topic list (at most 100; letters, digits and `-_.~%`). Invalid names are dropped. |
| `logout()` | Clears the external user id. The device stays registered and keeps receiving pushes. |
| `forget()` | Deletes this installation from PushPilot and clears all local SDK state. Nothing is sent again until your app next calls a setter or the app next launches, which registers a new installation. To keep a user forgotten, stop calling the SDK for them. |
| `handleMessage(data: Map<String, String>): Boolean` | Returns `true` when a push came from PushPilot (its data has `source` = `pushpilot`). Makes no network call. |

## What the SDK sends, and when

Every request goes over HTTPS to `{baseUrl}/api/sdk/v1/register/`, `/sync/` or `/forget/`, with your public SDK key in the `X-SDK-Key` header. Each sync carries the full current state:

| Field | Value | Source |
|---|---|---|
| `installation_id` | Random UUID created on first launch, stored on the device and excluded from backup | SDK |
| `token` | FCM registration token, or `null` | Your app, via `setPushToken` |
| `notification_permission` | `granted`, `denied`, or `unknown` on Android 6 | SDK, read without prompting |
| `os_version` | Android version, for example `14` | SDK |
| `app_version` | Your app's `versionName` | SDK |
| `sdk_version` | This SDK's version | SDK |
| `device_model` | Manufacturer and model, for example `Google Pixel 8` | SDK |
| `locale` | BCP 47 tag, for example `en-IN` | SDK |
| `timezone` | IANA time zone id, for example `Asia/Kolkata` | SDK |
| `external_user_id` | Your user id, or `null` | Your app, via `setExternalUserId` |
| `topics` | Topic list; sent only after `setTopics` is called | Your app |
| `attributes` | Custom attributes; sent only after `setAttributes` is called | Your app |

`forget` sends only the `installation_id`.

Nothing else is collected: no advertising id, no location, no email or phone, no event tracking, no open or click reporting. Like any HTTP request, the server sees the device's IP address; PushPilot uses it in memory for rate limiting.

When requests are made:

- **First launch:** `register`, about 2 seconds after `init`.
- **After any setter call:** one `sync`, about 2 seconds later. Several calls within that window are sent together.
- **When an activity resumes:** the same check as at launch, about 2 seconds later, so a permission change made mid-session is sent. If nothing changed, it resyncs at most once a day. Before the first successful `register`, and after `forget()`, resuming sends nothing.
- **At each launch:** a `sync` if anything changed since the last accepted one (including app version, OS version, locale, time zone and permission), or if the last accepted one is more than 24 hours old. Otherwise nothing is sent.
- **On failure:** offline, timeout or server error is retried with exponential backoff (1 second doubling to 5 minutes, randomised). A `429` waits at least the server's `Retry-After`. Other `4xx` answers are not retried; the next change or launch tries again. A pending change is kept on disk, so it is still sent if the app is killed first.

## Google Play Data safety form

What to declare depends on what you pass to the SDK. For the SDK's own data:

- **Device or other IDs: collected.** The installation id and the FCM push token. Purpose: app functionality (delivering notifications). Not optional for users of push.
- **User IDs: collected**, only if you call `setExternalUserId`. Purpose: app functionality (sending to a signed-in user's devices).
- **Custom attributes:** declare whatever category matches the values you send with `setAttributes` (for example, purchase history or app interactions).
- Data is **encrypted in transit** (HTTPS) when you use the default base URL.
- Users can **request deletion**: call `PushPilot.forget()` from your account-deletion or privacy flow.

The OS version, app version, SDK version, device model, locale and time zone are sent along with these identifiers. Check Google's current Data safety guidance for whether your app must also declare them.

## Sample app

The sample builds without Firebase. To receive pushes, put your own `google-services.json` in `sample/` (it is git-ignored and must never be committed), then:

```bash
./gradlew :sample:installDebug -PpushpilotSdkKey=pp_sdk_...
```

Add `-PpushpilotBaseUrl=https://your-server` to point it at another server. Without a `google-services.json` the app runs and syncs, but has no push token.

## Build

```bash
./gradlew build
```

This builds the library and the sample, runs the JVM unit tests and runs Android lint. It needs JDK 17 and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

## Licence

No licence has been chosen yet.
