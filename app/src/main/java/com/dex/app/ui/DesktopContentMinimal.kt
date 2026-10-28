package com.dex.app.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.dex.app.ui.compose.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ──────────────────────────────────────────────────────────────
// Color palette (dark desktop theme)
// ──────────────────────────────────────────────────────────────
private val DesktopBg       = Color(0xFF0D1B2A)
private val TaskbarBg       = Color(0xCC0A1626)  // semi-transparent
private val StartMenuBg     = Color(0xFF1A2B3E)
private val WindowBgFocused = Color(0xFF1A2B3E)
private val WindowBgBlurred = Color(0xFF142232)
private val TitleBarFocused = Color(0xFF1E3048)
private val TitleBarBlurred = Color(0xFF16283A)
private val AccentCyan      = Color(0xFF00D4FF)
private val AccentGreen     = Color(0xFF00FF88)
private val TextDim         = Color(0xFF88AACC)
private val TextWhite       = Color.White
private val CloseRed        = Color(0xFFE81123)
private val IconPlaceholder = Color(0xFF6688AA)

// ──────────────────────────────────────────────────────────────
// Window state model (Compose-tracked)
// ──────────────────────────────────────────────────────────────
data class DexWindowState(
    val id: Int,
    val appId: String,
    val title: String,
    val x: Float = 120f,
    val y: Float = 80f,
    val width: Float = 720f,
    val height: Float = 480f,
    val isMinimized: Boolean = false,
    val isMaximized: Boolean = false,
    val zIndex: Int = 0,
)

/** Geometry of the snap preview shown while a window is dragged near an edge/corner. */
data class DexSnapRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** One of the 8 resize handles on a window frame. */
enum class ResizeEdge {
    LEFT, RIGHT, TOP, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
}

// ──────────────────────────────────────────────────────────────
// Desktop-level state holder
// ──────────────────────────────────────────────────────────────
class DesktopState {
    var windows     by mutableStateOf(listOf<DexWindowState>())
        private set
    var focusedId   by mutableStateOf(-1)
        private set
    var nextZ       by mutableStateOf(100)
        private set
    var nextId      by mutableStateOf(1)
        private set
    var startMenuOpen by mutableStateOf(false)

    // ---- M2: screen geometry + snap preview + persistence hook ----
    // Screen size in pixels, set by DesktopContentMinimal from
    // BoxWithConstraints. 0 until first layout – snap is a no-op then.
    var screenWidthPx by mutableStateOf(0f)
        private set
    var screenHeightPx by mutableStateOf(0f)
        private set
    // Taskbar height in pixels, set alongside the screen size.
    var taskbarHeightPx by mutableStateOf(0f)
        private set

    /** Live snap preview while dragging, or null when not near a snap zone. */
    var snapPreview by mutableStateOf<DexSnapRect?>(null)
        private set

    /** Set by DesktopContentMinimal to the WindowPersistence.save call. */
    var saveAction: (() -> Unit)? = null

    fun setScreenSize(widthPx: Float, heightPx: Float, taskbarPx: Float) {
        screenWidthPx = widthPx
        screenHeightPx = heightPx
        taskbarHeightPx = taskbarPx
    }

    /** Save now. Called at drag/resize end and after discrete actions. */
    fun persist() {
        saveAction?.invoke()
    }

    private fun changed() {
        saveAction?.invoke()
    }

    fun restoreFrom(saved: SavedDesktop) {
        if (saved.windows.isEmpty()) return
        // Drop windows for apps that no longer exist in the catalogue.
        val known = appCatalogue.map { it.id }.toSet()
        val valid = saved.windows.filter { it.appId in known }
        if (valid.isEmpty()) return
        windows = valid
        focusedId = saved.focusedId.takeIf { id -> valid.any { it.id == id } } ?: -1
        nextId = (valid.maxOfOrNull { it.id } ?: 0) + 1
        nextZ = (valid.maxOfOrNull { it.zIndex } ?: 100) + 1
    }

