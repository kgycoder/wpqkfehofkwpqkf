package com.arena.agent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import com.arena.agent.agent.DeviceCommand
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.ZonedDateTime
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class ExecResult(
    val lines: List<String>,
    val movedAside: Boolean,
)

object DeviceController {
    @Volatile
    var service: DeviceAccessibilityService? = null

    fun isEnabled(): Boolean = service != null

    fun snapshot(context: android.content.Context): String {
        val metrics = context.resources.displayMetrics
        val battery = runCatching {
            val manager = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val network = runCatching {
            val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            when {
                capabilities == null -> "offline"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "online"
            }
        }.getOrDefault("unknown")
        val active = service
        val root = active?.rootInActiveWindow
        val foreground = root?.packageName?.toString().orEmpty().ifBlank { "unknown" }
        val nodes = ArrayList<String>(70)
        if (root != null) {
            walk(root, nodes, metrics.widthPixels, metrics.heightPixels, 0)
            root.recycle()
        }
        val agentNote = if (foreground == context.packageName) {
            "note: the agent chat is in front. Commands move it aside before taps."
        } else {
            "note: commands already run on the foreground app."
        }
        return buildString {
            append("time: ").append(ZonedDateTime.now()).append('\n')
            append("locale: ").append(java.util.Locale.getDefault().toLanguageTag()).append('\n')
            append("battery: ").append(if (battery >= 0) "$battery%" else "unknown").append('\n')
            append("network: ").append(network).append('\n')
            append("screen: ").append(metrics.widthPixels).append('x').append(metrics.heightPixels).append('\n')
            append("foreground: ").append(foreground).append('\n')
            append("accessibility: ").append(if (active != null) "on" else "off").append('\n')
            append(agentNote).append('\n')
            append("nodes:\n")
            if (nodes.isEmpty()) append("- (none)") else append(nodes.joinToString("\n"))
        }.take(5_500)
    }

    suspend fun execute(context: android.content.Context, commands: List<DeviceCommand>): ExecResult {
        val active = service ?: return ExecResult(listOf("accessibility_off"), movedAside = false)
        if (commands.isEmpty()) return ExecResult(emptyList(), movedAside = false)
        val touchesUi = commands.any { it !is DeviceCommand.Wait }
        if (touchesUi) {
            AppVisibility.activity?.moveTaskToBack(true)
            delay(450)
        }
        val lock = acquire(context)
        val lines = ArrayList<String>(commands.size)
        try {
            for (command in commands) {
                if (!currentCoroutineContext().isActive) break
                lines += run(active, command)
            }
            delay(300)
        } finally {
            lock?.let { runCatching { if (it.isHeld) it.release() } }
        }
        return ExecResult(lines, movedAside = touchesUi)
    }

    @Suppress("DEPRECATION")
    private fun acquire(context: android.content.Context): PowerManager.WakeLock? = runCatching {
        val manager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        manager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "arenaagent:work",
        ).apply { acquire(45_000L) }
    }.getOrNull()

    private suspend fun run(service: AccessibilityService, command: DeviceCommand): String = when (command) {
        is DeviceCommand.Tap -> gesturePoint(service, command.x, command.y, 60L).let { ok ->
            "tap ${fmt(command.x)},${fmt(command.y)} ${if (ok) "ok" else "failed"}"
        }
        is DeviceCommand.LongPress -> gesturePoint(service, command.x, command.y, 560L).let { ok ->
            "long_press ${fmt(command.x)},${fmt(command.y)} ${if (ok) "ok" else "failed"}"
        }
        is DeviceCommand.Swipe -> swipe(service, command).let { ok ->
            "swipe ${if (ok) "ok" else "failed"}"
        }
        is DeviceCommand.Scroll -> {
            val points = scrollPoints(command.direction)
            val ok = swipe(
                service,
                DeviceCommand.Swipe(points[0], points[1], points[2], points[3], 280L),
            )
            "scroll ${command.direction} ${if (ok) "ok" else "failed"}"
        }
        is DeviceCommand.Wait -> {
            delay(command.ms)
            "wait ${command.ms} ok"
        }
        is DeviceCommand.Key -> "key ${command.key} ${if (key(service, command.key)) "ok" else "failed"}"
        is DeviceCommand.Type -> "type ${command.text.length} chars ${if (type(service, command.text)) "ok" else "failed"}"
        is DeviceCommand.TapText -> tapText(service, command.text)
        is DeviceCommand.OpenApp -> {
            val line = openApp(service, command)
            delay(800)
            line
        }
        is DeviceCommand.Unknown -> "unsupported ${command.op}"
    }

    private suspend fun gesturePoint(service: AccessibilityService, x: Float, y: Float, duration: Long): Boolean {
        val metrics = service.resources.displayMetrics
        val px = px(x, metrics.widthPixels)
        val py = px(y, metrics.heightPixels)
        val path = Path().apply { moveTo(px, py) }
        return dispatch(service, path, duration.coerceAtLeast(40L))
    }

