package dev.shivam.nfcexplorer.ui.discovery

import androidx.annotation.StringRes
import dev.shivam.nfcexplorer.R

/**
 * The tag-inspection surfaces, grouped behind one bottom-nav entry.
 *
 * They were six peers in the bottom bar, which put the two things used daily — running an action and
 * reading the log — alongside four that are only interesting while a card is physically present.
 * Grouping them keeps the inspection tools whole while letting the bar reflect what the app is
 * mostly used for.
 */
enum class DiscoverySection(@StringRes val labelRes: Int) {
    TAG(R.string.nav_tag),
    MEMORY(R.string.nav_memory),
    LOCKS(R.string.nav_locks),
    WRITE(R.string.nav_write),
}