    // ---- app catalogue (internal M1 apps) ----
    data class AppEntry(val id: String, val title: String, val icon: ImageVector)

    val appCatalogue = listOf(
        AppEntry("file_manager", "File Manager", Icons.Default.Folder),
        AppEntry("terminal",     "Terminal",      Icons.Default.Terminal),
        AppEntry("settings",     "Settings",      Icons.Default.Settings),
        AppEntry("browser",      "Browser",       Icons.Default.Language),
        AppEntry("calculator",   "Calculator",    Icons.Default.Calculate),
        AppEntry("text_editor",  "Text Editor",   Icons.Default.EditNote),
    )

    val runningApps: List<RunningApp>
        get() = windows.filter { !it.isMinimized }.map { RunningApp(it.appId, it.title) }

    // ---- actions ----
    fun openApp(appId: String) {
        // Already open and visible? focus it
        windows.find { it.appId == appId && !it.isMinimized }?.let {
            focus(it.id); return
        }
        // Minimized? restore
        windows.find { it.appId == appId && it.isMinimized }?.let {
            windows = windows.map { w ->
                if (w.id == it.id) w.copy(isMinimized = false, zIndex = nextZ++) else w
            }
            focus(it.id); return
        }
        // New window
        val cascade = windows.size * 32f
        val w = DexWindowState(
            id     = nextId++,
            appId  = appId,
            title  = appCatalogue.find { it.id == appId }?.title ?: appId,
            x      = 120f + cascade,
            y      = 80f  + cascade,
            zIndex = nextZ++,
        )
        windows = windows + w
        focus(w.id)
        changed()
    }

    fun closeWindow(id: Int) {
        windows = windows.filter { it.id != id }
        if (focusedId == id) focusedId = windows.maxByOrNull { it.zIndex }?.id ?: -1
        changed()
    }

    fun minimizeWindow(id: Int) {
        windows = windows.map { if (it.id == id) it.copy(isMinimized = true) else it }
        if (focusedId == id) focusedId = windows.filter { !it.isMinimized }.maxByOrNull { it.zIndex }?.id ?: -1
        changed()
    }

    fun toggleMaximize(id: Int) {
        windows = windows.map {
            if (it.id == id) it.copy(isMaximized = !it.isMaximized) else it
        }
        // Leaving a snapped/maximized window clears any stale preview.
        snapPreview = null
        changed()
    }

    fun focus(id: Int) {
        focusedId = id
        windows = windows.map {
            if (it.id == id) it.copy(zIndex = nextZ++) else it
        }
        changed()
    }

    fun moveWindow(id: Int, dx: Float, dy: Float) {
        windows = windows.map {
            if (it.id == id) it.copy(x = (it.x + dx).coerceAtLeast(0f), y = (it.y + dy).coerceAtLeast(0f)) else it
        }
        // Live snap preview while dragging (no save per pixel – see endDrag).
        windows.find { it.id == id }?.let { snapPreview = snapTargetFor(it) }
    }

    fun resizeWindow(id: Int, dw: Float, dh: Float) {
        resizeWindowFrom(id, ResizeEdge.BOTTOM_RIGHT, dw, dh)
    }

