// PlayerBackgroundStyles.kt

package com.mochyy.mochymusic.ui.player.customize

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.mochyy.mochymusic.constants.PlayerBackgroundStyle
import com.mochyy.mochymusic.ui.utils.resize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlayerBackgroundContent(
    style: PlayerBackgroundStyle,
    thumbnailUrl: String?,
    pureBlack: Boolean,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    when (style) {
        PlayerBackgroundStyle.COVER_GRADIENT -> Unit
        PlayerBackgroundStyle.SOLID -> SolidBackground(pureBlack = pureBlack, modifier = modifier)
        PlayerBackgroundStyle.DARK_GRADIENT -> DarkGradientBackground(modifier = modifier)
        PlayerBackgroundStyle.APPLE_MUSIC -> AppleMusicBackground(
            thumbnailUrl = thumbnailUrl,
            animate = animate,
            modifier = modifier,
        )
        PlayerBackgroundStyle.AMBIENT -> StandaloneAmbientBackground(
            thumbnailUrl = thumbnailUrl,
            animate = animate,
            modifier = modifier,
        )
        PlayerBackgroundStyle.KAWARP -> com.mochyy.mochymusic.ui.player.flagship.KawarpBackdrop(
            thumbnailUrl = thumbnailUrl,
            pureBlack = pureBlack,
            animate = animate,
            modifier = modifier,
        )
    }
}

// the ambient backdrop on its own, from the picture's colours and with its own clock, for previews and the other styles' pages
@Composable
private fun StandaloneAmbientBackground(
    thumbnailUrl: String?,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = com.mochyy.mochymusic.ui.player.flagship.rememberAmbientColors()
    com.mochyy.mochymusic.ui.player.flagship.FollowPicture(colors, thumbnailUrl)
    com.mochyy.mochymusic.ui.player.flagship.AmbientBackdrop(
        colors = colors,
        time = com.mochyy.mochymusic.ui.player.flagship.rememberAmbientClock(running = animate),
        modifier = modifier,
    )
}

@Composable
fun PlayerBackgroundPreview(
    style: PlayerBackgroundStyle,
    thumbnailUrl: String?,
    width: Dp,
    height: Dp,
    animate: Boolean,
    modifier: Modifier = Modifier,

    warp: Boolean = true,
    /** a clock shared by several of these (crossfading copies), so they don't each run their own */
    timeProvider: (() -> Float)? = null,
) {
    if (style != PlayerBackgroundStyle.COVER_GRADIENT) {
        PlayerBackgroundContent(
            style = style,
            thumbnailUrl = thumbnailUrl,
            pureBlack = false,
            modifier = modifier,
            animate = animate,
        )
        return
    }
    Box(modifier = modifier.background(Color(0xFF1A1A1C))) {
        CoverGradientBackdrop(
            thumbnailUrl = thumbnailUrl,
            width = width,
            height = height,
            animate = animate,
            shader = if (warp) rememberWarpShader() else null,
            timeProvider = timeProvider,
        )
    }
}

@Composable
private fun SolidBackground(pureBlack: Boolean, modifier: Modifier = Modifier) {
    val color = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    Box(modifier = modifier.background(color))
}

@Composable
private fun DarkGradientBackground(modifier: Modifier = Modifier) {
    val brush = remember {
        Brush.verticalGradient(
            0f to Color(0xFF2A2A2E),
            0.55f to Color(0xFF1A1A1D),
            1f to Color(0xFF0E0E10),
        )
    }
    Box(modifier = modifier.background(brush))
}

@Composable
private fun AppleMusicBackground(
    thumbnailUrl: String?,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fallbackColorInt = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer

    var palette by remember { mutableStateOf<List<Color>>(emptyList()) }

    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl == null) {
            palette = emptyList()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(thumbnailUrl)
                    .size(100, 100)
                    .allowHardware(false)
                    .build()
                val bitmap = context.imageLoader.execute(request).image?.toBitmap()
                    ?: return@runCatching
                val generated = withContext(Dispatchers.Default) {
                    Palette.from(bitmap)
                        .maximumColorCount(8)
                        .resizeBitmapArea(100 * 100)
                        .generate()
                }
                palette = com.mochyy.mochymusic.ui.theme.PlayerColorExtractor.extractAppleMusicColors(
                    palette = generated,
                    fallbackColor = fallbackColorInt,
                )
            }
        }
    }

    val drift = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var prevMs = 0L
        val cycleRadPerSec = (2.0 * Math.PI / 24.0).toFloat()
        val twoPi = (2.0 * Math.PI).toFloat()
        while (true) {
            withFrameMillis { frameMs ->
                if (prevMs != 0L) {
                    val dtSec = ((frameMs - prevMs).coerceIn(1L, 80L) / 1000f)
                    drift.floatValue = (drift.floatValue + dtSec * cycleRadPerSec) % twoPi
                }
                prevMs = frameMs
            }
        }
    }

    val blobColors = List(BlobCount) { index ->
        animateColorAsState(
            targetValue = palette.getOrNull(index) ?: surfaceColor,
            animationSpec = tween(durationMillis = 800),
            label = "appleMusicBlob$index",
        ).value
    }

    val base = palette.firstOrNull() ?: surfaceColor

    Box(
        modifier = modifier
            .background(base)
            .drawColorBlobs(blobColors) { drift.floatValue },
    )
}

private const val BlobCount = 6

private fun Modifier.drawColorBlobs(
    colors: List<Color>,
    phase: () -> Float,
): Modifier = drawWithCache {
    val radii = FloatArray(colors.size) { index ->
        (size.minDimension * (0.55f + 0.08f * (index % 3))).coerceAtLeast(1f)
    }
    val brushes = List(colors.size) { index ->
        val color = colors[index]
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)),
            center = Offset.Zero,
            radius = radii[index],
        )
    }
    val scrim = Brush.verticalGradient(
        listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.45f)),
    )
    onDrawBehind {
        val t = phase()
        for (index in brushes.indices) {
            val angle = t * (0.6f + index * 0.17f) + index * 1.05f
            val orbitX = size.width * (0.20f + 0.06f * (index % 3))
            val orbitY = size.height * (0.14f + 0.05f * (index % 4))
            val cx = size.width * (0.28f + 0.15f * (index % 3)) + orbitX * kotlin.math.cos(angle)
            val cy = size.height * (0.24f + 0.18f * (index % 4)) + orbitY * kotlin.math.sin(angle)
            val radius = radii[index]
            translate(cx, cy) {
                drawCircle(
                    brush = brushes[index],
                    radius = radius,
                    center = Offset.Zero,
                )
            }
        }
        drawRect(brush = scrim)
    }
}
