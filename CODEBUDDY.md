# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## What this project is

`keydroidx-foucs` (package `io.github.cctyl.keydroidx.focus`) is a **quick-validation prototype** for a "modern feature phone" (现代功能机) focus-navigation experience on Android. It does NOT modify third-party apps. Instead, an AccessibilityService reads the on-screen `AccessibilityNodeInfo` tree and maintains its own in-memory "virtual focus" pointer. A physical D-pad / arrow keys moves an orange focus box between clickable controls; the confirm key dispatches `ACTION_CLICK` directly on the node (no screen coordinates). Long-press confirm → `ACTION_LONG_CLICK`; back key → `GLOBAL_ACTION_BACK`.

It is part of the KeydroidX (原键) ecosystem, but this module is intentionally **standalone** — it does not depend on `keydroidx-core` / `nokia-*` libraries. It was scaffolded from `keydroidx-core` (Gradle wrapper, `test.jks`, mirror config, signing) but only reuses that project structure, not its UI/SDK.

The design rationale and MVP acceptance criteria live in `README.md` (the prototype design doc). Implement against those criteria: focus box must wrap the selected control; confirm must open the corresponding page; no touch required.

## Build & run commands (Windows dev env)

Run Gradle via `gradlew.bat`, **not** `./gradlew`. The `gradlew` shell script passes a Unix-style classpath to the Windows JVM and fails with `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`.

- Build debug APK: `./gradlew.bat assembleDebug`
- Lint: `./gradlew.bat lint`
- Install (device already on `192.168.1.8:5555` in this env): `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Launch the app: `adb shell am start -n io.github.cctyl.keydroidx.focus/.MainActivity`
- Live logs: `adb logcat | grep FocusNavigationService`

There are **no unit tests** yet. When added, `./gradlew.bat testDebugUnitTest` runs them; a single class via `./gradlew.bat testDebugUnitTest --tests "io.github.cctyl.keydroidx.focus.FocusNavigatorTest"`.

`local.properties` and `gradle.properties` are git-ignored and contain machine-specific paths (SDK dir, JDK 17 home) and `test.jks` signing credentials — do not commit them.

## Code architecture

Single Gradle module `:app`. Key source under `app/src/main/java/io/github/cctyl/keydroidx/focus/`:

- **`FocusNavigationService.java`** — the heart. An `AccessibilityService` that:
  - `onKeyEvent` consumes `DPAD_UP/DOWN/LEFT/RIGHT`, `DPAD_CENTER`/`ENTER` (short-press = click, long-press ≥500ms = long-click), and `BACK`.
  - Holds the virtual focus as `AccessibilityNodeInfo currentFocusNode` (an `obtain()`-ed copy; the old one is `recycle()`-d when focus moves).
  - `onAccessibilityEvent`: on `TYPE_WINDOW_STATE_CHANGED` it discards the current node and re-locates focus (new screen); on content/scroll changes it re-locates only if the current node fails `refresh()`.
  - Skips its **own** package (`isOurOwnApp()`) so the app's UI stays usable for configuration.
  - Gated by `NavigationPrefs.isEnabled()` — the service can be ON in system settings but inert until the in-app toggle is on.
- **`FocusNavigator.java`** — pure algorithm, no Android service state:
  - `collectClickableNodes(root, out)` — recursive tree walk, keeps nodes that are `visibleToUser` + `clickable` and larger than 20×20 px.
  - `findInitialFocus(nodes)` — picks the topmost-then-leftmost node as the entry point.
  - `findNextFocus(current, direction, all)` — geometric nearest-neighbor: in the pressed direction, minimizes `2*primaryAxis + secondaryAxis` distance. Returns `null` if no candidate (focus stays put).
- **`FocusOverlay.java`** — a `View` that draws the orange rounded-rect focus box (`#FF9100` border + translucent fill).
- **`MainActivity.java`** — launcher/configuration screen: button to `Settings.ACTION_ACCESSIBILITY_SETTINGS`, a Switch bound to `NavigationPrefs`, and a status check via `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
- **`NavigationPrefs.java`** — `SharedPreferences` flag `enabled` that arms/disarms navigation.

Wiring worth knowing:
- The overlay is added through `WindowManager` with `TYPE_ACCESSIBILITY_OVERLAY` (no extra permission) — this is why `minSdk = 27` in `app/build.gradle` (root `ext.MIN_SDK` is 27 here, overriding core's 19).
- `app/build.gradle` signing uses `rootProject.file("test.jks")` — the keystore is at the **repo root**, not in `app/`, so `storeFile` must resolve against `rootProject`, not the module dir.
- `res/xml/accessibility_service_config.xml` declares `canRetrieveWindowContent` + `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` and the event types the service listens to.

## Known limitations (from README, relevant when extending)

Pure geometry jumping can feel unnatural; Flutter/game self-drawn UIs expose no node tree (no node = no focus); some apps disable accessibility. Future work: semantic/type-aware ordering, scroll-follow, edge highlight fallback for secured pages.
