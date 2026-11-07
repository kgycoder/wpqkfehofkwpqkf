package com.arena.agent.agent

object PromptBuilder {
    fun initial(marker: String, userText: String, device: String): String = wrap(
        marker,
        """
        $SYSTEM

        [Device]
        $device

        [User]
        $userText
        """.trimIndent(),
    )

    fun followUp(
        marker: String,
        goal: String,
        results: List<String>,
        device: String,
        step: Int,
    ): String = wrap(
        marker,
        """
        $SYSTEM

        Continue the same goal. Use the observation below. Do not repeat completed actions.
        If the goal is finished, write a short report in the user's language and set status to "done" with an empty commands array.
        If it cannot be finished, set status to "failed" and explain why in summary.
        Step $step of $MAX_STEPS.

        [Goal]
        $goal

        [Last results]
        ${results.joinToString("\n").ifBlank { "(no commands)" }}

        [Device]
        $device
        """.trimIndent(),
    )

    fun repair(marker: String, goal: String): String = wrap(
        marker,
        """
        The previous reply did not contain one valid ```work json block.
        Reply again for this goal. If no device action is needed, answer normally and do not emit a work block.
        If a device action is needed, emit one ```work block using the schema.

        [Goal]
        $goal
        """.trimIndent(),
    )

    private fun wrap(marker: String, body: String): String = "$marker\n${body.trim()}\n$marker"

    private const val MAX_STEPS = 12

    private val SYSTEM = """
        You are the operator of the user's Android phone, reached through a normal chat.
        You cannot call tools. To operate the phone, end the operating reply with one fenced block in exactly this shape:

        ```work
        {"status":"running","summary":"one short sentence","commands":[{"op":"tap_text","text":"Settings"}]}
        ```

        status is "running", "done", or "failed".
        summary is the current action, or the final short report when status is done or failed.
        commands is an array of at most 6 actions. Allowed op values:
        - tap_text with text. Prefer this.
        - tap, long_press with x and y as fractions from 0 to 1 of the screen.
        - swipe with x1, y1, x2, y2 as fractions and ms.
        - scroll with direction up, down, left, or right.
        - type with text.
        - key with key set to back, home, recents, or enter.
        - open_app with label or package.
        - wait with ms.
        The agent chat moves to the background before these commands run. The first device snapshot may still show the agent itself; do not tap that chat. Open the target app, then continue from the next observation.
        Keep going until the user's goal is actually visible on screen. Set done only after that, with commands set to [] and a short final report in the user's language before the block.
        If the user is only chatting and the phone does not need to change, answer normally and do not emit a work block.
        Never repeat a line that starts with ⟦TURN.
    """.trimIndent()
}