    /**
     * M2: resize from any of the 8 frame handles. Left/top handles move
     * the window origin as well as its size, clamped to the minimum size
     * (320x200 px) and to x/y >= 0. Bottom-right behaviour is unchanged.
     */
    fun resizeWindowFrom(id: Int, edge: ResizeEdge, dx: Float, dy: Float) {
        windows = windows.map { w ->
            if (w.id != id) return@map w
            var nx = w.x
            var ny = w.y
            var nw = w.width
            var nh = w.height
            when (edge) {
                ResizeEdge.RIGHT -> nw = (w.width + dx).coerceAtLeast(320f)
                ResizeEdge.BOTTOM -> nh = (w.height + dy).coerceAtLeast(200f)
                ResizeEdge.BOTTOM_RIGHT -> {
                    nw = (w.width + dx).coerceAtLeast(320f)
                    nh = (w.height + dy).coerceAtLeast(200f)
                }
                ResizeEdge.LEFT -> {
                    val newW = (w.width - dx).coerceAtLeast(320f)
                    nx = (w.x + (w.width - newW)).coerceAtLeast(0f)
                    nw = newW
                }
                ResizeEdge.TOP -> {
                    val newH = (w.height - dy).coerceAtLeast(200f)
                    ny = (w.y + (w.height - newH)).coerceAtLeast(0f)
                    nh = newH
                }
                ResizeEdge.TOP_LEFT -> {
                    val newW = (w.width - dx).coerceAtLeast(320f)
                    val newH = (w.height - dy).coerceAtLeast(200f)
                    nx = (w.x + (w.width - newW)).coerceAtLeast(0f)
                    ny = (w.y + (w.height - newH)).coerceAtLeast(0f)
                    nw = newW
                    nh = newH
                }
                ResizeEdge.TOP_RIGHT -> {
                    val newH = (w.height - dy).coerceAtLeast(200f)
                    ny = (w.y + (w.height - newH)).coerceAtLeast(0f)
                    nw = (w.width + dx).coerceAtLeast(320f)
                    nh = newH
                }
                ResizeEdge.BOTTOM_LEFT -> {
                    val newW = (w.width - dx).coerceAtLeast(320f)
                    nx = (w.x + (w.width - newW)).coerceAtLeast(0f)
                    nw = newW
                    nh = (w.height + dy).coerceAtLeast(200f)
                }
            }
            w.copy(x = nx, y = ny, width = nw, height = nh)
        }
    }

    // ---- M2: Windows-style snap ----
    // Zone: within 48 px of an edge/corner when the drag ends.
    // Top edge -> maximize (full desktop area above the taskbar).
    // Left/right -> half screen. Corners -> quarter screen.
    private val snapZonePx = 48f

    private fun desktopHeightPx(): Float =
        (screenHeightPx - taskbarHeightPx).coerceAtLeast(200f)

    fun snapTargetFor(win: DexWindowState): DexSnapRect? {
        if (screenWidthPx <= 0f || screenHeightPx <= 0f || win.isMaximized) return null
        val h = desktopHeightPx()
        val nearLeft = win.x <= snapZonePx
        val nearRight = win.x + win.width >= screenWidthPx - snapZonePx
        val nearTop = win.y <= snapZonePx
        val nearBottom = win.y + win.height >= h - snapZonePx
        return when {
            nearLeft && nearTop -> DexSnapRect(0f, 0f, screenWidthPx / 2f, h / 2f)
            nearRight && nearTop -> DexSnapRect(screenWidthPx / 2f, 0f, screenWidthPx / 2f, h / 2f)
            nearLeft && nearBottom -> DexSnapRect(0f, h / 2f, screenWidthPx / 2f, h / 2f)
            nearRight && nearBottom -> DexSnapRect(screenWidthPx / 2f, h / 2f, screenWidthPx / 2f, h / 2f)
            nearLeft -> DexSnapRect(0f, 0f, screenWidthPx / 2f, h)
            nearRight -> DexSnapRect(screenWidthPx / 2f, 0f, screenWidthPx / 2f, h)
            nearTop -> DexSnapRect(0f, 0f, screenWidthPx, h)
            else -> null
        }
    }

    /** Apply the snap on drag release, clear the preview, and persist. */
    fun endDrag(id: Int) {
        val win = windows.find { it.id == id }
        val target = win?.let { snapTargetFor(it) }
        if (win != null && target != null) {
            windows = windows.map {
                if (it.id == id) it.copy(
                    x = target.x, y = target.y,
                    width = target.width, height = target.height,
                    isMaximized = false,
                ) else it
            }
        }
        snapPreview = null
        persist()
    }

