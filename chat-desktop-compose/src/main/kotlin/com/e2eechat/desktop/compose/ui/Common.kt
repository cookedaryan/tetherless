package com.e2eechat.desktop.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e2eechat.desktop.compose.model.DeliveryStatus
import com.e2eechat.desktop.compose.theme.LocalAppColors

private val avatarPalette = listOf(
    Color(0xFF4D8DFF), Color(0xFFF4756B), Color(0xFF34C759),
    Color(0xFFAF7BFF), Color(0xFFFF9F0A), Color(0xFF2DD4BF),
)

private fun colorFor(seed: String): Color =
    avatarPalette[(seed.hashCode() and 0x7FFFFFFF) % avatarPalette.size]

private fun initialsOf(name: String): String =
    name.split(' ', '·')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.first().isLetterOrDigit() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }

/** A round monogram avatar with a colour hashed from the name — no image assets required. */
@Composable
fun Avatar(name: String, size: Dp = 46.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(colorFor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = Color.White,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The delivery ticks drawn inside an outgoing bubble. A failed send shows a red warning glyph. */
@Composable
fun StatusTicks(status: DeliveryStatus) {
    val colors = LocalAppColors.current
    when (status) {
        DeliveryStatus.SENT -> Icon(Icons.Filled.Done, contentDescription = "Sent", tint = colors.timeOut, modifier = Modifier.size(15.dp))
        DeliveryStatus.DELIVERED -> Icon(Icons.Filled.DoneAll, contentDescription = "Delivered", tint = colors.timeOut, modifier = Modifier.size(15.dp))
        DeliveryStatus.READ -> Icon(Icons.Filled.DoneAll, contentDescription = "Read", tint = colors.tick, modifier = Modifier.size(15.dp))
        DeliveryStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, contentDescription = "Failed", tint = colors.danger, modifier = Modifier.size(15.dp))
    }
}
