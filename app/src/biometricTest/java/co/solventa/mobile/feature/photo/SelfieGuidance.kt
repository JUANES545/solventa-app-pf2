package co.solventa.mobile.feature.photo

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal enum class SelfieHint { PREPARING, NO_FACE, MULTIPLE, CENTER, CLOSER, FARTHER, STRAIGHT, HOLD, CAPTURING }

// Geometry is relative to the displayed oval, never raw sensor pixels.
internal data class SelfiePosition(
    val centerX: Float, val centerY: Float, val width: Float, val height: Float,
    val pitch: Float, val yaw: Float, val roll: Float,
    val enoughPixels: Boolean = true,
)

internal class SelfieGuidance {
    companion object {
        const val STABLE_MS = 900L
        const val MAX_FRAME_AGE_MS = 300L
        const val MAX_GAP_MS = 300L
    }

    private val fired = AtomicBoolean(false)
    private var anchor: SelfiePosition? = null
    private var since = -1L
    private var lastTime = -1L
    private var lastFrame = -1L

    @Synchronized
    fun reset() {
        anchor = null
        since = -1
        lastTime = -1
    }

    @Synchronized
    fun update(
        count: Int, position: SelfiePosition?, receivedMs: Long, nowMs: Long, frameTimestamp: Long,
        active: Boolean,
    ): SelfieHint {
        if (fired.get()) return SelfieHint.PREPARING
        if (!active || receivedMs < 0 || nowMs < receivedMs || nowMs - receivedMs > MAX_FRAME_AGE_MS ||
            frameTimestamp <= lastFrame || nowMs <= lastTime) {
            reset()
            return SelfieHint.PREPARING
        }
        lastFrame = frameTimestamp
        val hint = when {
            count == 0 -> SelfieHint.NO_FACE
            count != 1 -> SelfieHint.MULTIPLE
            position == null || !listOf(position.centerX, position.centerY, position.width, position.height,
                position.pitch, position.yaw, position.roll).all(Float::isFinite) -> SelfieHint.PREPARING
            position.centerX !in .4f.. .6f || position.centerY !in .4f.. .6f -> SelfieHint.CENTER
            !position.enoughPixels || position.width < .62f || position.height < .62f -> SelfieHint.CLOSER
            position.width > .96f || position.height > .98f -> SelfieHint.FARTHER
            position.centerX - position.width / 2 < 0f || position.centerX + position.width / 2 > 1f ||
                position.centerY - position.height / 2 < 0f || position.centerY + position.height / 2 > 1f -> SelfieHint.CENTER
            abs(position.pitch) > 12f || abs(position.yaw) > 12f || abs(position.roll) > 10f -> SelfieHint.STRAIGHT
            else -> SelfieHint.HOLD
        }
        if (hint != SelfieHint.HOLD) {
            reset()
            return hint
        }
        val current = requireNotNull(position)
        val previous = anchor
        val moved = previous != null && (
            abs(current.centerX - previous.centerX) > .04f ||
                abs(current.centerY - previous.centerY) > .04f ||
                abs(current.width - previous.width) > .06f ||
                abs(current.height - previous.height) > .06f ||
                abs(current.pitch - previous.pitch) > 5f ||
                abs(current.yaw - previous.yaw) > 5f || abs(current.roll - previous.roll) > 5f)
        if (previous == null || moved || nowMs - lastTime > MAX_GAP_MS) {
            anchor = current
            since = nowMs
        }
        lastTime = nowMs
        lastFrame = frameTimestamp
        return if (nowMs - since >= STABLE_MS && fired.compareAndSet(false, true)) {
            reset()
            SelfieHint.CAPTURING
        } else SelfieHint.HOLD
    }
}
