# Run Poring Pet

## Windows development

From the repository root in PowerShell:

```powershell
npm install
npm start
```

The app opens a transparent Capsule at the top of the primary display. Click it to expand or collapse. Use **Desktop Play** to show the pet next to the Capsule; **Return to Capsule** brings the pet back. The tray menu has **Show / Hide**, display modes, and **Quit**.

To connect GitHub without pasting a token, install GitHub CLI, run `gh auth login` in PowerShell, and complete its browser sign-in. Choose the scopes you want to grant. Open the GitHub shortcut from the Capsule to load live notifications, assigned issues, and recently pushed repositories.

## Windows checks and packaging

```powershell
npm run test:ui
npm run build
```

`test:ui` starts both renderer pages and checks that local Three.js loads, the app API exists, and the full-page demo controls are hidden. `build` writes an installer and a portable executable to `dist/`.

## Android debug APK

From the project root:

```powershell
npm run build:android
adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk
```

Or run `./gradlew assembleDebug` from `android/`. APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

For the Android floating pet, expand the Capsule and tap **Desktop Play**. The app will explain the overlay permission and open its Android settings page. For notifications and system media, add a Notifications shortcut and open it; grant notification access when Android prompts you. The Quick Settings tile can then show or hide the overlay.

This machine has Android SDK platforms 34, 35, and 36. No emulator or USB device was attached during the build, so installation and runtime behavior have not been checked on a device.
