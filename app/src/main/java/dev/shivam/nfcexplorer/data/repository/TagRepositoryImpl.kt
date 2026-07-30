package dev.shivam.nfcexplorer.data.repository

import android.nfc.tech.MifareUltralight
import dev.shivam.nfcexplorer.data.nfc.AndroidTagHandle
import dev.shivam.nfcexplorer.data.nfc.AndroidUltralightTransport
import dev.shivam.nfcexplorer.data.nfc.TagTechnologyInspector
import dev.shivam.nfcexplorer.di.IoDispatcher
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.model.WriteOutcome
import dev.shivam.nfcexplorer.domain.repository.TagHandle
import dev.shivam.nfcexplorer.domain.repository.TagRepository
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport
import dev.shivam.nfcexplorer.domain.usecase.ReadTagUseCase
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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

    override suspend fun writePage(
        handle: TagHandle,
        page: Int,
        data: ByteArray,
        expertMode: Boolean,
    ): Result<WriteOutcome> = withContext(ioDispatcher) {
        Result.failure(NotImplementedError("guarded write lands in Phase 4 Task 4.1"))
    }

    private companion object {
        const val CATEGORY = "session"

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