    fun taskbarClick(appId: String) {
        val w = windows.find { it.appId == appId }
        if (w == null) { openApp(appId); return }
        if (w.isMinimized) {
            windows = windows.map { if (it.id == w.id) it.copy(isMinimized = false) else it }
            focus(w.id)
        } else if (w.id == focusedId) {
            minimizeWindow(w.id)
        } else {
            focus(w.id)
        }
        changed()
    }
}

// ──────────────────────────────────────────────────────────────
// M2: window persistence (SharedPreferences + org.json, no new deps)
// ──────────────────────────────────────────────────────────────
data class SavedDesktop(
    val windows: List<DexWindowState>,
    val focusedId: Int,
)

object WindowPersistence {
    private const val PREFS = "dex_prefs"
    private const val KEY_WINDOWS = "m2_windows_json"
    private const val KEY_FOCUSED = "m2_focused_id"

    fun save(context: Context, state: DesktopState) {
        try {
            val arr = JSONArray()
            state.windows.forEach { w ->
                arr.put(JSONObject().apply {
                    put("id", w.id)
                    put("appId", w.appId)
                    put("title", w.title)
                    put("x", w.x.toDouble())
                    put("y", w.y.toDouble())
                    put("width", w.width.toDouble())
                    put("height", w.height.toDouble())
                    put("minimized", w.isMinimized)
                    put("maximized", w.isMaximized)
                    put("zIndex", w.zIndex)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_WINDOWS, arr.toString())
                .putInt(KEY_FOCUSED, state.focusedId)
                .apply()
        } catch (_: Exception) {
            // Persistence must never crash the desktop.
        }
    }

    fun load(context: Context): SavedDesktop {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_WINDOWS, null) ?: return SavedDesktop(emptyList(), -1)
            val arr = JSONArray(raw)
            val list = mutableListOf<DexWindowState>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    DexWindowState(
                        id = o.getInt("id"),
                        appId = o.getString("appId"),
                        title = o.optString("title", o.getString("appId")),
                        x = o.getDouble("x").toFloat(),
                        y = o.getDouble("y").toFloat(),
                        width = o.getDouble("width").toFloat(),
                        height = o.getDouble("height").toFloat(),
                        isMinimized = o.optBoolean("minimized", false),
                        isMaximized = o.optBoolean("maximized", false),
                        zIndex = o.optInt("zIndex", 100),
                    )
                )
            }
            SavedDesktop(list, prefs.getInt(KEY_FOCUSED, -1))
        } catch (_: Exception) {
            SavedDesktop(emptyList(), -1)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Root composable: wallpaper → windows → start menu → taskbar
// ──────────────────────────────────────────────────────────────
@Composable
fun DesktopContentMinimal() {
    val context = LocalContext.current
    // M2: restore saved windows synchronously on first composition.
    val state = remember { DesktopState().also { it.restoreFrom(WindowPersistence.load(context)) } }
    // Persistence hook for all discrete actions (open/close/min/max/focus).
    state.saveAction = { WindowPersistence.save(context, state) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Screen size in pixels for snap math; taskbar is 56 dp tall.
        LaunchedEffect(maxWidth, maxHeight) {
            with(density) {
                state.setScreenSize(
                    maxWidth.toPx(), maxHeight.toPx(), 56.dp.toPx()
                )
            }
        }

        // 1) Wallpaper
        DesktopWallpaperFull()

        // 2) Desktop icons
        DesktopIcons(state)

        // 3) Windows layer
        WindowLayer(state)

        // 3b) M2 snap preview (shown while dragging near an edge/corner)
        state.snapPreview?.let { preview ->
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(preview.x.roundToInt(), preview.y.roundToInt())
                    }
                    .width(with(density) { preview.width.toDp() })
                    .height(with(density) { preview.height.toDp() })
                    .zIndex(5000f)
                    .background(AccentCyan.copy(alpha = 0.18f))
                    .border(2.dp, AccentCyan.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            )
        }

        // 4) Start menu (animated overlay, anchored bottom-left above taskbar)
        AnimatedVisibility(
            visible = state.startMenuOpen,
            enter   = slideInVertically(initialOffsetY = { it / 4 }) + fadeIn(tween(200)),
            exit    = slideOutVertically(targetOffsetY  = { it / 4 }) + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 64.dp)
                .zIndex(9000f),
        ) {
            StartMenuPanel(state)
        }

        // Click-away to close start menu
        if (state.startMenuOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(8999f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { state.startMenuOpen = false }
            )
        }

        // 5) Taskbar (always on bottom)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(10000f),
        ) {
            DesktopTaskbarFull(state)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Wallpaper (gradient background)
// ──────────────────────────────────────────────────────────────
@Composable
private fun DesktopWallpaperFull() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D1B2A),
                        Color(0xFF1B2838),
                        Color(0xFF0D1B2A),
                    )
                )
            )
    )
}

