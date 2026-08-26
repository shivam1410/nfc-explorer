package dev.shivam.nfcexplorer.domain.whatsapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Link-building rules, all of which fail silently when broken: a punctuated number opens an error
 * page instead of a chat, and an unencoded message loses everything after the first `&`.
 */
class WhatsAppTest {

    @Test
    fun `punctuation and spaces are stripped from the number`() {
        assertEquals("917982242069", WhatsApp.normalise("+91 79822 42069"))
        assertEquals("917982242069", WhatsApp.normalise("(+91) 79822-42069"))
    }

    @Test
    fun `a number with no digits is refused`() {
        assertFailsWith<IllegalArgumentException> { WhatsApp.linkFor("not a number", "hi") }
    }

    @Test
    fun `a blank message yields a bare chat link`() {
        assertEquals("https://wa.me/917982242069", WhatsApp.linkFor("+91 79822 42069", ""))
        assertEquals("https://wa.me/917982242069", WhatsApp.linkFor("917982242069", "   "))
    }

    /** `URLEncoder` targets form bodies, where `+` means space; in a query WhatsApp shows a plus. */
    @Test
    fun `spaces encode as percent-20 rather than plus`() {
        val link = WhatsApp.linkFor("917982242069", "on my way")

        assertEquals("https://wa.me/917982242069?text=on%20my%20way", link)
    }

    /** Everything after an unencoded ampersand would be silently dropped by the receiver. */
    @Test
    fun `ampersands and question marks are encoded`() {
        val link = WhatsApp.linkFor("1", "tea & biscuits?")

        assertEquals("https://wa.me/1?text=tea%20%26%20biscuits%3F", link)
    }

    @Test
    fun `emoji survive encoding`() {
        val link = WhatsApp.linkFor("1", "hi ❤")

        assertEquals("https://wa.me/1?text=hi%20%E2%9D%A4", link)
    }

    @Test
    fun `there is a send button id for each WhatsApp app`() {
        // A view id is package-qualified, so Business needs its own; the auto-send tap tries both.
        assertEquals(
            listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send"),
            WhatsApp.SEND_BUTTON_IDS,
        )
    }

    @Test
    fun `both WhatsApp apps are accepted`() {
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), WhatsApp.PACKAGES)
    }
}
