// PlaybackSpeedSheet.kt

package com.mochyy.mochymusic.ui.player.menu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.di.AudioLabEntryPoint
import com.mochyy.mochymusic.eq.PlaybackSettings
import com.mochyy.mochymusic.eq.ReverbSettings
import com.mochyy.mochymusic.ui.component.RemoteFocus
import dagger.hilt.android.EntryPointAccessors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

private val CardSurface = MenuRowSurface
private const val SpeedMin = 0.50f
private const val SpeedMax = 2.00f
private const val PitchMin = -12f
private const val PitchMax = 12f

private data class StudioSpeedPreset(
    val id: String,
    val label: String,
    val speed: Float,
    val preservePitch: Boolean,
    val pitchSemitones: Float,
    val enableReverb: Boolean = false,
)

private val StudioPresets = listOf(
    StudioSpeedPreset("default", "Mặc định", 1.00f, preservePitch = true, pitchSemitones = 0f),
    StudioSpeedPreset("nightcore", "Nightcore", 1.25f, preservePitch = false, pitchSemitones = 0f),
    StudioSpeedPreset("slowed_reverb", "Slowed + Reverb", 0.85f, preservePitch = false, pitchSemitones = 0f, enableReverb = true),
    StudioSpeedPreset("daycore", "Daycore", 0.85f, preservePitch = true, pitchSemitones = -2f),
    StudioSpeedPreset("tone_down", "Hạ tông -2", 1.00f, preservePitch = true, pitchSemitones = -2f),
    StudioSpeedPreset("tone_up", "Nâng tông +2", 1.00f, preservePitch = true, pitchSemitones = 2f),
)

