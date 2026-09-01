package dev.shivam.nfcexplorer.domain.feedback

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tone at 1% is indistinguishable from a tone that never played, so the conversion between the
 * percentage the user sets and the gain the player takes is worth pinning down.
 */
class FeedbackVolumeTest {

    @Test
    fun `a percentage below zero clamps to silence`() {
        assertEquals(0, FeedbackVolume.clamp(-5))
    }

    @Test
    fun `a percentage above one hundred clamps to full`() {
        assertEquals(100, FeedbackVolume.clamp(140))
    }

    @Test
    fun `a percentage in range is left alone`() {
        assertEquals(70, FeedbackVolume.clamp(70))
    }

    @Test
    fun `silence is zero gain`() {
        assertEquals(0f, FeedbackVolume.gain(0))
    }

    @Test
    fun `full is unity gain`() {
        assertEquals(1f, FeedbackVolume.gain(100))
    }

    @Test
    fun `half is half gain, not fifty`() {
        assertEquals(0.5f, FeedbackVolume.gain(50))
    }

    @Test
    fun `gain clamps its own input rather than trusting the caller`() {
        // Called straight from the settings preview while a slider is mid-drag, so it cannot assume
        // the store has already clamped. A gain above 1f is undefined, not an error.
        assertEquals(0f, FeedbackVolume.gain(-5))
        assertEquals(1f, FeedbackVolume.gain(140))
    }
}