    private suspend fun swipe(service: AccessibilityService, command: DeviceCommand.Swipe): Boolean {
        val metrics = service.resources.displayMetrics
        val path = Path().apply {
            moveTo(px(command.x1, metrics.widthPixels), px(command.y1, metrics.heightPixels))
            lineTo(px(command.x2, metrics.widthPixels), px(command.y2, metrics.heightPixels))
        }
        return dispatch(service, path, command.ms)
    }

    private suspend fun dispatch(service: AccessibilityService, path: Path, duration: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(40L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!accepted && cont.isActive) cont.resume(false)
        }

    private fun key(service: AccessibilityService, name: String): Boolean = when (name) {
        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        "recents", "overview" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        "enter" -> enter(service)
        else -> false
    }

    private fun enter(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focus ?: firstEditable(root)
        val ok = target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        if (focus != null && focus !== target) focus.recycle()
        target?.recycle()
        root.recycle()
        return ok
    }

    private fun type(service: AccessibilityService, text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focus ?: firstEditable(root)
        if (target == null) {
            root.recycle()
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (focus != null && focus !== target) focus.recycle()
        target.recycle()
        root.recycle()
        return ok
    }

    private suspend fun tapText(service: AccessibilityService, query: String): String {
        if (query.isBlank()) return "tap_text empty failed"
        val root = service.rootInActiveWindow ?: return "tap_text \"$query\" no_window"
        val nodes = ArrayList<AccessibilityNodeInfo>(128)
        collect(root, nodes, 0)
        val target = pick(nodes, query)
        if (target == null) {
            nodes.forEach { if (it !== root) it.recycle() }
            root.recycle()
            return "tap_text \"$query\" not_found"
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        nodes.forEach { if (it !== root) it.recycle() }
        root.recycle()
        if (rect.width() <= 0 || rect.height() <= 0) return "tap_text \"$query\" not_visible"
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val ok = dispatch(service, path, 70L)
        return "tap_text \"$query\" ${if (ok) "ok" else "failed"}"
    }

    private fun openApp(service: AccessibilityService, command: DeviceCommand.OpenApp): String {
        val pm = service.packageManager
        val intent = command.packageName?.let { pm.getLaunchIntentForPackage(it) }
            ?: command.label?.let { launchByLabel(pm, it) }
        if (intent == null) {
            return "open_app ${command.label ?: command.packageName ?: "?"} not_found"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            service.startActivity(intent)
            "open_app ${command.label ?: command.packageName} ok"
        } catch (_: Exception) {
            "open_app ${command.label ?: command.packageName} failed"
        }
    }

    @Suppress("DEPRECATION")
    private fun launchByLabel(pm: PackageManager, label: String): Intent? {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(query, 0)
        }
        val match = apps.firstOrNull {
            it.loadLabel(pm)?.toString()?.contains(label, ignoreCase = true) == true
        } ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
    }

    private fun firstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = firstEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun pick(nodes: List<AccessibilityNodeInfo>, query: String): AccessibilityNodeInfo? {
        val needle = query.lowercase()
        val labeled = nodes.mapNotNull { node ->
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            if (label.isEmpty()) null else node to label
        }
        val exact = labeled.firstOrNull { it.second.equals(needle, ignoreCase = true) }
        val loose = labeled.firstOrNull { it.second.contains(needle, ignoreCase = true) }
        return (exact ?: loose)?.first
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (out.size > 500 || depth > 30) return
        for (index in 0 until node.childCount) {
            if (out.size > 500) return
            val child = node.getChild(index) ?: continue
            out += child
            collect(child, out, depth + 1)
        }
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        width: Int,
        height: Int,
        depth: Int,
    ) {
        if (out.size >= 70 || depth > 18) return
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim().take(80)
        if (label.isNotEmpty() || node.isClickable || node.isEditable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (label.isNotEmpty() && rect.width() > 0 && rect.height() > 0) {
                val kind = when {
                    node.isEditable -> "Edit"
                    node.isClickable -> "Tap"
                    else -> "Text"
                }
                val cx = (rect.exactCenterX() / width).coerceIn(0f, 1f)
                val cy = (rect.exactCenterY() / height).coerceIn(0f, 1f)
                out += "- $kind \"${label.replace("\n", " ")}\" center=${fmt(cx)},${fmt(cy)}"
            }
        }
        for (index in 0 until node.childCount) {
            if (out.size >= 70) return
            val child = node.getChild(index) ?: continue
            walk(child, out, width, height, depth + 1)
            child.recycle()
        }
    }

    private fun scrollPoints(direction: String): FloatArray = when (direction) {
        "up" -> floatArrayOf(0.5f, 0.35f, 0.5f, 0.75f)
        "left" -> floatArrayOf(0.3f, 0.5f, 0.8f, 0.5f)
        "right" -> floatArrayOf(0.8f, 0.5f, 0.3f, 0.5f)
        else -> floatArrayOf(0.5f, 0.75f, 0.5f, 0.32f)
    }

    private fun px(value: Float, span: Int): Float {
        val pixels = if (value <= 1.05f) value.coerceIn(0f, 1f) * span else value
        return pixels.coerceIn(1f, (span - 2).coerceAtLeast(1).toFloat())
    }

    private fun fmt(value: Float): String = ((value * 100).roundToInt() / 100f).toString()
}
