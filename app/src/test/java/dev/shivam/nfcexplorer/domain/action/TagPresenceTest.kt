package dev.shivam.nfcexplorer.domain.action

import dev.shivam.nfcexplorer.domain.transport.TagFieldLostException
import dev.shivam.nfcexplorer.fake.FakeUltralightTransport
import dev.shivam.nfcexplorer.fake.Mf0icu1Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The check that makes the trigger's guard mean something.
 *
 * The third review of this codebase found that the guard's "the intent carried a tag" condition was a
 * tautology: the activity null-checked the tag and then passed `true`, so the condition restated what
 * the null-check had already established. It proved nothing about where the intent came from.
 *
 * It was also demonstrably reachable — `adb shell am start -n .../TagActionActivity -a
 * android.nfc.action.TECH_DISCOVERED` starts the activity from uid 2000, an ordinary third-party
 * caller, with a spoofed NFC action string. A `Tag` is a `Parcelable` with a public `CREATOR`, so a
 * hostile caller can hand-build one carrying any UID it likes.
 *
 * What such a caller cannot fabricate is a tag that *answers*. The tag handle inside a `Tag` is issued
 * by the NFC service for a live discovery session, so opening a connection succeeds only while a real
 * tag sits in the field. That is the unforgeable signal, and this is where it is checked.
 *
 * It does not defend against a cloned UID — see the note in [TagPresence].
 */
class TagPresenceTest {

    @Test
    fun `a tag that answers is present`() {
        val transport = FakeUltralightTransport(Mf0icu1Fixtures.unlockedHotelCard())

        assertEquals(TagPresence.Answer.Live, TagPresence.check(transport))
    }

    @Test
    fun `a forged tag that cannot be connected to is not present`() {
        // The hostile case: a hand-built Tag parcel carries a UID but no live session behind it, so
        // the connection attempt fails.
        val transport = FakeUltralightTransport(
            Mf0icu1Fixtures.unlockedHotelCard(),
            failOnConnect = true,
        )

        // The cause travels with the answer: the trigger has no UI, so a tap that does nothing has to
        // be explainable from the log afterwards.
        val answer = assertIs<TagPresence.Answer.Absent>(TagPresence.check(transport))
        assertIs<TagFieldLostException>(answer.cause)
    }

    @Test
    fun `no transport at all is not present`() {
        // The tag's technology is not one this app can talk to, so nothing can be proven about it —
        // and nothing was attempted, so there is no cause to report.
        val answer = assertIs<TagPresence.Answer.Absent>(TagPresence.check(null))
        assertNull(answer.cause)
    }

    @Test
    fun `the connection is released either way`() {
        // This runs on every tap, including taps that do nothing. Leaving the tag connected would
        // block the next reader, and on the refusal path there is nothing else to clean it up.
        val answering = FakeUltralightTransport(Mf0icu1Fixtures.unlockedHotelCard())
        val refusing = FakeUltralightTransport(
            Mf0icu1Fixtures.unlockedHotelCard(),
            failOnConnect = true,
        )

        TagPresence.check(answering)
        TagPresence.check(refusing)

        assertTrue(answering.isClosed, "an answering tag must be released")
        assertTrue(refusing.isClosed, "a refusing tag must be released too")
    }
}
