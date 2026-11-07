package com.arena.agent.agent

import android.app.Application
import android.content.Intent
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arena.agent.MainActivity
import com.arena.agent.arena.ArenaClient
import com.arena.agent.arena.ArenaPageListener
import com.arena.agent.arena.ArenaUrls
import com.arena.agent.arena.SessionStore
import com.arena.agent.device.DeviceController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class AgentViewModel(app: Application) : AndroidViewModel(app), ArenaPageListener {
    private val session = SessionStore(app)
    private val ids = AtomicLong(1)

    var gate by mutableStateOf(if (session.hasSavedLogin()) Gate.Ready else Gate.Booting)
        private set
    var draft by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var accessibilityOn by mutableStateOf(DeviceController.isEnabled())
        private set
    var webCanGoBack by mutableStateOf(false)
        private set
    val messages = mutableStateListOf<UiMessage>()

    private var client: ArenaClient? = null
    private var savedLogin = session.hasSavedLogin()
    private var bootStartedMs = 0L
    private var pageFinishedAt = 0L
    private var loginClickedAt = 0L
    private var googleClickedAt = 0L
    private var menuClicked = false
    private var epoch = 0
    private var steps = 0
    private var repairs = 0
    private var emptyStreak = 0
    private var goal: String? = null
    private var turnOpen = false
    private var sendAt = 0L
    private var lastText = ""
    private var stableSince = 0L
    private var sawStop = false
    private var assistantId = 0L
    private var queued: String? = null
    private var runJob: Job? = null

    init {
        viewModelScope.launch { poll() }
    }

    fun attach(next: ArenaClient) {
        client = next
        next.listener = this
        val pending = queued
        queued = null
        if (pending != null && busy) deliver(pending, epoch)
    }

    fun detach(current: ArenaClient) {
        if (client === current) client = null
    }

    override fun onPageStarted(url: String) {
        if (url.startsWith("about:")) return
        pageFinishedAt = 0L
        menuClicked = false
        loginClickedAt = 0L
        googleClickedAt = 0L
    }

    override fun onPageFinished(url: String) {
        if (url.startsWith("about:")) return
        pageFinishedAt = android.os.SystemClock.elapsedRealtime()
    }

    fun sendDraft() {
        val text = draft.trim()
        if (text.isEmpty() || busy || gate != Gate.Ready) return
        draft = ""
        goal = text
        steps = 0
        repairs = 0
        emptyStreak = 0
        messages += UiMessage(ids.incrementAndGet(), Role.User, text)
        busy = true
        openAssistant()
        deliver(
            PromptBuilder.initial(marker(), text, DeviceController.snapshot(getApplication())),
            epoch,
        )
    }

    fun stop() {
        epoch++
        turnOpen = false
        busy = false
        runJob?.cancel()
        queued = null
        markStreamingDone()
        viewModelScope.launch { client?.stop() }
        bringToFront()
    }

    fun newChat() {
        epoch++
        turnOpen = false
        busy = false
        runJob?.cancel()
        queued = null
        goal = null
        steps = 0
        repairs = 0
        messages.clear()
        viewModelScope.launch { client?.stop() }
        client?.loadFresh()
    }

    fun goBack() {
        client?.goBack()
    }

    private suspend fun poll() {
        while (true) {
            accessibilityOn = DeviceController.isEnabled()
            val current = client
            if (current != null) {
                webCanGoBack = current.canGoBack()
                current.probe()?.let { onProbe(it) }
                if (turnOpen) current.assistant()?.let { onAssistant(it) }
            }
            delay(400)
        }
    }

    private fun onProbe(raw: JSONObject) {
        val now = android.os.SystemClock.elapsedRealtime()
        val url = raw.optString("url")
        if (url.startsWith("about:")) return
        if (bootStartedMs == 0L && !savedLogin) bootStartedMs = now
        val hasLogin = raw.optBoolean("hasLogin")
        val hasGoogle = raw.optBoolean("hasGoogle")
        val hasComposer = raw.optBoolean("hasComposer")
        val onProvider = raw.optBoolean("onProvider")
        val sinceFinish = if (pageFinishedAt == 0L) 0L else now - pageFinishedAt
        val settled = pageFinishedAt > 0L && (
            hasComposer || hasLogin || hasGoogle || onProvider || sinceFinish > 8_000L
            )
        val decision = decide(
            GateInput(
                savedLogin = savedLogin,
                probe = Probe(
                    url = url,
                    hasLogin = hasLogin,
                    hasGoogle = hasGoogle,
                    hasComposer = hasComposer,
                    onProvider = onProvider,
                    settled = settled,
                    stop = raw.optBoolean("stop"),
                ),
                nowMs = now,
                bootStartedMs = bootStartedMs,
                loginClickedAt = loginClickedAt,
                googleClickedAt = googleClickedAt,
                menuClicked = menuClicked,
            ),
        )
        if (decision.confirmLogin) {
            savedLogin = true
            bootStartedMs = 0L
            val cookies = runCatching { CookieManager.getInstance().getCookie(ArenaUrls.COOKIE_URL) }.getOrNull()
            session.markLoggedIn(cookies)
            runCatching { CookieManager.getInstance().flush() }
        }
        if (decision.clearLogin) {
            savedLogin = false
            session.clear()
        }
        gate = decision.gate
        loginClickedAt = decision.loginClickedAt
        googleClickedAt = decision.googleClickedAt
        menuClicked = decision.menuClicked
        when (decision.click) {
            Click.Login -> viewModelScope.launch { client?.clickLogin() }
            Click.Google -> viewModelScope.launch { client?.clickGoogle() }
            Click.Menu -> viewModelScope.launch { client?.clickMenu() }
            null -> Unit
        }
    }

    private fun onAssistant(raw: JSONObject) {
        if (!turnOpen || !busy) return
        val text = raw.optString("text")
        val stopVisible = raw.optBoolean("stop")
        val now = android.os.SystemClock.elapsedRealtime()
        val parsed = parseWork(text)
        val block = (parsed as? ParsedWork.Block)?.block
        updateAssistant(
            text = presentText(text).ifBlank { block?.summary.orEmpty() },
            summary = block?.summary,
            status = block?.status,
            streaming = true,
        )
        if (text != lastText) {
            lastText = text
            stableSince = now
        }
        if (stopVisible) {
            sawStop = true
            return
        }
        if (!sawStop && now - sendAt < 1_500L) return
        if (text.isBlank()) {
            if (now - sendAt > 25_000L) finishTurn("", epoch)
            return
        }
        if (now - stableSince >= 1_200L) finishTurn(text, epoch)
    }

    private fun finishTurn(text: String, token: Int) {
        if (!turnOpen || epoch != token) return
        turnOpen = false
        val shown = presentText(text)
        when (val parsed = parseWork(text)) {
            ParsedWork.None -> {
                updateAssistant(shown, streaming = false)
                endLoop(movedAside = false)
            }
            ParsedWork.Incomplete, is ParsedWork.Invalid -> repairOrStop(shown, token)
            is ParsedWork.Block -> handleBlock(parsed.block, shown, token)
        }
    }

    private fun repairOrStop(shown: String, token: Int) {
        val pending = goal
        if (repairs >= 1 || pending == null) {
            updateAssistant(shown.ifBlank { "응답을 해석하지 못했습니다." }, streaming = false)
            endLoop(false)
            return
        }
        repairs++
        updateAssistant(shown, streaming = false)
        openAssistant()
        deliver(PromptBuilder.repair(marker(), pending), token)
    }

    private fun handleBlock(block: WorkBlock, shown: String, token: Int) {
        updateAssistant(
            text = shown.ifBlank { block.summary },
            summary = block.summary,
            status = block.status,
            streaming = false,
        )
        when (block.status) {
            WorkStatus.Done, WorkStatus.Failed -> {
                if (block.commands.isEmpty()) endLoop(false) else runCommands(block, token, continueAfter = false)
            }
            WorkStatus.Running -> {
                if (block.commands.isEmpty()) {
                    emptyStreak++
                    if (emptyStreak >= 2) {
                        updateAssistant(
                            shown.ifBlank { block.summary }.ifBlank { "다음 동작을 정하지 못했습니다." },
                            streaming = false,
                        )
                        endLoop(false)
                        return
                    }
                } else {
                    emptyStreak = 0
                }
                if (steps >= MAX_STEPS) {
                    messages += UiMessage(
                        ids.incrementAndGet(),
                        Role.Status,
                        "작업 단계가 ${MAX_STEPS}회에 도달해 멈췄습니다.",
                    )
                    endLoop(false)
                    return
                }
                steps++
                runCommands(block, token, continueAfter = true)
            }
        }
    }

    private fun runCommands(block: WorkBlock, token: Int, continueAfter: Boolean) {
        val job = viewModelScope.launch {
            val result = DeviceController.execute(getApplication(), block.commands)
            if (epoch != token) return@launch
            stampDetail(result.lines.joinToString("\n"))
            if (!continueAfter) {
                endLoop(result.movedAside)
                return@launch
            }
            val pending = goal ?: return@launch
            openAssistant()
            deliver(
                PromptBuilder.followUp(
                    marker = marker(),
                    goal = pending,
                    results = result.lines,
                    device = DeviceController.snapshot(getApplication()),
                    step = steps,
                ),
                token,
            )
        }
        runJob = job
    }

    private fun deliver(prompt: String, token: Int) {
        val current = client
        if (current == null) {
            queued = prompt
            return
        }
        val job = viewModelScope.launch {
            turnOpen = false
            val ok = current.send(prompt)
            if (epoch != token) return@launch
            if (!ok) {
                busy = false
                turnOpen = false
                updateAssistant("LM Arena에 메시지를 보내지 못했습니다. 로그인 화면을 엽니다.", streaming = false)
                gate = Gate.NeedsLogin
                return@launch
            }
            sendAt = android.os.SystemClock.elapsedRealtime()
            lastText = ""
            stableSince = sendAt
            sawStop = false
            turnOpen = true
        }
        runJob = job
    }

    private fun openAssistant() {
        val id = ids.incrementAndGet()
        assistantId = id
        messages += UiMessage(id, Role.Assistant, "", streaming = true)
    }

    private fun updateAssistant(
        text: String,
        summary: String? = null,
        status: WorkStatus? = null,
        streaming: Boolean,
    ) {
        val index = messages.indexOfLast { it.id == assistantId }
        if (index < 0) return
        val current = messages[index]
        messages[index] = current.copy(
            text = text,
            summary = summary ?: current.summary,
            status = status ?: current.status,
            streaming = streaming,
        )
    }

    private fun stampDetail(detail: String) {
        if (detail.isBlank()) return
        val index = messages.indexOfLast { it.id == assistantId }
        if (index < 0) return
        messages[index] = messages[index].copy(detail = detail)
    }

    private fun markStreamingDone() {
        val index = messages.indexOfLast { it.streaming }
        if (index >= 0) messages[index] = messages[index].copy(streaming = false)
    }

    private fun endLoop(movedAside: Boolean) {
        busy = false
        turnOpen = false
        if (movedAside) bringToFront()
    }

    private fun bringToFront() {
        val context = getApplication<Application>()
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        context.startActivity(intent)
    }

    private fun marker(): String = "⟦TURN:${epoch}.${steps}.${android.os.SystemClock.elapsedRealtime()}⟧"

    private companion object {
        const val MAX_STEPS = 12
    }
}