@Composable
fun PlaybackSpeedSheet(onDismiss: () -> Unit) {
    LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val store = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, AudioLabEntryPoint::class.java).audioLabStore()
    }
    val playback by store.playback.collectAsState()
    val reverb by store.reverb.collectAsState()
    val accent = MaterialTheme.colorScheme.primary

    fun applyPreset(preset: StudioSpeedPreset) {
        store.updatePlayback {
            PlaybackSettings(
                speed = preset.speed,
                preservePitch = preset.preservePitch,
                pitchSemitones = preset.pitchSemitones,
            )
        }
        if (preset.enableReverb) {
            store.updateReverb {
                it.copy(
                    enabled = true,
                    mix = 0.38f,
                    roomSize = 0.72f,
                    wet = 0.55f,
                )
            }
        } else if (reverb.enabled) {
            store.updateReverb { ReverbSettings(enabled = false) }
        }
    }

    MenuSheetSurface(onDismiss = onDismiss, halfDetent = 0.64f, fullDetent = 0.80f) { _ ->
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Studio Tốc độ & Cao độ",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (playback.preservePitch) "Giữ giọng" else "Vinyl Tape",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Hàng preset nhanh
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudioPresets.forEach { preset ->
                    val active = abs(playback.speed - preset.speed) < 0.01f &&
                        playback.preservePitch == preset.preservePitch &&
                        (!preset.preservePitch || abs(playback.pitchSemitones - preset.pitchSemitones) < 0.1f) &&
                        (preset.enableReverb == reverb.enabled || !preset.enableReverb && preset.id != "default")
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (active) accent.copy(alpha = 0.24f) else CardSurface)
                            .border(
                                width = 1.dp,
                                color = if (active) accent else Color.White.copy(alpha = 0.08f),
                                shape = CircleShape,
                            )
                            .clickable(onClick = { applyPreset(preset) })
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = preset.label,
                            color = if (active) Color.White else Color.White.copy(alpha = 0.82f),
                            fontSize = 12.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }

            // Bật/tắt giữ nguyên cao độ giọng hát (hoặc chạy kiểu đĩa than Vinyl)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardSurface)
                    .clickable {
                        store.updatePlayback { it.copy(preservePitch = !it.preservePitch) }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Giữ nguyên cao độ giọng hát",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (playback.preservePitch) {
                            "Đổi tốc độ không làm méo giọng ca sĩ"
                        } else {
                            "Chế độ Vinyl: cao độ tự nhiên theo tốc độ tua"
                        },
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 11.5.sp,
                    )
                }
                Switch(
                    checked = playback.preservePitch,
                    onCheckedChange = { checked ->
                        store.updatePlayback { it.copy(preservePitch = checked) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent,
                    ),
                )
            }

            // Thanh thước chia tốc độ phát
            TickRulerCard(
                label = "Tốc độ phát",
                formattedValue = String.format(Locale.US, "%.2f×", playback.speed),
                value = playback.speed,
                min = SpeedMin,
                max = SpeedMax,
                neutralValue = 1.00f,
                tickCount = 31,
                dragStep = 0.05f,
                fineStep = 0.01f,
                accentColor = accent,
                onValueChange = { next ->
                    val clamped = next.coerceIn(SpeedMin, SpeedMax)
                    store.updatePlayback { it.copy(speed = clamped) }
                },
            )

            // Thanh thước chia bán cung cao độ
            val shownSemitones = if (playback.preservePitch) playback.pitchSemitones else 0f
            val stRounded = round(shownSemitones * 2f) / 2f
            val stText = when {
                !playback.preservePitch -> "Theo tốc độ"
                abs(stRounded) < 0.05f -> "0 bán cung"
                abs(stRounded - round(stRounded)) < 0.05f -> {
                    val i = round(stRounded).toInt()
                    if (i > 0) "+$i bán cung" else "$i bán cung"
                }
                else -> String.format(Locale.US, "%+.1f bán cung", stRounded)
            }
            TickRulerCard(
                label = "Cao độ (Bán cung)",
                formattedValue = stText,
                value = shownSemitones,
                min = PitchMin,
                max = PitchMax,
                neutralValue = 0f,
                tickCount = 25,
                dragStep = 0.5f,
                fineStep = 1.0f,
                accentColor = accent,
                onValueChange = { next ->
                    val clamped = next.coerceIn(PitchMin, PitchMax)
                    store.updatePlayback { it.copy(preservePitch = true, pitchSemitones = clamped) }
                },
            )

            // Nút đặt lại mặc định
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = RemoteFocus,
                        onClick = { applyPreset(StudioPresets.first()) },
                    )
                    .padding(vertical = 14.dp),
            ) {
                Text(
                    text = "ĐẶT LẠI MẶC ĐỊNH",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TickRulerCard(
    label: String,
    formattedValue: String,
    value: Float,
    min: Float,
    max: Float,
    neutralValue: Float,
    tickCount: Int,
    dragStep: Float,
    fineStep: Float,
    accentColor: Color,
    onValueChange: (Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val neutralFraction = ((neutralValue - min) / (max - min)).coerceIn(0f, 1f)

    fun emitFraction(f: Float) {
        val raw = min + f.coerceIn(0f, 1f) * (max - min)
        val snapped = round(raw / dragStep) * dragStep
        currentOnChange(snapped.coerceIn(min, max))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StepperPill("−") {
                currentOnChange((value - fineStep).coerceIn(min, max))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formattedValue,
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(8.dp))
            StepperPill("+") {
                currentOnChange((value + fineStep).coerceIn(min, max))
            }
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(min, max, dragStep) {
                    detectTapGestures { pos ->
                        if (size.width > 0) emitFraction(pos.x / size.width.toFloat())
                    }
                }
                .pointerInput(min, max, dragStep) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        if (size.width > 0) emitFraction(change.position.x / size.width.toFloat())
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val midY = h * 0.55f
            val pad = 8.dp.toPx()
            val spanW = (w - pad * 2f).coerceAtLeast(1f)
            val thumbX = pad + spanW * fraction
            val neutralX = pad + spanW * neutralFraction

            // Vạch thước chia tỷ lệ
            for (i in 0 until tickCount) {
                val tf = i.toFloat() / (tickCount - 1).coerceAtLeast(1)
                val x = pad + spanW * tf
                val isMajor = i == 0 || i == tickCount - 1 || i % 5 == 0
                val tickTop = if (isMajor) midY - 9.dp.toPx() else midY - 5.dp.toPx()
                val tickBottom = if (isMajor) midY + 9.dp.toPx() else midY + 5.dp.toPx()
                val betweenNeutralAndThumb = x in minOf(neutralX, thumbX)..maxOf(neutralX, thumbX)
                drawLine(
                    color = if (betweenNeutralAndThumb) accentColor.copy(alpha = 0.85f) else Color.White.copy(alpha = if (isMajor) 0.35f else 0.18f),
                    start = Offset(x, tickTop),
                    end = Offset(x, tickBottom),
                    strokeWidth = if (isMajor) 2.dp.toPx() else 1.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Vạch nền + đoạn active từ vị trí chuẩn (1.0x hoặc 0st)
            drawLine(
                color = Color.White.copy(alpha = 0.16f),
                start = Offset(pad, midY),
                end = Offset(pad + spanW, midY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accentColor,
                start = Offset(neutralX, midY),
                end = Offset(thumbX, midY),
                strokeWidth = 3.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Núm kéo
            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = Offset(thumbX, midY),
            )
            drawCircle(
                color = accentColor,
                radius = 3.5.dp.toPx(),
                center = Offset(thumbX, midY),
            )
        }
    }
}

@Composable
private fun StepperPill(symbol: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = symbol,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
