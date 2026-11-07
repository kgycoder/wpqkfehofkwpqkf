package com.arena.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkParserTest {
    @Test
    fun parsesWorkBlock() {
        val raw = """
            화면을 확인했습니다.
            ```work
            {"status":"running","summary":"설정을 연다","commands":[{"op":"open_app","label":"Settings"},{"op":"wait","ms":9000}]}
            ```
        """.trimIndent()
        val parsed = parseWork(raw)
        val block = (parsed as ParsedWork.Block).block
        assertEquals(WorkStatus.Running, block.status)
        assertEquals("설정을 연다", block.summary)
        assertEquals("Settings", (block.commands[0] as DeviceCommand.OpenApp).label)
        assertEquals(4000L, (block.commands[1] as DeviceCommand.Wait).ms)
        assertEquals("화면을 확인했습니다.", presentText(raw))
    }

    @Test
    fun acceptsKoreanStatusAndStripsControlJson() {
        val raw = """
            ```json
            {"상태":"완료","요약":"Wi-Fi를 켰습니다","명령":[]}
            ```
        """.trimIndent()
        val block = (parseWork(raw) as ParsedWork.Block).block
        assertEquals(WorkStatus.Done, block.status)
        assertEquals("Wi-Fi를 켰습니다", block.summary)
        assertTrue(block.commands.isEmpty())
        assertEquals("", presentText(raw))
    }

    @Test
    fun incompleteFenceWaits() {
        val raw = "진행합니다.\n```work\n{\"status\":\"running\""
        assertEquals(ParsedWork.Incomplete, parseWork(raw))
        assertEquals("진행합니다.", presentText(raw))
    }

    @Test
    fun plainChatHasNoBlock() {
        assertEquals(ParsedWork.None, parseWork("안녕하세요"))
        assertEquals("안녕하세요", presentText("⟦TURN:1.0.2⟧\n안녕하세요"))
    }
}

class LoginGateTest {
    @Test
    fun savedSessionOpensTheChatBeforeVerification() {
        val decision = decide(input(saved = true, probe = null))
        assertEquals(Gate.Ready, decision.gate)
        assertEquals(null, decision.click)
    }

    @Test
    fun firstLaunchClicksLoginWhileTheLoaderStaysUp() {
        val decision = decide(
            input(
                saved = false,
                probe = probe(hasLogin = true),
                bootStartedMs = 1_000L,
                nowMs = 2_000L,
            ),
        )
        assertEquals(Gate.Booting, decision.gate)
        assertEquals(Click.Login, decision.click)
    }

    @Test
    fun googlePageReplacesTheLoader() {
        val decision = decide(
            input(
                saved = false,
                probe = probe(onProvider = true),
                bootStartedMs = 1_000L,
                nowMs = 2_000L,
            ),
        )
        assertEquals(Gate.NeedsLogin, decision.gate)
        assertEquals(null, decision.click)
    }

    @Test
    fun confirmedComposerSavesTheSession() {
        val decision = decide(input(saved = false, probe = probe(hasComposer = true)))
        assertEquals(Gate.Ready, decision.gate)
        assertTrue(decision.confirmLogin)
    }

    @Test
    fun visibleLoginAfterASavedSessionClearsItOnce() {
        val first = decide(
            input(
                saved = true,
                probe = probe(hasLogin = true, settled = true),
                nowMs = 10_000L,
            ),
        )
        assertEquals(Gate.NeedsLogin, first.gate)
        assertTrue(first.clearLogin)
        assertEquals(Click.Login, first.click)

        val second = decide(
            input(
                saved = true,
                probe = probe(hasLogin = true, settled = true),
                nowMs = 11_000L,
                loginClickedAt = first.loginClickedAt,
            ),
        )
        assertEquals(null, second.click)
    }

    private fun input(
        saved: Boolean,
        probe: Probe?,
        nowMs: Long = 5_000L,
        bootStartedMs: Long = 0L,
        loginClickedAt: Long = 0L,
    ) = GateInput(
        savedLogin = saved,
        probe = probe,
        nowMs = nowMs,
        bootStartedMs = bootStartedMs,
        loginClickedAt = loginClickedAt,
        googleClickedAt = 0L,
        menuClicked = false,
    )

    private fun probe(
        hasLogin: Boolean = false,
        hasGoogle: Boolean = false,
        hasComposer: Boolean = false,
        onProvider: Boolean = false,
        settled: Boolean = false,
    ) = Probe(
        url = if (onProvider) "https://accounts.google.com/" else "https://arena.ai/text/direct?model_a=max",
        hasLogin = hasLogin,
        hasGoogle = hasGoogle,
        hasComposer = hasComposer,
        onProvider = onProvider,
        settled = settled,
    )
}
