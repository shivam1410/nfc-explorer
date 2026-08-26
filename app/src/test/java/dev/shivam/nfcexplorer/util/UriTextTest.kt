package dev.shivam.nfcexplorer.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The encoder's job is narrow and its trap is double-encoding, so idempotence is tested directly
 * rather than assumed from the character rules.
 */
class UriTextTest {

    @Test
    fun `spaces become percent-20`() {
        assertEquals(
            "https://wa.me/91?text=see%20you%20at%205",
            "https://wa.me/91?text=see you at 5".percentEncodeUnsafe(),
        )
    }

    @Test
    fun `an existing escape is left alone`() {
        // The double-encoding trap: someone who already typed %20 must not get %2520.
        assertEquals(
            "https://wa.me/91?text=Hi%20there",
            "https://wa.me/91?text=Hi%20there".percentEncodeUnsafe(),
        )
    }

    @Test
    fun `encoding twice is the same as encoding once`() {
        val once = "https://wa.me/91?text=see you at 5".percentEncodeUnsafe()

        assertEquals(once, once.percentEncodeUnsafe())
    }

    @Test
    fun `a lone percent is encoded`() {
        // Not the start of an escape, so it is data and has to be escaped itself.
        assertEquals("https://e.com/?a=100%25", "https://e.com/?a=100%".percentEncodeUnsafe())
    }

    @Test
    fun `a percent followed by one hex digit is encoded`() {
        assertEquals("https://e.com/?a=%25A", "https://e.com/?a=%A".percentEncodeUnsafe())
    }

    @Test
    fun `delimiters are left alone`() {
        // A `&` may be a separator the user meant. Guessing would break the links already correct.
        val link = "https://e.com/a/b?x=1&y=2#frag"

        assertEquals(link, link.percentEncodeUnsafe())
    }

    @Test
    fun `unreserved characters are left alone`() {
        val link = "https://e.com/a-b_c.d~e"

        assertEquals(link, link.percentEncodeUnsafe())
    }

    @Test
    fun `characters that can never appear raw are encoded`() {
        assertEquals(
            "https://e.com/?q=%22a%22%3Cb%3E%5Cc%5E%60%7Bd%7C%7D",
            "https://e.com/?q=\"a\"<b>\\c^`{d|}".percentEncodeUnsafe(),
        )
    }

    @Test
    fun `accented characters become their UTF-8 bytes`() {
        assertEquals("https://e.com/?q=caf%C3%A9", "https://e.com/?q=café".percentEncodeUnsafe())
    }

    @Test
    fun `an emoji survives as one character rather than two broken halves`() {
        // A surrogate pair. Encoding char by char would emit two invalid sequences.
        assertEquals(
            "https://wa.me/91?text=%F0%9F%98%80",
            "https://wa.me/91?text=😀".percentEncodeUnsafe(),
        )
    }

    @Test
    fun `an empty string encodes to nothing`() {
        assertEquals("", "".percentEncodeUnsafe())
    }
}
