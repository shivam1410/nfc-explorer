package dev.shivam.nfcexplorer.domain.update

/**
 * Compares version strings that were not necessarily written to be compared.
 *
 * Release tags in this repo are not bare semver — `sleep-cycle-toggle-v0.1.0` is a real one — so the
 * numeric run is extracted rather than assumed to be the whole string. Anything that yields no digits
 * is treated as uncomparable instead of guessed at, because guessing here means either nagging about
 * an update that does not exist or silently hiding one that does.
 */
object AppVersion {

    /** The last dotted numeric run in [raw], e.g. `sleep-cycle-toggle-v0.1.0` -> `[0, 1, 0]`. */
    fun parse(raw: String): List<Int>? {
        val match = NUMERIC.findAll(raw).lastOrNull() ?: return null
        return match.value.split('.').mapNotNull(String::toIntOrNull).takeIf { it.isNotEmpty() }
    }

    /**
     * Whether [candidate] is a strictly later version than [current].
     *
     * False when either side is uncomparable: an unparseable tag must not be announced as an update.
     * Shorter versions are padded, so `1.2` and `1.2.0` are the same version rather than one being
     * mysteriously older.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val new = parse(candidate) ?: return false
        val old = parse(current) ?: return false
        val width = maxOf(new.size, old.size)
        for (i in 0 until width) {
            val a = new.getOrElse(i) { 0 }
            val b = old.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private val NUMERIC = Regex("""\d+(?:\.\d+)*""")
}
