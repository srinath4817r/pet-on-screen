# Poring Pet

Poring Pet is a small, frameless Windows capsule and floating pet, plus an Android app shell. The supplied capsule renderer is preserved in `_source/poring-capsule.html`; the runnable renderer is generated from it with a local Three.js copy, live service connectors, and no sample notifications or synthetic audio.

## Windows

Requirements: Windows 10/11 and Node.js 20 or newer.

```powershell
npm install
npm start
```

The Capsule and pet are transparent, always-on-top windows, with no title bars or taskbar buttons. The capsule opens as a compact pill and expands to its controls; clicking another app collapses it. Pet size is adjusted in expanded Settings and applied with **Confirm**. Right-click the pet for its action menu, including Sleep. Expanded mode follows the cursor by default, with a Random behavior option. In **Desktop Play**, the pet roams around the desktop while the capsule stays available. Only true full-screen apps hide both overlays; pressing `Ctrl+Alt` toggles them, including while full-screen. The app starts with Windows. The tray menu changes modes, hides or shows the app, and quits.

Build the NSIS installer and portable executable with `npm run build`; files are written to `dist/`.

## Android

Requirements: Android SDK platform 35, JDK 17 or newer, and an attached device or emulator for installation.

```powershell
cd android
./gradlew assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Or run `npm run build:android` from the project root. The APK path is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Live data and permissions

The shortcut strip starts with the live WhatsApp, GitHub, and Vercel connectors. No sample notifications, media tracks, networks, or device data are seeded. Choose local audio with the folder control, or use Windows system media when a Windows media session is available. Windows Wi-Fi and Bluetooth details come from Windows scan/device data. Android Wi-Fi scan results and Bluetooth device names require nearby-device permissions; settings buttons open the relevant Android system settings.

On Android, tap **Desktop Play** in the expanded Capsule to enable the floating overlay. Poring Pet explains the draw-over-other-apps permission and opens Android settings. The Quick Settings tile toggles the overlay after permission is granted. To enable real notifications and system media, open the WhatsApp connector or media controls; Android asks for notification access when needed. Notification text stays in process memory and is not written to disk. The GitHub connector accepts a personal access token and stores it encrypted with the Android Keystore.

On Windows, connect a GitHub token in its connector panel, or install GitHub CLI and run `gh auth login`. The app uses the saved token or CLI login for live GitHub notifications, assigned issues, and recently pushed repositories. Connect Vercel from its connector panel with a Vercel access token; Poring Pet stores both tokens using Windows secure storage. Setup instructions appear in each connector panel.

Windows media metadata and transport controls use the active Windows system media session when one is available. WhatsApp notifications are available through Android notification access; Windows notification listening and native Codex approval prompts are not connected. The current Android build includes one live battery/time widget. Empty states explain when a source or permission is unavailable.

The Android build needs the SDK location in `android/local.properties` when `ANDROID_HOME` is not set. This machine-specific file is ignored by Git.
