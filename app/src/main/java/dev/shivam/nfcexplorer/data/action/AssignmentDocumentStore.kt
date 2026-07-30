package dev.shivam.nfcexplorer.data.action

import kotlinx.coroutines.flow.Flow

/**
 * Stores one opaque document string.
 *
 * A seam, for the same reason as `TagTransport` and `IntentSpec`: `DataStore` is an Android library
 * and will not run in a JVM unit test, so all the *logic* — serialising, merging, finding by UID,
 * degrading on corruption — sits above this interface where a fake can drive it, and only the
 * file-backed implementation is device-verified.
 *
 * [read] and [observe] return null when nothing has been stored yet **or when the store cannot be
 * read**. Callers must treat those identically: the dispatch path runs on a tap, and there is nothing
 * useful to do about an unreadable file except behave as though no assignment exists.
 */
interface AssignmentDocumentStore {

    fun observe(): Flow<String?>

    suspend fun read(): String?

    suspend fun write(document: String)
}
