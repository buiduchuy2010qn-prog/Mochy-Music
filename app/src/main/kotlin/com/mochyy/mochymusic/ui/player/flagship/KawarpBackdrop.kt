package com.mochyy.mochymusic.ui.player.flagship

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.mochyy.mochymusic.ui.component.asAgslPaintShader
import com.mochyy.mochymusic.ui.component.createAgslShader
import com.mochyy.mochymusic.ui.component.setAgslColorUniform
import com.mochyy.mochymusic.ui.component.setAgslUniform
import com.mochyy.mochymusic.ui.player.share.coverPalette
import kotlin.math.cos
import kotlin.math.sin

private val DefaultKawarpColors = listOf(
    Color(0xFF4B2E6B),
    Color(0xFF1F4B6E),
    Color(0xFF6B324B),
    Color(0xFF151821),
)

private const val KawarpAgslSource = """
uniform float2 uRes;
uniform float uTime;
uniform float uDetail;
uniform float uDarken;
layout(color) uniform half4 uC0;
layout(color) uniform half4 uC1;
layout(color) uniform half4 uC2;
layout(color) uniform half4 uC3;

float2 rot2(float2 p, float a) {
    float s = sin(a);
    float c = cos(a);
    return float2(p.x * c - p.y * s, p.x * s + p.y * c);
}

float waveField(float2 p, float t) {
    float v = sin(p.x * 2.1 + t * 0.42) * cos(p.y * 1.9 - t * 0.36);
    float2 q = rot2(p, 0.72);
    v += 0.55 * sin(q.x * 3.4 - t * 0.58 + v * 1.6);
    v += (0.28 * uDetail) * cos(q.y * 5.1 + t * 0.71 - v * 1.2);
    return v;
}

half4 main(float2 fragCoord) {
    float2 safeRes = max(uRes, float2(1.0, 1.0));
    float2 uv = (fragCoord - 0.5 * safeRes) / min(safeRes.x, safeRes.y);
    float t = uTime;

    float2 warp1 = float2(
        waveField(uv + float2(0.3, -0.2), t * 0.75),
        waveField(uv + float2(-0.5, 0.4), t * 0.75 + 1.9)
    );
    float2 warped = uv + warp1 * 0.38;

    float f1 = smoothstep(-1.1, 1.1, waveField(warped, t * 0.85));
    float f2 = smoothstep(-0.9, 1.0, sin(warped.x * 2.6 - warped.y * 2.2 + t * 0.35));
    float radial = clamp(length(uv * float2(0.85, 0.65)), 0.0, 1.4);

    half4 col = mix(uC0, uC1, half(f1));
    col = mix(col, uC2, half(f2 * 0.72));
    col = mix(col, uC3, half(smoothstep(0.35, 1.35, radial) * 0.78));

    // Nhấn nhẹ viền tối trên/dưới để chữ và thanh điều khiển luôn rõ nét
    float yNorm = clamp(fragCoord.y / safeRes.y, 0.0, 1.0);
    float legibility = 1.0 - 0.28 * smoothstep(0.55, 1.0, yNorm) - 0.14 * smoothstep(0.25, 0.0, yNorm);
    col.rgb *= half(clamp(legibility * uDarken, 0.18, 1.0));
    col.a = 1.0h;
    return col;
}
"""

/**
 * Phông nền chất lỏng hữu cơ Kawarp (Domain-Warped AGSL Fluid Shader trên Android 13+,
 * tự thích nghi FPS qua [FrameStrainMonitor] và có dự phòng Canvas mượt cho máy cũ).
 */
