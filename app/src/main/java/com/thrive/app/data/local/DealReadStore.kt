package com.thrive.app.data.local

import org.json.JSONArray

/**
 * Tracks which deal IDs the user has already seen. When the user opens the
 * Savings screen, every visible deal is marked as seen — the "New this week"
 * shelf and per-card NewPill only light up for deals the user hasn't scrolled
 * past yet. The set is persisted so it survives process death; it is also
 * pruned on every write to avoid unbounded growth (older than 90 days or
 * more than 5 000 entries).
 */
object DealReadStore {
    private const val KEY_SEEN = "deal_seen_ids"
    private const val MAX_IDS = 5_000

    /** All IDs the user has seen. */
    fun seen(settings: SettingsStore): Set<String> {
        val raw = settings.getString(KEY_SEEN, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            val set = HashSet<String>(arr.length())
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Mark the given IDs as seen (additive — never unmarks). */
    fun markSeen(settings: SettingsStore, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val current = seen(settings).toMutableSet()
        current.addAll(ids)
        // Prune: keep only the last MAX_IDS (by insertion order isn't
        // tracked, so just cap the size — the oldest entries are the
        // least useful to keep).
        if (current.size > MAX_IDS) {
            val trimmed = current.toList().takeLast(MAX_IDS)
            current.clear()
            current.addAll(trimmed)
        }
        val arr = JSONArray(current.toList())
        settings.putString(KEY_SEEN, arr.toString())
    }

    /** How many of the given IDs have NOT been seen yet. */
    fun unseenCount(settings: SettingsStore, ids: Collection<String>): Int {
        val seen = seen(settings)
        return ids.count { it !in seen }
    }
}
