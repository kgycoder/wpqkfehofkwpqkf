package com.arena.agent.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private val workFence = Regex("```work\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
private val anyFence = Regex("```[a-zA-Z0-9_-]*\\s*([\\s\\S]*?)```")
private val openFence = Regex("```work\\b", RegexOption.IGNORE_CASE)
private val marker = Regex("⟦TURN:[^⟧]*⟧")

fun presentText(raw: String): String {
    val withoutMarkers = marker.replace(raw, "")
    val withoutControl = anyFence.replace(withoutMarkers) { match ->
        val body = match.groupValues[1]
        val header = match.value.substringBefore('\n').lowercase()
        if (header.startsWith("```work") || body.contains("\"status\"") || body.contains("\"상태\"")) {
            ""
        } else {
            match.value
        }
    }
    val open = openFence.find(withoutControl)?.range?.first ?: -1
    val cut = if (open >= 0) withoutControl.substring(0, open) else withoutControl
    return cut.trim()
}

fun parseWork(raw: String): ParsedWork {
    if (openFence.containsMatchIn(raw) && !workFence.containsMatchIn(raw)) {
        return ParsedWork.Incomplete
    }
    val fenced = workFence.findAll(raw).lastOrNull()?.groupValues?.getOrNull(1)
        ?: anyFence.findAll(raw).lastOrNull { candidate ->
            val body = candidate.groupValues[1]
            body.contains("\"status\"") || body.contains("\"상태\"")
        }?.groupValues?.getOrNull(1)
    if (fenced == null) return ParsedWork.None
    return parseJson(fenced)
}

private fun parseJson(rawInner: String): ParsedWork {
    val start = rawInner.indexOf('{')
    val end = rawInner.lastIndexOf('}')
    if (start < 0 || end <= start) return ParsedWork.Invalid(rawInner.trim())
    return try {
        val obj = JSONObject(rawInner.substring(start, end + 1))
        ParsedWork.Block(
            WorkBlock(
                status = statusOf(obj.str("status", "상태")),
                summary = obj.str("summary", "요약", "작업요약").take(400),
                commands = commandsOf(obj).take(6),
            ),
        )
    } catch (_: JSONException) {
        ParsedWork.Invalid(rawInner.trim())
    }
}

private fun commandsOf(obj: JSONObject): List<DeviceCommand> {
    val raw = obj.opt("commands") ?: obj.opt("command") ?: obj.opt("명령") ?: obj.opt("명령어")
    return when (raw) {
        is JSONArray -> buildList {
            for (index in 0 until raw.length()) {
                when (val item = raw.opt(index)) {
                    is JSONObject -> add(commandOf(item))
                    is String -> add(commandFromString(item))
                }
            }
        }
        is JSONObject -> listOf(commandOf(raw))
        is String -> listOf(commandFromString(raw))
        else -> emptyList()
    }
}

private fun commandFromString(raw: String): DeviceCommand {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{")) {
        return try {
            commandOf(JSONObject(trimmed))
        } catch (_: JSONException) {
            DeviceCommand.Unknown(trimmed.take(80))
        }
    }
    return DeviceCommand.Unknown(trimmed.take(80))
}

private fun commandOf(obj: JSONObject): DeviceCommand {
    val op = obj.str("op", "action", "command", "명령").lowercase()
    return when (op) {
        "tap" -> DeviceCommand.Tap(obj.num("x"), obj.num("y"))
        "long_press", "longpress" -> DeviceCommand.LongPress(obj.num("x"), obj.num("y"))
        "swipe" -> DeviceCommand.Swipe(
            x1 = obj.num("x1", "x"),
            y1 = obj.num("y1", "y"),
            x2 = obj.num("x2"),
            y2 = obj.num("y2"),
            ms = obj.millis("ms", "duration").coerceIn(80L, 1_500L),
        )
        "type", "input" -> DeviceCommand.Type(obj.str("text", "value").take(300))
        "key" -> DeviceCommand.Key(obj.str("key", "code", "target").lowercase())
        "open_app", "open", "launch" -> DeviceCommand.OpenApp(
            packageName = obj.str("package", "packageName").ifBlank { null },
            label = obj.str("label", "target", "text", "name").ifBlank { null },
        )
        "scroll" -> DeviceCommand.Scroll(obj.str("direction", "dir").ifBlank { "down" }.lowercase())
        "wait", "sleep" -> DeviceCommand.Wait(obj.millis("ms", "duration").coerceIn(0L, 4_000L))
        "tap_text", "taptext" -> DeviceCommand.TapText(obj.str("text", "label", "target").take(80))
        else -> DeviceCommand.Unknown(op.ifBlank { "empty" })
    }
}

private fun statusOf(raw: String): WorkStatus = when (raw.trim().lowercase()) {
    "done", "success", "succeeded", "complete", "completed", "finished", "완료", "성공" -> WorkStatus.Done
    "failed", "failure", "error", "blocked", "impossible", "실패", "불가" -> WorkStatus.Failed
    else -> WorkStatus.Running
}

private fun JSONObject.str(vararg keys: String): String {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = optString(key, "")
        if (value.isNotEmpty() && value != "null") return value
    }
    return ""
}

private fun JSONObject.num(vararg keys: String): Float {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = optDouble(key, Double.NaN)
        if (!value.isNaN()) return value.toFloat()
    }
    return 0f
}

private fun JSONObject.millis(vararg keys: String): Long = num(*keys).toLong()
