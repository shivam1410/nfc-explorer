package dev.shivam.nfcexplorer.domain.feedback

/**
 * The tap tone's loudness, as a percentage the user sets and as the gain `MediaPlayer` wants.
 *
 * Trivial arithmetic, hoisted out of the player for one reason: it is the only part of the volume
 * setting that can be tested on the JVM, and an off-by-a-factor-of-100 here is silent — a tone that
 * plays at 1% sounds exactly like a tone that failed to play at all.
 */
object FeedbackVolume {

    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100

    /** Loud enough to hear over a hand moving away from a phone, quiet enough not to startle. */
    const val DEFAULT_PERCENT = 70

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /**
     * The 0f..1f gain for `MediaPlayer.setVolume`.
     *
     * Clamps rather than trusting its caller. The store clamps on write, but this is also called
     * straight from the settings preview while a slider is being dragged, and a gain above 1f is
     * undefined behaviour rather than an error.
     */
    fun gain(percent: Int): Float = clamp(percent) / MAX_PERCENT.toFloat()
}