// ──────────────────────────────────────────────────────────────
// Desktop shortcut icons
// ──────────────────────────────────────────────────────────────
@Composable
private fun DesktopIcons(state: DesktopState) {
    Column(
        modifier = Modifier
            .padding(24.dp)
            .zIndex(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.appCatalogue.take(4).forEach { app ->
            DesktopShortcut(
                icon  = app.icon,
                label = app.title,
                onClick = { state.openApp(app.id) },
            )
        }
    }
}

@Composable
private fun DesktopShortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(AccentCyan.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = AccentCyan, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label, fontSize = 11.sp, color = TextWhite,
            maxLines = 2, lineHeight = 13.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Windows layer
// ──────────────────────────────────────────────────────────────
@Composable
private fun WindowLayer(state: DesktopState) {
    val sorted = state.windows.sortedBy { it.zIndex }
    sorted.forEach { win ->
        if (!win.isMinimized) {
            key(win.id) {
                DexWindow(
                    win     = win,
                    isFocused = win.id == state.focusedId,
                    state   = state,
                )
            }
        }
    }
}

@Composable
private fun DexWindow(win: DexWindowState, isFocused: Boolean, state: DesktopState) {
    val density = LocalDensity.current

    // When maximized, fill entire screen minus taskbar
    val effectiveX      = if (win.isMaximized) 0f else win.x
    val effectiveY      = if (win.isMaximized) 0f else win.y
    val effectiveWidth  = if (win.isMaximized) 9999f else win.width    // will be constrained by fillMaxSize
    val effectiveHeight = if (win.isMaximized) 9999f else win.height

    Box(
        modifier = Modifier
            .zIndex(win.zIndex.toFloat())
            .offset { IntOffset(effectiveX.roundToInt(), effectiveY.roundToInt()) }
            .then(
                if (win.isMaximized) Modifier.fillMaxWidth().fillMaxHeight(0.92f)
                else Modifier
                    .width(with(density) { effectiveWidth.toDp() })
                    .height(with(density) { effectiveHeight.toDp() })
            )
            .shadow(if (isFocused) 12.dp else 4.dp, RoundedCornerShape(8.dp))
            .background(
                if (isFocused) WindowBgFocused else WindowBgBlurred,
                RoundedCornerShape(8.dp),
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { state.focus(win.id) },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Title bar (draggable)
            DexTitleBar(
                title     = win.title,
                isFocused = isFocused,
                isMaximized = win.isMaximized,
                onDrag    = { dx, dy -> state.moveWindow(win.id, dx, dy) },
                onDragEnd = { state.endDrag(win.id) },
                onClose   = { state.closeWindow(win.id) },
                onMinimize = { state.minimizeWindow(win.id) },
                onMaximize = { state.toggleMaximize(win.id) },
            )

            // Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .background(Color(0xFF0F1923))
                    .padding(1.dp),
            ) {
                AppContent(win.appId)
            }
        }

        // M2: 8 resize handles (4 edges + 4 corners). Edge strips are
        // 12 dp wide/tall so they are easy to grab; corners keep the icon.
        if (!win.isMaximized) {
            // Edges
            ResizeHandle(
                edge = ResizeEdge.LEFT,
                modifier = Modifier.align(Alignment.CenterStart)
                    .fillMaxHeight().width(12.dp),
                winId = win.id, state = state,
            )
            ResizeHandle(
                edge = ResizeEdge.RIGHT,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight().width(12.dp),
                winId = win.id, state = state,
            )
            ResizeHandle(
                edge = ResizeEdge.TOP,
                modifier = Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth().height(12.dp),
                winId = win.id, state = state,
            )
            ResizeHandle(
                edge = ResizeEdge.BOTTOM,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().height(12.dp),
                winId = win.id, state = state,
            )
            // Corners
            ResizeHandle(
                edge = ResizeEdge.TOP_LEFT,
                modifier = Modifier.align(Alignment.TopStart).size(20.dp),
                winId = win.id, state = state,
            )
            ResizeHandle(
                edge = ResizeEdge.TOP_RIGHT,
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                winId = win.id, state = state,
            )
            ResizeHandle(
                edge = ResizeEdge.BOTTOM_LEFT,
                modifier = Modifier.align(Alignment.BottomStart).size(20.dp),
                winId = win.id, state = state,
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.BottomEnd)
                    .pointerInput(win.id) {
                        detectDragGestures(
                            onDragEnd = { state.persist() },
                        ) { change, dragAmount ->
                            change.consume()
                            state.resizeWindowFrom(win.id, ResizeEdge.BOTTOM_RIGHT, dragAmount.x, dragAmount.y)
                        }
                    },
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "Resize",
                    tint = TextDim.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp).align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun ResizeHandle(
    edge: ResizeEdge,
    modifier: Modifier,
    winId: Int,
    state: DesktopState,
) {
    Box(
        modifier = modifier.pointerInput(winId, edge) {
            detectDragGestures(
                onDragEnd = { state.persist() },
            ) { change, dragAmount ->
                change.consume()
                state.resizeWindowFrom(winId, edge, dragAmount.x, dragAmount.y)
            }
        }
    )
}

@Composable
private fun DexTitleBar(
    title: String,
    isFocused: Boolean,
    isMaximized: Boolean,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(
                if (isFocused) TitleBarFocused else TitleBarBlurred,
                RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    if (!isMaximized) onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon placeholder
        Box(
            Modifier
                .size(20.dp)
                .background(AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(8.dp))

        // Title
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) TextWhite else TextDim,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )

        // Window controls
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            WinCtrl(Icons.Default.Remove,         "Minimize",                 onMinimize)
            WinCtrl(
                if (isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                if (isMaximized) "Restore" else "Maximize",
                onMaximize,
            )
            WinCtrl(Icons.Default.Close, "Close", onClose, isClose = true)
        }
    }
}

@Composable
private fun WinCtrl(icon: ImageVector, desc: String, onClick: () -> Unit, isClose: Boolean = false) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (isClose) CloseRed else Color(0xFFCCCCCC), modifier = Modifier.size(16.dp))
    }
}

