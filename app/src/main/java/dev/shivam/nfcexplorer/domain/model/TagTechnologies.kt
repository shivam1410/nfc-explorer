package dev.shivam.nfcexplorer.domain.model

/**
 * One `android.nfc.tech.*` interface the tag reports.
 *
 * [maxTransceiveLength] and [timeoutMillis] are null when that technology does not expose
 * them — only some do, and inventing a number would misrepresent the tag. [extras] carries
 * technology-specific technical values (`historicalBytes`, `hiLayerResponse`, ...) as raw
 * key/value pairs; keys are protocol names, not translatable labels.
 */
data class TechnologyInfo(
    val name: String,
    val maxTransceiveLength: Int? = null,
    val timeoutMillis: Int? = null,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * The tag's advertised technology set, in the order the platform reported it.
 */
data class TagTechnologies(
    val available: List<TechnologyInfo>,
) {
    fun has(name: String): Boolean = available.any { it.name == name }

    val names: List<String> get() = available.map { it.name }

    companion object {
        val EMPTY = TagTechnologies(emptyList())
    }
}
