# Testing

## Build and design checks

```sh
npm ci --prefix web
./scripts/build.sh
npm --prefix web run dev -- --port 5179
```

In a second terminal:

```sh
npm --prefix web exec -- playwright install chromium
node scripts/verify-design.mjs
```

The browser checks render the sample canvas, open participants, switch theme, check a narrow layout, navigate to Boards, and exercise invalid-link feedback. They generate public sample screenshots and private verification artifacts. `BROWSER_CHANNEL=chrome` can use installed Chrome instead of a Playwright-managed browser.

The sandbox has no native Ink layer and does not test synchronization.

## Android instrumentation

Use a **disposable board** shared from the desktop. Open its invitation once in the Android app so it is in Recents. These tests draw temporary strokes in a distant area and remove their own new Ink records. They change the tablet's camera/tool while running; don't use the same board interactively during a test.

```sh
cd android
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e boardId YOUR_SHARE_BOARD_ID \
  -e class com.facundopri.tldrawink.CurveHandoffTest \
  com.facundopri.tldrawink.test/androidx.test.runner.AndroidJUnitRunner
```

The board ID is the path component after `/join/`, not the invitation token. Each test starts the activity and selects the matching recent board. Test classes:

| Class | Checks |
| --- | --- |
| `LivePressureTest` | Width changes during a stroke, before pen-up |
| `NativeHandoffTest` | Native input in the left band and preview/final width at two zooms |
| `CurveHandoffTest` | Pixel overlap of preview and final small curve |
| `PortraitLayoutTest` | Portrait layout when the device honors the requested orientation |

Instrumentation captures and metrics are saved to the app's private files. Some newer large-screen devices ignore requested orientation; the portrait test skips in that case. Synthetic MotionEvents are useful regression checks but don't measure physical S Pen latency, palm rejection, or every pressure profile.

## Two-way sync and assets

Connect both clients to the same disposable board. Enable the desktop's local agent API, and attach to the debug Android WebView:

```sh
export ANDROID_SERIAL=YOUR_DEVICE_SERIAL # optional for one device
./scripts/attach.sh
export TEST_BOARD_ID=YOUR_SHARE_BOARD_ID
export TEST_DOCUMENT_ID=YOUR_DESKTOP_DOCUMENT_ID
node scripts/verify-sync.mjs
node scripts/verify-reconnect-assets.mjs
```

Get the desktop document ID from its agent API's document listing. Tests match it explicitly, and check the Android canvas's board ID before mutating. The default desktop API configuration path is the standard macOS `~/Library/Application Support/tldraw/server.json`; set `TLDRAW_SERVER_CONFIG` for another location. The scripts read its credentials without printing them.

For the native swipe probe, first capture a current UI hierarchy:

```sh
mkdir -p artifacts
adb shell uiautomator dump /sdcard/ink-share-ui.xml
adb pull /sdcard/ink-share-ui.xml artifacts/ui-ink.xml
node scripts/verify-ink.mjs
```

The reconnect probe changes only the WebView's network emulation, restores it, and deletes its test records. The desktop may retain a tiny unreferenced PNG blob until its own garbage collection. These scripts require a running editor and do not start or reconfigure a Share server.

## Observed device baseline

Prior development builds were checked on a Samsung SM-X730 running Android 16 with tldraw offline 1.18.0. Native pressure, landscape left-edge capture, two-way CRUD, PNG assets, and in-process reconnection passed. A controlled small-curve handoff measured pixel IoU of 0.928 after resampling, versus 0.780 before. These are specific synthetic test results, not guarantees for all handwriting.

The 0.3.0 redesign adds browser layout/interaction verification and Android build validation. Physical tablet review of the updated icon and styling remains necessary when a device is connected. A signed production APK also needs its own device smoke test and license validation.
