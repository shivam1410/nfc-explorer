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

    /** WhatsApp Business, a separate app with its own package and its own copy of every view id. */
    const val BUSINESS_PACKAGE = "com.whatsapp.w4b"

    /**
     * Both WhatsApp apps.
     *
     * Auto-send used to require `com.whatsapp` in the foreground and nothing else, so on a phone
     * running WhatsApp Business it failed outright with "expected com.whatsapp in the foreground but
     * found com.whatsapp.w4b" -- the chat opened, the message sat there, and nothing was pressed.
     * Which of the two answers a `wa.me` link is the user's choice, not ours, so both are accepted.
     */
    val PACKAGES = setOf(PACKAGE, BUSINESS_PACKAGE)

    /**
     * The send control, by view id, one per app.
     *
     * A view id is package-qualified, so there is no single id that covers both apps and the caller
     * has to try each. Ids are the preferred route because they are never translated.
     */
    val SEND_BUTTON_IDS = PACKAGES.map { "$it:id/send" }

    /**
     * The send control, by accessibility label.
     *
     * The fallback for when no id matches -- a WhatsApp layout change, or a build that labels the
     * button differently. Kept because it survives redesigns that move ids around, but it cannot be
     * the primary route: it is English, so on a translated WhatsApp it matches nothing.
     */
    const val SEND_BUTTON_DESCRIPTION = "Send"

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
