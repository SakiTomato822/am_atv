package dev.amatv.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** One continuous spring; retargeting never cancels its clock or loses velocity. */
internal class FocusFrameMotion {
    private val values = FloatArray(5)
    private val velocities = FloatArray(5)
    private val anchor = FloatArray(5)
    private var anchorKey: Any? = null
    private var initialized = false
    var opacity = 0f
        private set
    var isMoving = false
        private set
    val left get() = values[0]
    val top get() = values[1]
    val width get() = values[2]
    val height get() = values[3]
    val radius get() = values[4]

    fun advance(
        deltaNanos: Long,
        left: Float, top: Float, width: Float, height: Float, radius: Float,
        visible: Boolean,
        followImmediately: Boolean = false,
        key: Any? = null,
    ) {
        if (!initialized && !visible) return
        val snap = !initialized || followImmediately || (visible && opacity == 0f)
        initialized = true
        val seconds = (deltaNanos / 1_000_000_000.0).coerceIn(0.0, 0.25)
        var moving = false
        for (index in values.indices) {
            val target = when (index) {
                0 -> left
                1 -> top
                2 -> width
                3 -> height
                else -> radius
            }
            // Layout motion belongs to the object, not to another focus
            // transition. Transport the spring with the same object's anchor
            // so a scrolling viewport never creates a second chase/reversal.
            if (initialized && key != null && key == anchorKey) {
                values[index] += target - anchor[index]
            }
            anchor[index] = target
            if (snap) {
                values[index] = target
                velocities[index] = 0f
                continue
            }
            val stiffness = if (index < 2) 430.0 else 500.0
            val damping = if (index < 2) 0.88 else 0.92
            val frequency = sqrt(stiffness)
            val decay = damping * frequency
            val damped = frequency * sqrt(1.0 - damping * damping)
            val displacement = (values[index] - target).toDouble()
            val velocity = velocities[index].toDouble()
            val envelope = exp(-decay * seconds)
            val c = cos(damped * seconds)
            val s = sin(damped * seconds)
            values[index] = target + (envelope * (
                displacement * c + (velocity + decay * displacement) / damped * s
            )).toFloat()
            velocities[index] = (envelope * (
                velocity * c - (decay * velocity + stiffness * displacement) / damped * s
            )).toFloat()
            if (abs(values[index] - target) < 0.05f && abs(velocities[index]) < 0.5f) {
                values[index] = target
                velocities[index] = 0f
            } else moving = true
        }
        anchorKey = key
        val targetOpacity = if (visible) 1f else 0f
        opacity = if (snap && visible) 1f else {
            (targetOpacity + (opacity - targetOpacity) * exp(-seconds / 0.065)).toFloat()
        }
        if (abs(opacity - targetOpacity) < 0.002f) opacity = targetOpacity
        isMoving = moving || opacity != targetOpacity
    }
}
