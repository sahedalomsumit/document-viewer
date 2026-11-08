# 📄 Document Viewer

An offline Android PDF reader with drawing and text tools. No ads, accounts, or data collection.

- **Android:** 7.0 (API 24) or later
- **Package:** `com.sahed.document_viewer`
- **Current version:** 1.0.0
- **Google Play listing copy:** [play-description.md](play-description.md)

## ✨ Features

- Open PDFs from the Android file picker or compatible apps.
- Scroll continuously, pinch or double-tap to zoom, and jump to a page.
- Resume reading from your last page, with a continue-reading card and progress for up to 12 recent documents.
- Draw on pages, place typed text, and undo marks.
- Save a separate marked-up PDF without changing the original.
- Read in a dark, emerald-accented interface with bundled DM Sans typography.

Despite its name, Document Viewer currently supports **PDF files only**, not Word documents, spreadsheets, or presentations. The Android app does not request internet permission; developer links open in an external app.

## 📖 Using the app

1. Tap **Open PDF** and choose a document, or open a PDF from Files with Document Viewer.
2. Scroll to read. Pinch or double-tap to zoom, or use the page-jump control.
3. Select **Draw** or **Text** to mark a page. While drawing, use two fingers to move or zoom. Select **Read** to return to reading, or **Undo** to remove the last mark on the current page.
4. Export a marked-up copy before leaving the document; unsaved marks are discarded when you leave.

**Export limitations:** Marked-up copies are flattened into page images. Searchable text, links, forms, and accessibility tags are not preserved. Password-protected, damaged, or unavailable PDFs may not open.

To make the app your default PDF viewer, open a PDF from Files, select Document Viewer in Android's app picker, and choose **Always** when offered. If another app opens automatically, clear that app's defaults in Android Settings first. The app cannot change defaults itself.

## 🎨 Design system

Adapted from `money-tracker-app` for Android Views: bundled DM Sans, dark-only Emerald tokens, 12dp buttons, 18dp cards, 24dp hero containers, and 20dp dialogs bounded to 92% / 600dp. Entrances use the same 380ms fade/24px slide with 35ms staggering; press feedback uses a 0.97 spring scale. System animation preferences are respected. All brand imagery comes from `app/src/main/res/drawable/logo.jpg`.

The library includes a continue-reading card, per-document page progress, and automatic page restore. The reader scrolls continuously through the entire PDF using recycled page views, rendering visible pages off the UI thread into a byte-bounded cache (at most 24 MiB). It caches annotation paths, supports double-tap zoom and bounded panning, and allows two-finger navigation while drawing. Tools include Read, Draw, Text (tap a page to place typed text), and Undo. Added text and drawings are included in flattened exports. Release builds use R8 and resource shrinking.

The home footer shows the installed version and current copyright year, with a developer website link and a continuously pulsing heart (paused in the background and disabled when system animations are off). The overflow menu includes More apps; Make default PDF viewer is hidden while Android resolves PDFs to this app and rechecked whenever the app resumes. A one-time prompt explains Android's user-controlled “Always” selection using a real PDF from Files; the app never changes defaults itself or generates a welcome PDF. Previous onboarding cache files and their recent entries are removed on upgrade. Rounded branding uses `logo_rounded.png`, derived from `logo.jpg`; regenerate this asset when replacing the source logo. The Android 12+ splash uses a transparent safe-area inset to avoid a white surround or circular cropping.

DM Sans is bundled from Google Fonts under the SIL Open Font License (see `app/src/main/assets/fonts/OFL.txt`).

## 🛠️ Build

Use JDK 17 and an Android SDK with platform 34 installed. Configure your local SDK location through Android Studio or `local.properties`; use the included Gradle wrapper.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Run `:app:stageInstallable` to also stage `app.apk` at the project root. Install the APK on an Android device to run the app.

On macOS or Linux, use `./gradlew` instead of `.\gradlew.bat`.

## 🧪 Device regression tests

Connect an Android device or start an emulator before running:

```powershell
.\gradlew.bat :app:connectedDeviceTestAndroidTest
```

Tests use the separate `com.sahed.document_viewer.testbed` app, so Gradle cannot replace or uninstall your normal app or reset its recent-file history. Coverage includes page navigation/resume, rotation with annotations, undo, invalid PDFs, bitmap cache ownership, annotation rendering, zoom/pan bounds, export, developer link destinations, rounded branding, footer metadata, heart animation lifecycle, and the one-time default-viewer prompt. Results: `app/build/reports/androidTests/connected/`.

## 🚀 Signed release

Keep `key.properties` at the project root and the keystore in `app/`. The properties file must contain `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Relative `storeFile` paths resolve from `app/` (for example `storeFile=upload-keystore.jks`). Both files are ignored by Git; keep a secure backup of the keystore and credentials.

```powershell
.\gradlew.bat :app:stageReleaseInstallable :app:bundleRelease
```

Signed APK: `app-release.apk` (also `app/build/outputs/apk/release/app-release.apk`). Play Store bundle: `app/build/outputs/bundle/release/app-release.aab`. Release builds fail when signing credentials are missing; they do not fall back to a debug key. Debug builds do not require release credentials.
