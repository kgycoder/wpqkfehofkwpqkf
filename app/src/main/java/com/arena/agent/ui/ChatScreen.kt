package com.arena.agent.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arena.agent.agent.AgentViewModel
import com.arena.agent.agent.Gate
import com.arena.agent.agent.Role
import com.arena.agent.agent.UiMessage
import com.arena.agent.agent.WorkStatus
import com.arena.agent.arena.ArenaClient
import com.arena.agent.arena.ArenaWebView
import com.arena.agent.arena.SessionStore
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun AgentScreen(viewModel: AgentViewModel) {
    val dark = isSystemInDarkTheme()
    val backdrop = rememberLayerBackdrop()
    val glass = if (dark) Color(0x66121212) else Color(0xB8FFFFFF)
    val primary = if (dark) Color(0xFFF5F5F7) else Color(0xFF111111)
    val secondary = if (dark) Color(0xFF9A9AA1) else Color(0xFF6E6E73)
    val page = if (dark) {
        Brush.verticalGradient(listOf(Color(0xFF16161A), Color(0xFF0E1014), Color(0xFF1A1C22)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFE7EEF8), Color(0xFFF7F4EF), Color(0xFFE6F1EA)))
    }

    Box(Modifier.fillMaxSize()) {
        ArenaWebHost(
            viewModel = viewModel,
            modifier = if (viewModel.gate == Gate.NeedsLogin) {
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            } else {
                Modifier.fillMaxSize()
            },
        )
        if (viewModel.gate != Gate.NeedsLogin) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(page),
            ) {
                Wallpaper(dark)
            }
            if (viewModel.gate == Gate.Ready) {
                Messages(
                    messages = viewModel.messages,
                    backdrop = backdrop,
                    glass = glass,
                    primary = primary,
                    secondary = secondary,
                    accessibilityOn = viewModel.accessibilityOn,
                )
                TopBar(viewModel, backdrop, glass, primary)
                Composer(viewModel, backdrop, glass, primary, secondary)
            }
            if (viewModel.gate == Gate.Booting) {
                Loading(backdrop, glass, primary, secondary)
            }
        }
    }

    BackHandler(enabled = viewModel.gate == Gate.NeedsLogin && viewModel.webCanGoBack) {
        viewModel.goBack()
    }
}

@Composable
private fun ArenaWebHost(viewModel: AgentViewModel, modifier: Modifier) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = {
            ArenaWebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                val script = context.assets.open("arena_bridge.js").bufferedReader().use { it.readText() }
                val client = ArenaClient(this, script)
                setTag(client)
                attach(client)
                viewModel.attach(client)
                onResume()
                resumeTimers()
                client.loadInitial { SessionStore(context).restoreCookies() }
            }
        },
        onRelease = { web ->
            (web.tag as? ArenaClient)?.let(viewModel::detach)
            web.stopLoading()
            web.destroy()
        },
    )
}

@Composable
private fun Wallpaper(dark: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val first = if (dark) Color(0x664A6CF7) else Color(0x88B9D4FF)
        val second = if (dark) Color(0x553C8F7A) else Color(0x66D7F0DE)
        drawCircle(first, size.minDimension * 0.42f, Offset(size.width * 0.18f, size.height * 0.12f))
        drawCircle(second, size.minDimension * 0.5f, Offset(size.width * 0.9f, size.height * 0.82f))
    }
}

@Composable
private fun Loading(
    backdrop: com.kyant.backdrop.Backdrop,
    glass: Color,
    primary: Color,
    secondary: Color,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .liquidCapsule(backdrop, glass)
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = primary,
            )
            Spacer(Modifier.height(16.dp))
            Text("Google 로그인으로 이동하는 중", color = primary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("로그인 창이 준비되면 이 화면이 닫힙니다.", color = secondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun BoxScope.TopBar(
    viewModel: AgentViewModel,
    backdrop: com.kyant.backdrop.Backdrop,
    glass: Color,
    primary: Color,
) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Agent",
            modifier = Modifier.weight(1f),
            color = primary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        GlassIconButton(backdrop, glass, primary, onClick = viewModel::newChat) {
            androidx.compose.material3.Icon(Icons.Filled.Edit, contentDescription = "새 대화", tint = primary)
        }
    }
}

