package com.arena.agent.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.shapes.Capsule

fun Modifier.liquidCapsule(
    backdrop: Backdrop,
    surface: Color,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { Capsule() },
    effects = {
        blur(8f.dp.toPx())
        lens(16f.dp.toPx(), 28f.dp.toPx())
    },
    onDrawSurface = { drawRect(surface) },
)
