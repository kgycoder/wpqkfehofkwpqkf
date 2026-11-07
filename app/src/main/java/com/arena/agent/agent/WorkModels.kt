package com.arena.agent.agent

sealed interface DeviceCommand {
    data class Tap(val x: Float, val y: Float) : DeviceCommand
    data class LongPress(val x: Float, val y: Float) : DeviceCommand
    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val ms: Long,
    ) : DeviceCommand
    data class Type(val text: String) : DeviceCommand
    data class Key(val key: String) : DeviceCommand
    data class OpenApp(val packageName: String?, val label: String?) : DeviceCommand
    data class Scroll(val direction: String) : DeviceCommand
    data class Wait(val ms: Long) : DeviceCommand
    data class TapText(val text: String) : DeviceCommand
    data class Unknown(val op: String) : DeviceCommand
}

enum class WorkStatus {
    Running,
    Done,
    Failed,
}

data class WorkBlock(
    val status: WorkStatus,
    val summary: String,
    val commands: List<DeviceCommand>,
)

sealed interface ParsedWork {
    data class Block(val block: WorkBlock) : ParsedWork
    data object None : ParsedWork
    data object Incomplete : ParsedWork
    data class Invalid(val raw: String) : ParsedWork
}