// ──────────────────────────────────────────────────────────────
// App content router
// ──────────────────────────────────────────────────────────────
@Composable
private fun AppContent(appId: String) {
    when (appId) {
        "file_manager" -> FileManagerContent()
        "terminal"     -> TerminalContent()
        "settings"     -> SettingsContent()
        "browser"      -> BrowserContent()
        "calculator"   -> CalculatorContent()
        "text_editor"  -> TextEditorContent()
        else           -> Text("Unknown app: $appId", color = TextWhite, modifier = Modifier.padding(16.dp))
    }
}

// ──────────── Internal apps ────────────

@Composable
private fun FileManagerContent() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Path bar
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFF162030), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Home, "Home", tint = AccentCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("/home/user", color = TextDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(12.dp))
        // Folders
        val items = listOf("Documents" to Icons.Default.Folder, "Downloads" to Icons.Default.Download,
            "Pictures" to Icons.Default.Image, "Music" to Icons.Default.MusicNote,
            "Videos" to Icons.Default.VideoFile, "Desktop" to Icons.Default.DesktopWindows)
        items.forEach { (name, icon) ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, name, tint = AccentCyan.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(name, color = TextWhite, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun TerminalContent() {
    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF0A0E14))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val lines = listOf(
            "user@dex:~$" to " ls -la",
            "" to "total 24",
            "" to "drwxr-xr-x  2 user user 4096 Oct 27 10:00 .",
            "" to "drwxr-xr-x  5 user user 4096 Oct 27 09:55 ..",
            "" to "-rw-r--r--  1 user user  220 Oct 27 10:00 .bashrc",
            "" to "drwxr-xr-x  2 user user 4096 Oct 27 10:00 Documents",
            "" to "drwxr-xr-x  2 user user 4096 Oct 27 10:00 Downloads",
            "user@dex:~$" to " _",
        )
        lines.forEach { (prompt, cmd) ->
            Row {
                if (prompt.isNotEmpty()) {
                    Text(prompt, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = AccentGreen)
                }
                Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextWhite)
            }
        }
    }
}