@Composable
fun KawarpBackdrop(
    thumbnailUrl: String?,
    pureBlack: Boolean = false,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var extracted by remember { mutableStateOf(DefaultKawarpColors) }

    LaunchedEffect(thumbnailUrl) {
        val loaded = coverPalette(context, thumbnailUrl)
        extracted = buildList {
            addAll(loaded)
            var idx = 0
            while (size < 4) {
                add(DefaultKawarpColors[idx % DefaultKawarpColors.size])
                idx++
            }
        }
    }

    val c0 by animateColorAsState(extracted[0], tween(700), label = "kw0")
    val c1 by animateColorAsState(extracted[1], tween(700), label = "kw1")
    val c2 by animateColorAsState(extracted[2], tween(700), label = "kw2")
    val c3 by animateColorAsState(extracted[3], tween(700), label = "kw3")

    val animTime = remember { mutableFloatStateOf(0f) }
    val strainDetail = remember { mutableFloatStateOf(1f) }
    val strainMonitor = remember { FrameStrainMonitor() }

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var prevMs = 0L
        while (true) {
            withFrameMillis { frameMs ->
                if (strainMonitor.onFrame(frameMs)) {
                    val dtSec = if (prevMs == 0L) 0.016f else ((frameMs - prevMs).coerceIn(1L, 80L) / 1000f)
                    animTime.floatValue = (animTime.floatValue + dtSec * 0.9f) % 628.318f
                    strainDetail.floatValue = strainMonitor.detailScale
                }
                prevMs = frameMs
            }
        }
    }

    val shader: Any? = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) createKawarpAgslShader() else null
    }
    val shaderBrush = remember(shader) {
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            createKawarpBrush(shader)
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C10))
            .drawWithCache {
                val darken = if (pureBlack) 0.56f else 0.78f
                val r0 = size.maxDimension * 0.75f
                val r1 = size.maxDimension * 0.70f
                val r2 = size.maxDimension * 0.65f
                val b0 = if (shader == null || shaderBrush == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Brush.radialGradient(
                        colors = listOf(c0.copy(alpha = 0.75f * darken), Color.Transparent),
                        center = Offset.Zero,
                        radius = r0.coerceAtLeast(1f),
                    )
                } else null
                val b1 = if (b0 != null) {
                    Brush.radialGradient(
                        colors = listOf(c1.copy(alpha = 0.65f * darken), Color.Transparent),
                        center = Offset.Zero,
                        radius = r1.coerceAtLeast(1f),
                    )
                } else null
                val b2 = if (b0 != null) {
                    Brush.radialGradient(
                        colors = listOf(c2.copy(alpha = 0.45f * darken), Color.Transparent),
                        center = Offset.Zero,
                        radius = r2.coerceAtLeast(1f),
                    )
                } else null
                onDrawBehind {
                    if (shader != null && shaderBrush != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        updateKawarpUniforms(
                            shader = shader,
                            width = size.width,
                            height = size.height,
                            time = animTime.floatValue,
                            detail = strainDetail.floatValue,
                            darken = darken,
                            c0 = c0.toArgb(),
                            c1 = c1.toArgb(),
                            c2 = c2.toArgb(),
                            c3 = c3.toArgb(),
                        )
                        drawRect(brush = shaderBrush)
                    } else if (b0 != null && b1 != null && b2 != null) {
                        val t = animTime.floatValue
                        val cx1 = size.width * (0.35f + 0.18f * cos(t * 0.6f))
                        val cy1 = size.height * (0.32f + 0.16f * sin(t * 0.5f))
                        val cx2 = size.width * (0.68f + 0.18f * sin(t * 0.45f))
                        val cy2 = size.height * (0.62f + 0.15f * cos(t * 0.55f))
                        drawRect(color = c3.copy(alpha = 0.9f))
                        translate(cx1, cy1) { drawCircle(brush = b0, center = Offset.Zero, radius = r0) }
                        translate(cx2, cy2) { drawCircle(brush = b1, center = Offset.Zero, radius = r1) }
                        translate(size.width - cx1 * 0.5f, size.height * 0.85f) {
                            drawCircle(brush = b2, center = Offset.Zero, radius = r2)
                        }
                    }
                }
            },
    )
}

@RequiresApi(33)
private fun createKawarpAgslShader(): Any = createAgslShader(KawarpAgslSource)

@RequiresApi(33)
private fun createKawarpBrush(shader: Any): ShaderBrush = ShaderBrush(shader.asAgslPaintShader())

@RequiresApi(33)
private fun updateKawarpUniforms(
    shader: Any,
    width: Float,
    height: Float,
    time: Float,
    detail: Float,
    darken: Float,
    c0: Int,
    c1: Int,
    c2: Int,
    c3: Int,
) {
    shader.setAgslUniform("uRes", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
    shader.setAgslUniform("uTime", time)
    shader.setAgslUniform("uDetail", detail)
    shader.setAgslUniform("uDarken", darken)
    shader.setAgslColorUniform("uC0", c0)
    shader.setAgslColorUniform("uC1", c1)
    shader.setAgslColorUniform("uC2", c2)
    shader.setAgslColorUniform("uC3", c3)
}
