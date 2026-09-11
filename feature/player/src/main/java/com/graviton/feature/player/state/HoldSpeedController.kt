package com.graviton.feature.player.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The player-wide "hold to boost speed" behaviour.
 *
 * A long press anywhere in the player — including on top of a control button — must temporarily
 * raise the playback speed and restore the previous speed on release. It must never activate that
 * control's normal action, and in particular must never open the playback-speed menu; that menu is
 * reachable only by an explicit tap on the speed control.
 */
@Stable
interface HoldSpeedController {
    /** True while a hold is boosting the speed. */
    val isHolding: Boolean

    /**
     * Starts a temporary speed boost.
     *
     * Returns `true` when the boost actually started, which is the caller's signal to suppress the
     * click it would otherwise have performed on release.
     */
    fun startHold(): Boolean

    /** Restores the speed that was active before [startHold]. Safe to call when not holding. */
    fun endHold()
}

/** No-op controller so previews and non-player surfaces do not need to provide one. */
private object NoHoldSpeedController : HoldSpeedController {
    override val isHolding: Boolean = false
    override fun startHold(): Boolean = false
    override fun endHold() = Unit
}

val LocalHoldSpeedController = staticCompositionLocalOf<HoldSpeedController> { NoHoldSpeedController }
