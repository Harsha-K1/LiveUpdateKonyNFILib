# LiveUpdateLib

`app/` builds `LiveUpdateNFILib.aar` (`com.metrash.liveupdate.LiveUpdateManager`).
`sample/` is a standalone Android app that exercises the lib before integrating it into VoltMX.

Requires JDK 17 (AGP 8.10 / Gradle 8.11.1).

## Build

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleRelease          # -> app/build/outputs/aar/LiveUpdateNFILib.aar
./gradlew :sample:installDebug          # sample uses the :app project directly
./gradlew :app:assembleRelease :sample:installDebug -PuseAar   # sample uses the built .aar file
```

Or open the folder in Android Studio and run the `sample` configuration.

## Using the sample

1. **Grant notification permission** (Android 13+).
2. **Open Live Update settings** (Android 16): make sure "Live updates" / promoted notifications is on for the app.
3. Press **Simulate** (or Start / Update / End with the slider), then press Home or lock the screen.

The *Device status* panel reports what the system actually did with the posted notification:

| Field | Meaning |
|---|---|
| `canPromote()` | `NotificationManager.canPostPromotedNotifications()` — user/system allows promotion |
| `Promotion API (36.1+)` | `Notification.Builder.setRequestPromotedOngoing` exists on this device |
| `promotable chars` | notification meets the Live Update requirements |
| `PROMOTED (Live Upd.)` | `FLAG_PROMOTED_ONGOING` was set — it is showing as a Live Update |

Below Android 16 the lib falls back to an ongoing progress notification on a high-importance channel
(`live_updates_alert`): `start()` drops a heads-up card from the top of the screen once (with the default
notification sound), then it stays in the shade and on the lock screen; `update()` calls change it silently.
Verified on a Pixel 6a API 34 emulator.
Seeing the real Live Update chip requires an Android 16 QPR1 (36.1) device or emulator image.

## Integrating into VoltMX

Build `app/build/outputs/aar/LiveUpdateNFILib.aar` (see Build) and add it to your VoltMX project as an
Android native extension (`Android/libs`, or via Volt MX Iris > Native Extensions).

### Who calls what

| Method | Class | Called from | Notes |
|---|---|---|---|
| `start`, `update`, `showTrip`, `end`, `hasPermission`, `requestPermission`, `openNotificationSettings`, `canPromote` | `LiveUpdateBridge` | **VoltMX JS** (`java.import`) | **Use these from VoltMX.** No `Context` argument; the bridge gets it from `KonyMain`. |
| Same names, plus a leading `Context` / `Activity` argument | `LiveUpdateManager` | Native Java (the `sample/` app, custom native code) | Does the real work. From JS you only need it if you want to pass your own `Context`. |
| `buildTrip`, `tripFallback`, `build`, `fallback` | `LiveUpdateManager` (internal) | Never called directly | Notification builders, reached through `start` / `update` / `showTrip`. `buildTrip` is the Android 16 version of the trip card and `tripFallback` is the older one. They are not exposed to JS or the host app. |

`tapDeepLink` (optional last argument of `start`, `update` and `showTrip`): a URI such as `"myapp://trip/42"` that
opens in your own app when the user taps the notification, so it can go straight to the trip details screen.
The app needs an activity with a matching `ACTION_VIEW` intent filter (scheme / host) for it; without one, or
with `null` / `""`, a tap opens the launcher activity as before. The existing signatures without it still work.

`alertOnEveryUpdate`: `true` alerts (sound / heads-up) on every `start` / `update` / `showTrip` call;
`false` alerts only on the first post and updates silently after that. On Android 16 a promoted
Live Update can still stay silent depending on the channel.

All calls return `false` (or do nothing) when the notification can't be shown: permission missing, the
"Live updates" channel turned off, or VoltMX not ready. `progress` is 0-100.

### From VoltMX JS

```js
var LiveUpdate = java.import("com.metrash.liveupdate.LiveUpdateBridge");

// 1. Permission (Android 13+). The answer arrives asynchronously, so check again before posting.
if (!LiveUpdate.hasPermission()) {
    LiveUpdate.requestPermission();
}

// 2a. Standard progress card
LiveUpdate.start("Order #1042", "Order confirmed", 0, "20 min", true);
LiveUpdate.update("Order #1042", "Out for delivery", 70, "6 min", true);

// 2b. Trip card (dashed route with a boat; Departure / Arrival underneath)
//     showTrip(departureLabel, departureTime, arrivalLabel, arrivalTime, progress, chipText, alertOnEveryUpdate)
LiveUpdate.showTrip("Departure", "10:30 AM", "Arrival", "11:45 AM", 0, "11:45 AM", true);
LiveUpdate.showTrip("Departure", "10:30 AM", "Arrival", "11:45 AM", 40, "11:45 AM", false);  // same call updates in place
LiveUpdate.showTrip("Departure", "10:30 AM", "Arrival", "11:45 AM", 40, "11:45 AM", false, "myapp://trip/42");  // tap opens the trip screen

// 3. Remove it
LiveUpdate.end();
```

`start`, `update` and `showTrip` all use one notification id, so any of them replaces whatever is showing.
Pass the labels in the user's language (e.g. Arabic); the lib doesn't translate them.
Guard these calls with `kony.os.deviceInfo().name === "android"`: the classes don't exist on iOS.

### From native Java

```java
// Standard progress card
LiveUpdateManager.start(ctx, "Order #1042", "Order confirmed", 0, "20 min", true);
LiveUpdateManager.update(ctx, "Order #1042", "Out for delivery", 70, "6 min", true);

// Trip card
LiveUpdateManager.showTrip(ctx, "Departure", "10:30 AM", "Arrival", "11:45 AM", 40, "11:45 AM", false);

LiveUpdateManager.end(ctx);
```

### Trip card appearance

- **Android 16:** a promoted Live Update. Promoted notifications can't use custom layouts, so it is a
  `ProgressStyle` (ring icons at both ends, the boat image as the progress marker); the route is not dashed.
- **Android 16 without promotion** (plain 16.0 such as Samsung One UI 8, or "Live updates" switched off for the app):
  `showTrip` uses the custom layout below. No status-bar chip or lock-screen Live Update is possible there.
  Logcat tag `LiveUpdateManager` prints which path was chosen.
- **Custom layout (below Android 16, or Android 16 without promotion):** a custom layout matching the design (dashed route, boat, Departure / Arrival; the app icon is only in the notification header).
  The boat moves with progress on all versions, from just clear of the start ring (0) to just before the end ring (100), in 2% steps.
- Colours come from `liveupdate_accent` (light and night variants) and the boat is `drawable-nodpi/sail_boat.png` (replace the file to change it) and the rings are
  `drawable/liveupdate_ic_endpoint.xml`.

### Host app requirements

The host app provides (all already present in a VoltMX Android build): `androidx.core`, `androidx.appcompat`
and `konywidgets`. These are `compileOnly` in the lib.

- **Icon:** the lib uses a drawable named `ic_stat_notify` from the host app if present (white-on-transparent);
  otherwise it falls back to a bundled boat icon.
- **targetSdk 36** is needed for promotion to work; the manifest permissions are merged in automatically.
