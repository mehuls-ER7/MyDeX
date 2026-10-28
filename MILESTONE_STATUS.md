# DeX App Milestone Status

## Current Status: M2 - Custom Window Manager (CODE DONE, DEVICE RETEST PENDING)

### Build Status: **CODE DONE – NOT YET RE-VERIFIED ON DEVICE**
M1 fix was merged into main (merge commit 23daf5a, 2026-10-28) and M2
is built on top of that main. As with M1, do not mark M2 COMPLETE on a
compile alone – the retest steps under "M2 retest" below must pass on a
real device first.

### M2 – what was built (2026-10-28)
All in `DesktopContentMinimal.kt` (no new dependencies, no layout/XML
changes, no string changes – all M2 behaviour is in the window layer):
1. **8-direction resize** (was: bottom-right corner only):
   `resizeWindowFrom()` with `ResizeEdge` LEFT/RIGHT/TOP/BOTTOM +
   4 corners. Left/top handles move the origin as well as the size,
   clamped to 320×200 px minimum and x/y ≥ 0. Bottom-right behaviour
   is unchanged, including its corner icon.
2. **Windows-style snap + live preview**: dragging a window's title
   bar within 48 px of an edge/corner shows a cyan preview rectangle;
   on release (`endDrag`) it snaps – left/right = half screen,
   corners = quarter screen, top = full desktop area above the
   56 dp taskbar. Maximized windows do not snap. Dragging a snapped
   window moves it as-is (no pre-snap size restore yet – noted
   limitation, not a silent bug).
3. **Window state persistence**: `WindowPersistence` (SharedPreferences
   `dex_prefs`, `org.json`, no new library) saves open windows
   (id, app, position, size, minimized, maximized, z-order) and the
   focused window on every discrete action and on drag/resize end
   (not per pixel). `DesktopContentMinimal` restores them
   synchronously on first composition; windows for apps no longer in
   the catalogue are dropped. So closing/reopening the desktop brings
   back the same layout instead of an empty desktop.
4. **Multi-window, already present, now exercised by the above**:
   z-order/focus, minimize/maximize/close, taskbar running apps,
   6 internal apps in simultaneous windows.
Not in M2 (explicitly): snap preview while resizing (preview is drag
only), pre-snap restore on drag, window tiling shortcuts, saved
layouts beyond the last layout. Those belong to a later polish pass,
not to a silent M2 claim.

### M2 retest (do this before calling M2 complete)
1. Rebuild in Android Studio, install, start DeX on an external display.
2. Open 2 apps at once, drag one to the left edge → preview → release
   → it must fill the left half; drag another to a corner → quarter.
3. Drag a window to the top edge → full desktop area (not under the
   taskbar); drag it back down → it moves freely again.
4. Resize from all 8 handles, including left and top (window must
   grow/shrink from that side, never below 320×200, never off the
   top/left edge).
5. Close the desktop, reopen it → the same windows, positions and
   sizes must come back (persistence). Close a window, reopen →
   it must NOT come back.
6. If anything above fails, capture logcat first:
   `adb logcat -d | findstr /C:"AndroidRuntime" /C:"DesktopShell"`

### M1 manual-start crash fix (2026-10-28)
Symptom: M0 manual start worked; after the M1 Compose desktop landed,
tapping Start DeX crashed the app, and later taps did nothing.
Root causes, all in the manual path MainActivity → DisplayWatcherService
→ DesktopShellActivity → Presentation + ComposeView:
1. `DesktopPresentation` only set `ViewTreeLifecycleOwner` on its
   ComposeView. A ComposeView outside an Activity/Fragment needs ALL
   ViewTree owners. The saved-state dependency was added to Gradle but
   never wired in code, so composition could throw
   IllegalStateException as soon as it attached on the external display.
   Fix: Presentation now implements LifecycleOwner +
   SavedStateRegistryOwner + OnBackPressedDispatcherOwner, sets all
   three owners before `setContent`, uses the Presentation (not the
   phone Activity) as the ComposeView context, disposes on lifecycle
   destroy, and drives its lifecycle down in `onStop()`.
2. `DisplayWatcherService.isDexRunning` was set to true before launch
   and never cleared when the shell died, so after a crash every later
   Start DeX tap was silently ignored ("DeX already running"). Fix:
   `startDexOnDisplay` now resets the flag if `startActivity` throws,
   and `DesktopShellActivity` broadcasts `ACTION_DEX_STOPPED` on
   destroy, which the service now listens for and clears.
