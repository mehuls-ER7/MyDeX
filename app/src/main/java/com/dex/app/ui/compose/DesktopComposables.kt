package com.dex.app.ui.compose

/**
 * Shared data classes used across the desktop environment.
 * M1: Component implementations moved to DesktopContentMinimal.kt
 * This file retains data models referenced by DisplayWatcherService and other modules.
 */

/**
 * Represents a currently running app in the taskbar.
 */
data class RunningApp(
    val id: String,
    val name: String,
    val isMinimized: Boolean = false
)

/**
 * App catalogue item for start menu and launcher.
 */
data class AppItem(
    val name: String,
    val iconName: String,
    val id: String
)

/**
 * Map app ID to display name. Used by components that need
 * app names outside of Compose context.
 */
fun getAppDisplayName(appId: String): String = when (appId) {
    "file_manager" -> "File Manager"
    "terminal"     -> "Terminal"
    "settings"     -> "Settings"
    "browser"      -> "Browser"
    "calculator"   -> "Calculator"
    "text_editor"  -> "Text Editor"
    else           -> appId
}
