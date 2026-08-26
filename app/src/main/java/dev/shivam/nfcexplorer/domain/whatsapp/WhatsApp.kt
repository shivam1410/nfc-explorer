package dev.shivam.nfcexplorer.domain.whatsapp

import java.net.URLEncoder

/**
 * Builds `wa.me` links, as pure functions.
 *
 * The link format is unforgiving in ways that fail silently: a number carrying `+`, spaces or dashes
 * opens WhatsApp on an error page rather than a chat, and an unencoded message loses everything from
 * the first `&` onwards. Both are the kind of bug that only shows up on the one tag someone actually
 * uses, so the rules live here with tests rather than inline at the call site.
 */
object WhatsApp {

    const val PACKAGE = "com.whatsapp"

    /**
     * Digits only.
     *
     * `wa.me` wants a full international number with no punctuation and no leading `+`. Anything a
     * contact picker or a human types — `+91 79822 42069`, `(079) 8224-2069` — reduces to the same
     * digits, which is exactly what makes picking from contacts safe to accept verbatim.
     */
    fun normalise(rawNumber: String): String = rawNumber.filter(Char::isDigit)

    /**
     * A `https://wa.me/<number>?text=<message>` link.
     *
     * The message is percent-encoded, including spaces as `%20` rather than `+`: `URLEncoder` targets
     * form bodies, where `+` means space, but in a query value WhatsApp renders a literal plus.
     */
    fun linkFor(rawNumber: String, message: String): String {
        val number = normalise(rawNumber)
        require(number.isNotEmpty()) { "a phone number must contain digits" }
        if (message.isBlank()) return "$BASE$number"
        val encoded = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        return "$BASE$number?text=$encoded"
    }

    private const val BASE = "https://wa.me/"
}
