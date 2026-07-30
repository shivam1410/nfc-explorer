package dev.shivam.nfcexplorer.data.nfc

import android.nfc.Tag
import dev.shivam.nfcexplorer.domain.repository.TagHandle

/**
 * Carries an [android.nfc.Tag] across the domain boundary without exposing it.
 *
 * The domain layer needs to refer to "the tag currently in the field" in repository signatures,
 * but it cannot import `android.nfc.Tag`. [TagHandle] is the opaque domain-side name; this is
 * the only place the framework type is unwrapped.
 *
 * A handle is valid only while the tag stays in the field and only for the reader-mode
 * dispatch that produced it.
 */
class AndroidTagHandle(internal val tag: Tag) : TagHandle