@Composable
private fun Messages(
    messages: List<UiMessage>,
    backdrop: com.kyant.backdrop.Backdrop,
    glass: Color,
    primary: Color,
    secondary: Color,
    accessibilityOn: Boolean,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text, messages.lastOrNull()?.streaming) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 92.dp,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (messages.isEmpty()) {
            item {
                EmptyState(accessibilityOn, primary, secondary)
            }
        }
        items(messages, key = { it.id }) { message ->
            when (message.role) {
                Role.User -> UserBubble(message.text, backdrop, glass, primary)
                Role.Assistant -> AssistantBubble(message, backdrop, glass, primary, secondary)
                Role.Status -> Text(message.text, color = secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(accessibilityOn: Boolean, primary: Color, secondary: Color) {
    val context = LocalContext.current
    Column(Modifier.padding(top = 48.dp, end = 24.dp)) {
        Text("메시지를 보내면 이 기기를 조작합니다.", color = primary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "화면의 글자는 작업 중에 LM Arena로 전송됩니다. 접근성을 켜면 탭, 입력, 앱 열기가 가능합니다.",
            color = secondary,
            fontSize = 15.sp,
        )
        if (!accessibilityOn) {
            Spacer(Modifier.height(16.dp))
            Text(
                "접근성 설정 열기",
                color = primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "앱 정보에서 제한된 설정 허용",
                color = secondary,
                fontSize = 15.sp,
                modifier = Modifier.clickable {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                },
            )
        }
    }
}

@Composable
private fun UserBubble(
    text: String,
    backdrop: com.kyant.backdrop.Backdrop,
    glass: Color,
    primary: Color,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            color = primary,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .liquidCapsule(backdrop, glass)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AssistantBubble(
    message: UiMessage,
    backdrop: com.kyant.backdrop.Backdrop,
    glass: Color,
    primary: Color,
    secondary: Color,
) {
    Column(Modifier.fillMaxWidth().padding(end = 28.dp)) {
        message.summary?.takeIf { it.isNotBlank() && it != message.text }?.let { summary ->
            Text(summary, color = secondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        message.status?.let { status ->
            Text(
                when (status) {
                    WorkStatus.Running -> "진행 중"
                    WorkStatus.Done -> "완료"
                    WorkStatus.Failed -> "실패"
                },
                color = secondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val body = message.text.ifBlank { if (message.streaming) "" else "…" }
        if (body.isNotEmpty() || message.streaming) {
            SelectionContainer {
                RichText(
                    text = body + if (message.streaming) "▍" else "",
                    backdrop = backdrop,
                    glass = glass,
                    primary = primary,
                )
            }
        }
        message.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(detail, color = secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun RichText(
    text: String,
    backdrop: com.kyant.backdrop.Backdrop,
    glass: Color,
    primary: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        splitSegments(text).forEach { segment ->
            when (segment) {
                is Segment.Text -> Text(segment.value, color = primary, fontSize = 17.sp, lineHeight = 23.sp)
                is Segment.Code -> Text(
                    segment.value,
                    color = primary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .liquidCapsule(backdrop, glass)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.Composer(
    viewModel: AgentViewModel,
    backdrop: com.kyant.backdrop.Backdrop,
    glass: Color,
    primary: Color,
    secondary: Color,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .liquidCapsule(backdrop, glass)
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            BasicTextField(
                value = viewModel.draft,
                onValueChange = { viewModel.draft = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(color = primary, fontSize = 17.sp),
                cursorBrush = SolidColor(primary),
                maxLines = 6,
                decorationBox = { inner ->
                    Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        if (viewModel.draft.isEmpty()) {
                            Text("메시지", color = secondary, fontSize = 17.sp)
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            if (viewModel.busy) {
                GlassIconButton(backdrop, glass, primary, onClick = viewModel::stop) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(primary),
                    )
                }
            } else {
                GlassIconButton(
                    backdrop = backdrop,
                    surface = glass,
                    primary = primary,
                    enabled = viewModel.draft.isNotBlank() && viewModel.gate == Gate.Ready,
                    onClick = viewModel::sendDraft,
                ) {
                    androidx.compose.material3.Icon(Icons.Filled.Send, contentDescription = "보내기", tint = primary)
                }
            }
        }
}

@Composable
private fun GlassIconButton(
    backdrop: com.kyant.backdrop.Backdrop,
    surface: Color,
    primary: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(36.dp)
            .liquidCapsule(backdrop, surface.copy(alpha = if (enabled) surface.alpha else 0.35f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private sealed interface Segment {
    data class Text(val value: String) : Segment
    data class Code(val value: String) : Segment
}

private fun splitSegments(raw: String): List<Segment> {
    if (!raw.contains("```")) return listOf(Segment.Text(raw))
    val parts = raw.split("```")
    return parts.mapIndexedNotNull { index, part ->
        if (part.isBlank()) return@mapIndexedNotNull null
        if (index % 2 == 1) {
            val body = part.substringAfter('\n', part).trim()
            Segment.Code(body.ifBlank { part.trim() })
        } else {
            Segment.Text(part.trim())
        }
    }
}