3. Manual fallback (flowchart purple branch) did not exist in the UI:
   with no display detected, the button just opened cast settings.
   Fix: with no display, MainActivity now asks Wired (rescan) or
   Wireless (cast screen); rescan checks `DisplayWatcher.scan()`
   synchronously and starts DeX directly if a display is found.
   `startDex` now uses `startForegroundService` (Android 12+ safe)
   in MainActivity and DexConfirmationActivity.
Files changed: DesktopShellActivity.kt (rewrite of the Presentation
host), DisplayWatcherService.kt, MainActivity.kt,
DexConfirmationActivity.kt, values/strings.xml, values-hi/strings.xml,
README.md (build + logcat steps).

### APK Location
`D:\dex\DexApp\app\build\outputs\apk\debug\app-debug.apk` (~59 MB)

### What Works Now (M0 + M1 Complete)
- ✓ Project structure and Gradle setup with Compose enabled
- ✓ DisplayWatcher with DisplayListener (detects external displays)
- ✓ Foreground service for persistent display monitoring
- ✓ Presentation API working on external display
- ✓ User confirmation dialog (AlertDialog-based)
- ✓ Successful build and deployment
- ✓ English and Hindi string resources
- ✓ Boot receiver for service restart
- ✓ **Full Compose-based desktop environment (M1):**
  - Wallpaper with gradient background
  - Desktop shortcut icons (4 apps)
  - Custom window manager with draggable/resizable windows
  - Title bar with minimize/maximize/close controls
  - Taskbar with Start button, running/pinned apps, system tray (Wi-Fi, battery, clock)
  - Start menu (animated slide-up) with app grid and power options
  - 6 internal demo apps:
    - File Manager (folder listing)
    - Terminal (simulated shell output)
    - Settings (placeholder options)
    - Browser (URL bar placeholder)
    - Calculator (working button grid)
    - Text Editor (editable text area)
  - Window z-order management (focus brings to front)
  - Window maximize/fullscreen toggle
  - Resize handle on windows

### Files Modified/Created
1. DisplayWatcher.kt - ✓ Complete
2. DisplayWatcherService.kt - ✓ Complete
3. DesktopShellActivity.kt - ✓ Complete (Presentation + ComposeView)
4. MainActivity.kt - ✓ Complete
5. DexApplication.kt - ✓ Complete (DataStore setup)
6. DesktopContentMinimal.kt - ✓ **NEW** - Full desktop composable (wallpaper, taskbar, start menu, windows, 6 apps)
7. DesktopComposables.kt - ✓ Updated (shared data models only)
8. DexConfirmationActivity.kt - ✓ Complete
8. activity_main.xml - ✓ Created (XML layout)
9. Local.properties - ✓ Created (SDK path)
10. build.gradle.kts - ✓ Added `buildFeatures { compose = true }`

### Retest after the fix (do this before calling M1 complete again)
1. Rebuild in Android Studio and install the new debug APK.
2. With NO display: open the app, tap the action button → you must
   get the "Start DeX manually" choice (Wired rescan / Wireless cast),
   not a crash and not a dead button.
3. With a display connected: tap Start DeX → desktop must appear on
   the external display (wallpaper + taskbar), with no crash.
4. If it still crashes, capture logcat before reopening the app:
   `adb logcat -d | findstr /C:"AndroidRuntime" /C:"DesktopShell" /C:"DisplayWatcher"`
   and paste the `FATAL EXCEPTION` block – that block, not a
   description, is what confirms the next fix.
5. **Install on device**: `adb install D:\dex\DexApp\app\build\outputs\apk\debug\app-debug.apk`
2. **Grant permissions**:
   - Foreground service permission (auto-granted for specialUse)
   - Draw over other apps (for Presentation)
   - Notification permission (Android 13+)
3. **Test display detection**:
   - Connect external display via Miracast
   - App should detect it and show confirmation dialog
   - Confirm to launch DeX on external display
4. **Test desktop interaction**:
   - Click desktop icons to open apps
   - Drag windows by title bar
   - Resize windows from bottom-right corner
   - Minimize/maximize/close windows
   - Click taskbar items to switch/focus
   - Open start menu from Start button
   - Test all 6 demo apps

### M2 Deliverables (DONE IN CODE, see "M2 – what was built" above)
- Window snap to edges/corners (Windows-style) ✓ code
- Multi-window management improvements ✓ code
- More internal apps – already 6 from M1, no new app added in M2
- Better window state persistence ✓ code

### M3-M6 Planned
- M3: Embedded ADB client + onboarding wizard
- M4: System app launcher + freeform test
- M5: Taskbar polish, notifications panel
- M6: Real Miracast testing on Realme 10 Pro Plus

---
Last Updated: 2026-10-28
Build Status: ⚠️ M2 CODE DONE, DEVICE RETEST PENDING