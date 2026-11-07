package com.arena.agent.agent

enum class Gate {
    Booting,
    NeedsLogin,
    Ready,
}

enum class Click {
    Login,
    Google,
    Menu,
}

data class Probe(
    val url: String,
    val hasLogin: Boolean,
    val hasGoogle: Boolean,
    val hasComposer: Boolean,
    val onProvider: Boolean,
    val settled: Boolean,
    val stop: Boolean = false,
) {
    val ready: Boolean
        get() = hasComposer && !hasLogin && !onProvider && url.isNotBlank()
}

data class GateInput(
    val savedLogin: Boolean,
    val probe: Probe?,
    val nowMs: Long,
    val bootStartedMs: Long,
    val loginClickedAt: Long,
    val googleClickedAt: Long,
    val menuClicked: Boolean,
)

data class GateDecision(
    val gate: Gate,
    val click: Click?,
    val confirmLogin: Boolean,
    val clearLogin: Boolean,
    val loginClickedAt: Long,
    val googleClickedAt: Long,
    val menuClicked: Boolean,
)

private const val CLICK_GAP_MS = 3_500L
private const val LOGIN_TIMEOUT_MS = 25_000L

fun decide(input: GateInput): GateDecision {
    val probe = input.probe
    if (probe == null || probe.url.isBlank() || probe.url.startsWith("about:")) {
        val gate = if (input.savedLogin) Gate.Ready else Gate.Booting
        return input.keep(gate)
    }

    if (probe.ready) {
        return GateDecision(
            gate = Gate.Ready,
            click = null,
            confirmLogin = true,
            clearLogin = false,
            loginClickedAt = 0L,
            googleClickedAt = 0L,
            menuClicked = false,
        )
    }

    if (input.savedLogin) {
        if (probe.onProvider) {
            return input.keep(Gate.NeedsLogin)
        }
        if (probe.settled && probe.hasLogin) {
            val click = if (input.nowMs - input.loginClickedAt > CLICK_GAP_MS) Click.Login else null
            return GateDecision(
                gate = Gate.NeedsLogin,
                click = click,
                confirmLogin = false,
                clearLogin = true,
                loginClickedAt = if (click != null) input.nowMs else input.loginClickedAt,
                googleClickedAt = input.googleClickedAt,
                menuClicked = input.menuClicked,
            )
        }
        return input.keep(Gate.Ready)
    }

    var loginAt = input.loginClickedAt
    var googleAt = input.googleClickedAt
    var menu = input.menuClicked
    var click: Click? = null
    if (!probe.onProvider) {
        when {
            probe.hasGoogle && input.nowMs - googleAt > CLICK_GAP_MS -> {
                click = Click.Google
                googleAt = input.nowMs
            }
            probe.hasLogin && input.nowMs - loginAt > CLICK_GAP_MS -> {
                click = Click.Login
                loginAt = input.nowMs
            }
            probe.settled && !probe.hasComposer && !menu -> {
                click = Click.Menu
                menu = true
            }
        }
    }
    val timedOut = input.bootStartedMs > 0L && input.nowMs - input.bootStartedMs > LOGIN_TIMEOUT_MS
    val gate = if (probe.onProvider || timedOut) Gate.NeedsLogin else Gate.Booting
    return GateDecision(
        gate = gate,
        click = click,
        confirmLogin = false,
        clearLogin = false,
        loginClickedAt = loginAt,
        googleClickedAt = googleAt,
        menuClicked = menu,
    )
}

private fun GateInput.keep(gate: Gate) = GateDecision(
    gate = gate,
    click = null,
    confirmLogin = false,
    clearLogin = false,
    loginClickedAt = loginClickedAt,
    googleClickedAt = googleClickedAt,
    menuClicked = menuClicked,
)
