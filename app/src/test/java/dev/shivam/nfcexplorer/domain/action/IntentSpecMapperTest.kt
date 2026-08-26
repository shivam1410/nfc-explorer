package dev.shivam.nfcexplorer.domain.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IntentSpecMapperTest {

    // --- Mapping ---

    @Test
    fun `launch app maps to launching its package`() {
        val spec = IntentSpecMapper.map(TagAction.LaunchApp("com.example.sleep"))

        assertEquals(IntentSpec.LaunchPackage("com.example.sleep"), spec)
    }

    @Test
    fun `open uri maps to a view intent carrying the uri`() {
        val spec = IntentSpecMapper.map(
            TagAction.OpenUri("https://music.youtube.com/playlist?list=PLabc"),
        )

        assertEquals(
            IntentSpec.ActivityIntent(
                action = "android.intent.action.VIEW",
                uri = "https://music.youtube.com/playlist?list=PLabc",
            ),
            spec,
        )
    }

    @Test
    fun `send intent passes action, uri and extras through verbatim`() {
        // Verbatim matters: the user typed these from another app's documentation, and silently
        // normalising any of them would break an integration in a way that is hard to diagnose.
        val action = TagAction.SendIntent(
            action = "com.urbandroid.sleep.alarmclock.START_SLEEP_TRACK",
            uri = "myapp://start",
            extras = mapOf("source" to "nfc", "id" to "42"),
        )

        val spec = IntentSpecMapper.map(action)

        assertEquals(
            IntentSpec.ActivityIntent(
                action = "com.urbandroid.sleep.alarmclock.START_SLEEP_TRACK",
                uri = "myapp://start",
                extras = mapOf("source" to "nfc", "id" to "42"),
            ),
            spec,
        )
    }

    @Test
    fun `send intent without a uri produces a spec without one`() {
        val spec = IntentSpecMapper.map(TagAction.SendIntent(action = "com.example.PING"))

        assertIs<IntentSpec.ActivityIntent>(spec)
        assertEquals(null, spec.uri)
        assertEquals(emptyMap(), spec.extras)
    }

    @Test
    fun `each media command maps to its own key code`() {
        // Asserted against the real KeyEvent constants, so a transposed pair would show up here
        // rather than as "next track skips backwards" on the device.
        assertEquals(
            IntentSpec.MediaKeyEvent(85),
            IntentSpecMapper.map(TagAction.MediaCommand(MediaKey.PLAY_PAUSE)),
        )
        assertEquals(
            IntentSpec.MediaKeyEvent(87),
            IntentSpecMapper.map(TagAction.MediaCommand(MediaKey.NEXT)),
        )
        assertEquals(
            IntentSpec.MediaKeyEvent(88),
            IntentSpecMapper.map(TagAction.MediaCommand(MediaKey.PREVIOUS)),
        )
    }

    @Test
    fun `every action type is mapped`() {
        // Guards against a new variant being added and silently falling through in some future
        // non-exhaustive branch.
        val all = listOf(
            TagAction.LaunchApp("a.b"),
            TagAction.OpenUri("https://x"),
            TagAction.SendIntent("A"),
            TagAction.MediaCommand(MediaKey.NEXT),
        )

        assertEquals(all.size, all.map { IntentSpecMapper.map(it) }.size)
        assertEquals(all.size, MediaKey.entries.size + 1)
    }

    // --- Validation happens at construction, not at map time ---

    @Test
    fun `a blank package name is rejected when the action is created`() {
        assertFailsWith<IllegalArgumentException> { TagAction.LaunchApp("  ") }
    }

    @Test
    fun `a uri without a scheme is rejected when the action is created`() {
        // "music.youtube.com/playlist" looks fine and resolves to nothing. Catching it at creation
        // beats discovering it on a tap, when no user is watching.
        assertFailsWith<IllegalArgumentException> { TagAction.OpenUri("music.youtube.com/playlist") }
        assertFailsWith<IllegalArgumentException> { TagAction.OpenUri("   ") }
    }

    @Test
    fun `common schemes are accepted`() {
        listOf(
            "https://music.youtube.com/playlist?list=PL",
            "http://example.com",
            "myapp://deep/link",
            "tel:+15551234",
            "geo:0,0?q=cafe",
        ).forEach { uri ->
            TagAction.OpenUri(uri) // must not throw
        }
    }

    @Test
    fun `a blank intent action is rejected`() {
        assertFailsWith<IllegalArgumentException> { TagAction.SendIntent(action = " ") }
    }

    @Test
    fun `a blank extra key is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TagAction.SendIntent(action = "A", extras = mapOf("" to "v"))
        }
    }

    @Test
    fun `a send intent uri without a scheme is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TagAction.SendIntent(action = "A", uri = "no-scheme/path")
        }
    }

    // --- WhatsApp ---
    //
    // This branch had no coverage at all, which is how three defects reached a release: the send
    // button was sought by a view id the service was not configured to report, the label fallback is
    // English-only, and WhatsApp Business was refused outright by the foreground guard.

    @Test
    fun `a whatsapp message without auto-send is just a view of the wa_me link`() {
        val spec = IntentSpecMapper.map(
            TagAction.WhatsAppMessage(phoneNumber = "+91 79822 42069", message = "on my way"),
        )

        assertEquals(
            IntentSpec.ActivityIntent(
                action = IntentSpecMapper.ACTION_VIEW,
                uri = "https://wa.me/917982242069?text=on%20my%20way",
            ),
            spec,
        )
    }

    @Test
    fun `auto-send opens the chat and then presses send`() {
        val spec = IntentSpecMapper.map(
            TagAction.WhatsAppMessage(
                phoneNumber = "919999900000",
                message = "on my way",
                autoSend = true,
            ),
        )

        val sequence = assertIs<IntentSpec.Sequence>(spec)
        assertEquals(2, sequence.specs.size)
        assertIs<IntentSpec.ActivityIntent>(sequence.specs[0])
        assertIs<IntentSpec.TapNode>(sequence.specs[1])
        // The gap is load-bearing: the button does not exist until the conversation is drawn.
        assertEquals(TagAction.DEFAULT_GAP_MILLIS, sequence.gapMillis)
    }

    @Test
    fun `auto-send accepts either WhatsApp app in the foreground`() {
        // Requiring com.whatsapp alone failed outright on a phone running WhatsApp Business.
        val sequence = assertIs<IntentSpec.Sequence>(
            IntentSpecMapper.map(
                TagAction.WhatsAppMessage(
                    phoneNumber = "919999900000",
                    message = "hi",
                    autoSend = true,
                ),
            ),
        )

        val tap = assertIs<IntentSpec.TapNode>(sequence.specs[1])
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), tap.requireForegroundPackages)
    }

    @Test
    fun `auto-send looks for a send button in both WhatsApp apps`() {
        // A view id carries its package, so one id cannot cover both apps.
        val sequence = assertIs<IntentSpec.Sequence>(
            IntentSpecMapper.map(
                TagAction.WhatsAppMessage(
                    phoneNumber = "919999900000",
                    message = "hi",
                    autoSend = true,
                ),
            ),
        )

        val tap = assertIs<IntentSpec.TapNode>(sequence.specs[1])
        assertEquals(
            listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send"),
            tap.viewIds,
        )
    }

    @Test
    fun `auto-send keeps a label fallback for when no id matches`() {
        val sequence = assertIs<IntentSpec.Sequence>(
            IntentSpecMapper.map(
                TagAction.WhatsAppMessage(
                    phoneNumber = "919999900000",
                    message = "hi",
                    autoSend = true,
                ),
            ),
        )

        val tap = assertIs<IntentSpec.TapNode>(sequence.specs[1])
        assertEquals("Send", tap.contentDescription)
    }

    @Test
    fun `a stored tap node names one target rather than several`() {
        val spec = IntentSpecMapper.map(
            TagAction.TapNode(viewId = "com.example:id/go", requireForegroundPackage = "com.example"),
        )

        val tap = assertIs<IntentSpec.TapNode>(spec)
        assertEquals(listOf("com.example:id/go"), tap.viewIds)
        assertEquals(setOf("com.example"), tap.requireForegroundPackages)
    }

    @Test
    fun `a tap node with only a label carries no view ids`() {
        val spec = IntentSpecMapper.map(TagAction.TapNode(contentDescription = "Go"))

        val tap = assertIs<IntentSpec.TapNode>(spec)
        assertEquals(emptyList(), tap.viewIds)
        assertEquals(emptySet(), tap.requireForegroundPackages)
    }
}
