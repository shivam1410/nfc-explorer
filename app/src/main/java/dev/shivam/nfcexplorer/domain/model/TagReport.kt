package dev.shivam.nfcexplorer.domain.model

/**
 * What the tag presented before any page was read.
 *
 * Comes from the anticollision exchange and the platform's technology list, so it is
 * available even if every subsequent read fails.
 */
data class TagPresentation(
    val uid: ByteBlock,
    val atqa: ByteBlock? = null,
    val sak: Short? = null,
    val technologies: TagTechnologies = TagTechnologies.EMPTY,
    val chip: ChipProfile = ChipProfile.UNIDENTIFIED,
)

/**
 * Everything established about a tag in one session.
 *
 * A report is produced even when the dump is partial — [memory] carries per-page status and
 * [locks] falls back to unknown verdicts when page `0x02` could not be read. There is no
 * "failed" variant of this type, because a partial answer is still an answer and hiding it
 * behind an error state would throw away the evidence.
 */
data class TagReport(
    val presentation: TagPresentation,
    val identity: TagIdentity,
    val memory: MemoryDump,
    val locks: LockAnalysis,
) {
    val chip: ChipProfile get() = presentation.chip
    val technologies: TagTechnologies get() = presentation.technologies
    val isMemoryComplete: Boolean get() = memory.isComplete
}
