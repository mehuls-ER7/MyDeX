# MyDeX

An Android app that turns a phone into a Samsung DeX-style desktop on an
external display: display detection, a confirmation dialog, and a
Jetpack Compose desktop (wallpaper, taskbar, start menu, draggable /
resizable windows, 6 internal demo apps) shown via the Presentation API
on the external display. No PC is involved. Wireless (Miracast) only on
devices like the Realme 10 Pro Plus, which has no wired video out.

## Manual start flow

```
Display connected
  ├─ Yes (auto-detected) → Auto-launch desktop, no taps needed
  └─ No  (auto trigger failed) → Open app manually
        ├─ Wired DeX → Rescan for the display, then start DeX
        └─ Wireless DeX → Cast screen, then start DeX
              └─ Desktop shell (taskbar, windows, apps)
```

If auto-detection misses a display, the app's action button offers the
manual choice above instead of jumping straight to cast settings.

## Build

Open the project in Android Studio (JDK 17, Android SDK 34) and run the
`app` configuration, or:

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Muse / this workspace cannot build the APK: it has no Android SDK or
JDK, and it cannot reach a local PC drive. Build on the dev machine.

## If Start DeX crashes

Do not just describe it – capture the fatal block first, before
reopening the app:

```
adb logcat -d | findstr /C:"AndroidRuntime" /C:"DesktopShell" /C:"DisplayWatcher"
```

Paste the `FATAL EXCEPTION` block. That is what identifies whether the
crash is in the Presentation/Compose host, in display detection, or in
the service launch.

## Status

See `MILESTONE_STATUS.md`. M1 was marked complete, then a manual
Start DeX crash was found and fixed in code (Presentation ViewTree
owners, service "already running" flag, manual Wired/Wireless fallback);
M1 is complete again only after the device retest in that file passes.
