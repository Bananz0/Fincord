package org.akanework.gramophone.logic.data.jellyfin

import androidx.annotation.WorkerThread
import org.akanework.gramophone.logic.data.db.dao.JellyfinIdDao
import org.akanework.gramophone.logic.data.db.entity.JellyfinId

/**
 * Translates Jellyfin GUIDs to the stable [Long] IDs the rest of the app is built around, and back.
 *
 * See [JellyfinId] for why this exists. The whole table is held in memory: a library sync interns
 * one ID per song plus album/artist/genre keys, and a database round trip each would dominate the
 * sync. New IDs are accumulated and written in a single batch by [flush].
 *
 * Not thread safe - the loader owns an instance for the duration of a sync.
 */
class JellyfinIdMap(private val dao: JellyfinIdDao) {

    private val toLocal = HashMap<String, Long>()
    private val toRemote = HashMap<Long, String>()
    private val pending = mutableListOf<JellyfinId>()

    /**
     * Local IDs are allocated here rather than by SQLite's AUTOINCREMENT so that interning stays a
     * pure in-memory operation; [flush] writes the rows with these exact IDs.
     */
    private var nextLocalId = 1L

    @WorkerThread
    fun load() {
        toLocal.clear()
        toRemote.clear()
        pending.clear()
        var maxId = 0L
        for (row in dao.getAll()) {
            toLocal[row.jellyfinId] = row.localId
            toRemote[row.localId] = row.jellyfinId
            if (row.localId > maxId) maxId = row.localId
        }
        nextLocalId = maxId + 1
    }

    /**
     * Returns the stable local ID for [jellyfinId], allocating one if this is the first time it is
     * seen. Null in, null out, so callers can pass through optional server fields.
     */
    fun intern(jellyfinId: String?, itemType: String): Long? {
        if (jellyfinId.isNullOrEmpty()) return null
        toLocal[jellyfinId]?.let { return it }
        val localId = nextLocalId++
        toLocal[jellyfinId] = localId
        toRemote[localId] = jellyfinId
        pending += JellyfinId(localId, jellyfinId, itemType)
        return localId
    }

    /**
     * Genres and other server concepts that are only identified by name get a synthetic key, so
     * they still receive a stable ID.
     */
    fun internName(name: String?, itemType: String): Long? {
        if (name.isNullOrEmpty()) return null
        return intern("$itemType:name:$name", itemType)
    }

    /** The Jellyfin GUID behind a local ID, or null if it was never interned. */
    fun remoteId(localId: Long): String? = toRemote[localId]

    @WorkerThread
    fun flush() {
        if (pending.isEmpty()) return
        dao.insertAll(pending)
        pending.clear()
    }
}
