package dev.shivam.nfcexplorer.data.repository

import android.nfc.tech.MifareUltralight
import dev.shivam.nfcexplorer.data.nfc.AndroidTagHandle
import dev.shivam.nfcexplorer.data.nfc.AndroidUltralightTransport
import dev.shivam.nfcexplorer.data.nfc.TagTechnologyInspector
import dev.shivam.nfcexplorer.di.IoDispatcher
import dev.shivam.nfcexplorer.domain.decoder.StaticLockDecoder
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.model.WriteBatchResult
import dev.shivam.nfcexplorer.domain.repository.TagHandle
import dev.shivam.nfcexplorer.domain.repository.TagRepository
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport
import dev.shivam.nfcexplorer.domain.usecase.ReadTagUseCase
import dev.shivam.nfcexplorer.domain.usecase.WritePagesUseCase
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the tag pipeline off the main thread and translates failures into [Result].
 *
 * Invariant I5 lives here: every exchange happens inside [withContext] on the IO dispatcher, so
 * no tag I/O touches the main thread. The domain layer stays dispatcher-free.
 */
@Singleton
class TagRepositoryImpl @Inject constructor(
    private val inspector: TagTechnologyInspector,
    private val readTag: ReadTagUseCase,
    private val writePages: WritePagesUseCase,
    private val logger: SessionLogger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TagRepository {

    override suspend fun read(handle: TagHandle): Result<TagReport> = withContext(ioDispatcher) {
        val tag = (handle as? AndroidTagHandle)?.tag
            ?: return@withContext Result.failure(
                IllegalArgumentException("unsupported TagHandle: ${handle::class.simpleName}"),
            )

        try {
            val presentation = inspector.inspect(tag)
            logger.info(
                category = CATEGORY,
                message = "tag discovered",
                payload = mapOf(
                    "uid" to presentation.uid.toString(),
                    "technologies" to presentation.technologies.names.size.toString(),
                    "chip" to presentation.chip.family.ifEmpty { "unidentified" },
                    "geometryConfirmed" to presentation.chip.geometryConfirmed.toString(),
                ),
            )

            val ultralight = MifareUltralight.get(tag)
            if (ultralight == null) {
                // Not an Ultralight-family tag. Identity and technologies are still worth
                // reporting, so this is a successful read of an empty dump rather than a failure.
                logger.warn(
                    category = CATEGORY,
                    message = "tag does not expose MifareUltralight; identity only",
                    payload = mapOf("technologies" to presentation.technologies.names.joinToString()),
                )
                return@withContext Result.success(
                    readTag(NoOpUltralightTransport, presentation.copy(chip = UNREADABLE_CHIP)),
                )
            }

            AndroidUltralightTransport(ultralight).use { transport ->
                transport.connect()
                Result.success(readTag(transport, presentation))
            }
        } catch (cancellation: CancellationException) {
            // A cancelled scan must stay cancelled rather than becoming a Result.failure.
            throw cancellation
        } catch (failure: Throwable) {
            logger.error(
                category = CATEGORY,
                message = "read failed",
                payload = mapOf(
                    "exception" to (failure::class.simpleName ?: "Throwable"),
                    "message" to (failure.message ?: ""),
                ),
            )
            Result.failure(failure)
        }
    }

    override suspend fun writePages(
        handle: TagHandle,
        startPage: Int,
        pages: List<ByteArray>,
        expertMode: Boolean,
    ): Result<WriteBatchResult> = withContext(ioDispatcher) {
        val tag = (handle as? AndroidTagHandle)?.tag
            ?: return@withContext Result.failure(
                IllegalArgumentException("unsupported TagHandle: ${handle::class.simpleName}"),
            )

        try {
            val ultralight = MifareUltralight.get(tag)
                ?: return@withContext Result.failure(
                    UnsupportedOperationException("tag does not expose MifareUltralight"),
                )

            AndroidUltralightTransport(ultralight).use { transport ->
                transport.connect()

                // Lock state is read from the tag being written, in this session. The UI's copy
                // comes from an earlier tap and could be stale -- the tag may have been locked in
                // between, and writing against a stale "unlocked" would be exactly the wrong call.
                val locks = StaticLockDecoder.decode(readLockBytes(transport))
                logger.info(
                    category = CATEGORY,
                    message = "lock state re-read before write",
                    payload = mapOf(
                        "lockBytes" to (locks.staticLockBytes?.toString() ?: "unreadable"),
                        "startPage" to startPage.toString(),
                    ),
                )

                Result.success(writePages(transport, startPage, pages, locks, expertMode))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logger.error(
                category = CATEGORY,
                message = "write session failed",
                payload = mapOf(
                    "exception" to (failure::class.simpleName ?: "Throwable"),
                    "message" to (failure.message ?: ""),
                ),
            )
            Result.failure(failure)
        }
    }

    /**
     * The two static lock bytes, straight off the tag.
     *
     * `readPages(2)` returns pages 2-5; the lock bytes are bytes 2 and 3 of page 2. Returns null if
     * the page will not read, which the decoder turns into "lock state unknown" and the guard turns
     * into a refusal — the safe direction.
     */
    private fun readLockBytes(transport: AndroidUltralightTransport): ByteBlock? =
        try {
            val frame = transport.readPages(LOCK_PAGE)
            ByteBlock.ofInts(
                frame[LOCK0_OFFSET].toInt() and 0xFF,
                frame[LOCK1_OFFSET].toInt() and 0xFF,
            )
        } catch (failure: IOException) {
            logger.warn(
                category = CATEGORY,
                message = "could not read lock state; no write will be permitted",
                payload = mapOf("exception" to (failure::class.simpleName ?: "IOException")),
            )
            null
        }

    private companion object {
        const val CATEGORY = "session"

        const val LOCK_PAGE = 2
        const val LOCK0_OFFSET = 2
        const val LOCK1_OFFSET = 3

        /** Zero geometry, so the read pipeline attempts no pages. */
        val UNREADABLE_CHIP = dev.shivam.nfcexplorer.domain.model.ChipProfile.UNIDENTIFIED
    }

    /**
     * Stands in when the tag exposes no page-oriented technology. Never used for I/O — the
     * pipeline sees zero geometry and returns before touching it.
     */
    private object NoOpUltralightTransport : UltralightTransport {
        override val maxTransceiveLength: Int = 0
        override fun connect() = Unit
        override fun close() = Unit
        override fun transceive(command: ByteArray): ByteArray =
            throw UnsupportedOperationException("tag exposes no Ultralight transport")

        override fun readPages(pageOffset: Int): ByteArray =
            throw UnsupportedOperationException("tag exposes no Ultralight transport")

        override fun writePage(pageOffset: Int, data: ByteArray): Unit =
            throw UnsupportedOperationException("tag exposes no Ultralight transport")
    }
}