@Composable
private fun SettingsContent() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DeX Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextWhite)
        SettingRow("Performance Mode", "Auto (Recommended)")
        SettingRow("Language", "English")
        SettingRow("Auto-start on display connect", "Off")
        SettingRow("Show confirmation dialog", "On")
        Spacer(Modifier.height(8.dp))
        Text("Version 1.0 – M1", color = TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xFF162030), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextWhite, fontSize = 14.sp)
        Text(value, color = AccentCyan, fontSize = 14.sp)
    }
}

@Composable
private fun BrowserContent() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // URL bar
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFF162030), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Lock, "Secure", tint = AccentGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("https://example.com", color = TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Refresh, "Refresh", tint = TextDim, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Browser placeholder – M2 will integrate WebView", color = TextDim, fontSize = 14.sp)
    }
}

@Composable
private fun CalculatorContent() {
    var display by remember { mutableStateOf("0") }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Display
        Box(
            Modifier.fillMaxWidth()
                .background(Color(0xFF0A0E14), RoundedCornerShape(8.dp))
                .padding(16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(display, fontSize = 32.sp, color = TextWhite, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        // Button grid
        val rows = listOf(
            listOf("C", "±", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "=", ""),
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { btn ->
                    if (btn.isNotEmpty()) {
                        val isOp = btn in listOf("÷", "×", "−", "+", "=")
                        Box(
                            Modifier
                                .weight(if (btn == "0") 2f else 1f)
                                .height(48.dp)
                                .background(
                                    if (isOp) AccentCyan.copy(alpha = 0.3f) else Color(0xFF1E3048),
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    display = if (btn == "C") "0"
                                    else if (display == "0" && btn !in listOf("÷","×","−","+","=",".")) btn
                                    else display + btn
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(btn, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Spacer(Modifier.weight(1f)) // empty slot
                    }
                }
            }
        }
    }
}

@Composable
private fun TextEditorContent() {
    var text by remember { mutableStateOf("Welcome to DeX Text Editor.\n\nStart typing here...") }
    Column(Modifier.fillMaxSize()) {
        // Toolbar
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFF162030))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(Icons.Default.Save to "Save", Icons.Default.ContentCopy to "Copy",
                Icons.Default.ContentPaste to "Paste").forEach { (icon, label) ->
                Icon(icon, label, tint = TextDim, modifier = Modifier.size(20.dp).clickable { })
            }
        }
        // Editor area
        Box(
            Modifier.fillMaxSize()
                .background(Color(0xFF0A0E14))
                .padding(12.dp),
        ) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = TextWhite, lineHeight = 20.sp)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Start Menu panel
// ──────────────────────────────────────────────────────────────
@Composable
private fun StartMenuPanel(state: DesktopState) {
    Card(
        modifier = Modifier.width(340.dp).heightIn(max = 520.dp),
        colors = CardDefaults.cardColors(containerColor = StartMenuBg),
        shape  = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // Header
            Text("Start", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextWhite)
            Spacer(Modifier.height(12.dp))

            // Search bar
            Row(
                Modifier.fillMaxWidth()
                    .background(DesktopBg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, "Search", tint = TextDim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Search apps…", fontSize = 14.sp, color = TextDim)
            }
            Spacer(Modifier.height(12.dp))

            // App grid
            state.appCatalogue.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.openApp(app.id)
                            state.startMenuOpen = false
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp)
                            .background(AccentCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(app.icon, app.title, tint = AccentCyan, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(app.title, fontSize = 15.sp, color = TextWhite)
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = TextDim.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))

            // Power row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(
                    Icons.Default.PowerSettingsNew to "Shutdown",
                    Icons.Default.RestartAlt        to "Restart",
                ).forEach { (icon, label) ->
                    Column(
                        Modifier.clickable { }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(icon, label, tint = TextDim, modifier = Modifier.size(22.dp))
                        Text(label, fontSize = 11.sp, color = TextDim)
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Taskbar (bottom bar)
// ──────────────────────────────────────────────────────────────
@Composable
private fun DesktopTaskbarFull(state: DesktopState) {
    val currentTime = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            currentTime.value = System.currentTimeMillis()
        }
    }
    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(currentTime.value))
    val dateText = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
        .format(java.util.Date(currentTime.value))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(TaskbarBg),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Start button
            Box(
                Modifier.size(48.dp)
                    .clickable { state.startMenuOpen = !state.startMenuOpen }
                    .background(
                        if (state.startMenuOpen) AccentCyan.copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Apps, "Start", tint = AccentCyan, modifier = Modifier.size(28.dp))
            }

            Spacer(Modifier.width(8.dp))

            // Divider
            Box(Modifier.width(1.dp).height(32.dp).background(TextDim.copy(alpha = 0.2f)))
            Spacer(Modifier.width(8.dp))

            // Running / pinned apps
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Pinned apps first
                val pinned = listOf("file_manager", "terminal", "settings", "browser")
                pinned.forEach { appId ->
                    val isRunning = state.windows.any { it.appId == appId }
                    val isFocused = state.windows.find { it.appId == appId }?.id == state.focusedId
                    TaskbarItem(
                        icon = state.appCatalogue.find { it.id == appId }?.icon ?: Icons.Default.Apps,
                        label = state.appCatalogue.find { it.id == appId }?.title ?: appId,
                        isRunning = isRunning,
                        isFocused = isFocused,
                        onClick = { state.taskbarClick(appId) },
                    )
                }
                // Non-pinned running
                state.windows.filter { w -> w.appId !in pinned }.distinctBy { it.appId }.forEach { w ->
                    TaskbarItem(
                        icon = state.appCatalogue.find { it.id == w.appId }?.icon ?: Icons.Default.Apps,
                        label = w.title,
                        isRunning = true,
                        isFocused = w.id == state.focusedId,
                        onClick = { state.taskbarClick(w.appId) },
                    )
                }
            }

            // System tray
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Default.Wifi, "Wi-Fi", tint = AccentCyan, modifier = Modifier.size(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryFull, "Battery", tint = AccentGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("85%", fontSize = 12.sp, color = TextWhite)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(timeText, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextWhite)
                    Text(dateText, fontSize = 10.sp, color = TextDim)
                }
            }
        }
    }
}

@Composable
private fun TaskbarItem(
    icon: ImageVector,
    label: String,
    isRunning: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick)
            .background(
                when {
                    isFocused  -> AccentCyan.copy(alpha = 0.25f)
                    isRunning  -> AccentCyan.copy(alpha = 0.10f)
                    else       -> Color.Transparent
                },
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (isRunning) AccentCyan else IconPlaceholder, modifier = Modifier.size(22.dp))
        // Running indicator dot
        if (isRunning) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(4.dp)
                    .background(AccentCyan, RoundedCornerShape(2.dp))
            )
        }
    }
}
