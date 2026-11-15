package com.mochyy.mochymusic.ui.player.flagship

/**
 * Giám sát áp lực khung hình (FPS strain monitor) theo thời gian thực để tự động giảm tải
 * shader nền khi máy nóng hoặc chạy ở tần số quét cao / tiết kiệm pin.
 */
internal class FrameStrainMonitor {
    private var lastFrameMs: Long = 0L
    private var emaDeltaMs: Float = 16.6f
    private var frameCounter: Int = 0

    var detailScale: Float = 1.0f
        private set

    fun onFrame(frameMs: Long): Boolean {
        if (lastFrameMs > 0L) {
            val delta = (frameMs - lastFrameMs).coerceIn(1L, 120L).toFloat()
            emaDeltaMs = emaDeltaMs * 0.88f + delta * 0.12f
        }
        lastFrameMs = frameMs
        frameCounter++

        val skipMod = when {
            emaDeltaMs > 36f -> {
                detailScale = 0.62f
                3
            }
            emaDeltaMs > 24f -> {
                detailScale = 0.80f
                2
            }
            else -> {
                detailScale = 1.00f
                1
            }
        }
        return frameCounter % skipMod == 0
    }
}
