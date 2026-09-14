package org.akanework.gramophone.logic.data.playcounts

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream

/**
 * Reads a listening history out of an archive a service handed the user.
 *
 * Spotify, Apple Music and YouTube expose no play counts over any API - between them they offer a
 * fifty-item recently-played list and two ranked charts with no numbers on them - so the only
 * complete history any of them will give up is the export you request from your account and
 * receive days later. This turns those files into per-track tallies.
 *
 * Everything here is written to be tolerant. These are undocumented formats that change without
 * notice, arrive in several vintages at once, and are nested differently depending on which button
 * the user pressed to request them; a parser that insisted on one exact shape would be wrong within
 * a year. Unrecognised rows are skipped rather than fatal, and the import preview shows the user
 * how much was understood before anything is written.
 *
 * It is also written to hold none of it. These exports are routinely hundreds of megabytes - a
 * heavy Takeout `watch-history.json` on its own can be half a gigabyte - and a phone cannot hold
 * one as a String, let alone as a parsed tree. Every format here is read as a stream and reduced to
 * a tally as it goes, so the memory cost is the number of distinct tracks rather than the size of
 * the file.
 */
object PlayHistoryArchive {

    private const val TAG = "PlayHistoryArchive"

    /**
     * Spotify counts a stream once thirty seconds have played, and its export records every start
     * including skips. Counting those would turn a flicked-past track into listening.
     */
    private const val SPOTIFY_MIN_MS = 30_000L

    /**
     * Apple records a play event's duration too, and the same reasoning applies. Its own threshold
     * is not published, so Spotify's is borrowed rather than invented.
     */
    private const val APPLE_MIN_MS = 30_000L

    /** Guards against a wrong file - a whole Takeout, say - being walked entry by entry. */
    private const val MAX_ENTRIES = 2_000_000

    data class Parsed(
        val source: PlayCountSource,
        val tracks: List<ImportedTrack>,
        /** Rows recognised as history but unusable - no title, no artist, below the threshold. */
        val skipped: Int,
        /** Files inside the archive that were read. Shown so a wrong pick is obvious. */
        val filesRead: List<String>,
    )

    sealed interface Outcome {
        data class Success(val parsed: Parsed) : Outcome
        /** The file opened but held nothing any parser recognised. */
        data object Unrecognised : Outcome
        data class Unreadable(val message: String) : Outcome
    }

    /**
     * Reads [uri], which may be a single exported file or the whole archive as a zip.
     *
     * The zip case is the one that matters: every one of these services delivers a zip, and asking
     * a user to find `Streaming_History_Audio_2023_7.json` several folders down - when there are
     * eleven of them and all are needed - is asking them to get it wrong.
     */
    fun read(context: Context, uri: Uri, expected: PlayCountSource): Outcome {
        val name = displayName(context, uri).orEmpty()
        return try {
            if (name.endsWith(".zip", ignoreCase = true) || looksLikeZip(context, uri)) {
                readZip(context, uri, expected)
            } else {
                context.contentResolver.openInputStream(uri).use { stream ->
                    if (stream == null) return Outcome.Unreadable("Could not open the file")
                    readSingle(stream, name, expected)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read archive", e)
            Outcome.Unreadable(e.message ?: "Could not read the file")
        }
    }

    private fun readSingle(stream: InputStream, name: String, expected: PlayCountSource): Outcome {
        val accumulator = Accumulator()
        val handled = parseInto(stream.buffered(), name, expected, accumulator)
        if (!handled) return Outcome.Unrecognised
        return finish(expected, accumulator, listOf(name))
    }

    /**
     * Walks the zip, feeding every member a parser recognises.
     *
     * Streamed rather than extracted, and streamed rather than read: unpacking to disk would need
     * space the phone may not have for data thrown away immediately, and reading an entry whole
     * would need the heap to hold a file that can be larger than the heap. Each entry is handed to
     * its parser as the stream it already is.
     */
    private fun readZip(context: Context, uri: Uri, expected: PlayCountSource): Outcome {
        val accumulator = Accumulator()
        val filesRead = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val entryName = entry.name.substringAfterLast('/')
                    if (!isInteresting(entryName, expected)) continue
                    // Shielded: a parser closing its reader would otherwise close the whole
                    // archive and take every remaining entry with it.
                    val shielded = ShieldedInputStream(zip)
                    if (parseInto(shielded, entryName, expected, accumulator)) filesRead += entryName
                }
            }
        } ?: return Outcome.Unreadable("Could not open the archive")

        if (filesRead.isEmpty()) return Outcome.Unrecognised
        return finish(expected, accumulator, filesRead)
    }

    /** Cheap name filter, so a Takeout full of photos is not decoded looking for scrobbles. */
    private fun isInteresting(name: String, expected: PlayCountSource): Boolean {
        val lower = name.lowercase()
        return when (expected) {
            PlayCountSource.SPOTIFY ->
                lower.endsWith(".json") && lower.contains("streaminghistory") ||
                    lower.endsWith(".json") && lower.contains("streaming_history")
            PlayCountSource.APPLE_MUSIC ->
                lower.endsWith(".csv") && (lower.contains("play activity") ||
                    lower.contains("play_activity") || lower.contains("playactivity"))
            PlayCountSource.YOUTUBE_MUSIC ->
                lower.endsWith(".json") && lower.contains("watch-history") ||
                    lower.endsWith(".json") && lower.contains("watch_history")
            PlayCountSource.LAST_FM -> false
        }
    }

    private fun parseInto(
        stream: InputStream,
        name: String,
        expected: PlayCountSource,
        into: Accumulator,
    ): Boolean = when (expected) {
        PlayCountSource.SPOTIFY -> parseSpotify(stream, into)
        PlayCountSource.YOUTUBE_MUSIC -> parseYouTube(stream, into)
        PlayCountSource.APPLE_MUSIC -> parseAppleCsv(stream, into)
        PlayCountSource.LAST_FM -> false
    }.also { if (!it) Log.d(TAG, "Nothing recognised in $name") }

    // ---------------------------------------------------------------- Spotify

    /**
     * Both vintages of Spotify's export.
     *
     * The "extended streaming history" uses `ts`/`ms_played` with the track under
     * `master_metadata_*`; the older account-data download uses `endTime`/`msPlayed` with plain
     * `artistName`/`trackName`. Users have both, often in the same folder, and neither is labelled.
     */
    private fun parseSpotify(stream: InputStream, into: Accumulator): Boolean =
        streamJsonArray(stream) { reader ->
            var msPlayed: Long? = null
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var timestamp: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "ms_played", "msPlayed" -> msPlayed = reader.nextLongOrNull()
                    "master_metadata_track_name", "trackName" -> title = reader.nextStringOrNull()
                    "master_metadata_album_artist_name", "artistName" ->
                        artist = reader.nextStringOrNull()
                    "master_metadata_album_album_name" -> album = reader.nextStringOrNull()
                    "ts", "endTime" -> timestamp = reader.nextStringOrNull()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            into.accept(spotifyRow(msPlayed, title, artist, album, timestamp))
        }

    // ---------------------------------------------------------------- YouTube

    /**
     * Google Takeout's watch history, narrowed to YouTube Music.
     *
     * Takeout puts YouTube and YouTube Music in one file and distinguishes them only by a `header`
     * field, so this is a music import only if that is honoured. Titles arrive as "Watched <name>"
     * and the artist sits in `subtitles`, usually as the auto-generated "<artist> - Topic" channel.
     */
    private fun parseYouTube(stream: InputStream, into: Accumulator): Boolean =
        streamJsonArray(stream) { reader ->
            var header: String? = null
            var title: String? = null
            var channel: String? = null
            var time: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "header" -> header = reader.nextStringOrNull()
                    "title" -> title = reader.nextStringOrNull()
                    "time" -> time = reader.nextStringOrNull()
                    "subtitles" -> channel = reader.readFirstSubtitleName()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            into.accept(youTubeRow(header, title, channel, time))
        }

    /** The channel behind a watch entry: the first `subtitles` element's name, if there is one. */
    private fun JsonReader.readFirstSubtitleName(): String? {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return null
        }
        var name: String? = null
        beginArray()
        var first = true
        while (hasNext()) {
            if (!first) {
                skipValue()
                continue
            }
            first = false
            if (peek() != JsonToken.BEGIN_OBJECT) {
                skipValue()
                continue
            }
            beginObject()
            while (hasNext()) {
                if (nextName() == "name") name = nextStringOrNull() else skipValue()
            }
            endObject()
        }
        endArray()
        return name
    }

    // ------------------------------------------------------------ Apple Music

    /**
     * Apple's Play Activity CSV, read a line at a time.
     *
     * This is the biggest file any of these services hands over - a few years of listening runs to
     * hundreds of megabytes - and it is also the one whose shape is least certain, so it is read
     * the way a log is read: one row in, one tally out, nothing kept.
     */
    private fun parseAppleCsv(stream: InputStream, into: Accumulator): Boolean {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val headerLine = reader.readLine() ?: return false
        val columns = AppleColumns(splitCsv(headerLine.removePrefix(BYTE_ORDER_MARK)))
        if (!columns.recognised) return false

        var count = 0
        while (count < MAX_ENTRIES) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            val fields = splitCsv(line)
            if (fields.size <= columns.title) continue
            count++
            into.accept(appleRow(columns, fields))
        }
        return count > 0
    }

    /** Where each field this import needs sits in a particular export's header row. */
    internal class AppleColumns(header: List<String>) {
        private val names = header.map { it.trim().trim('"').lowercase() }

        private fun column(vararg candidates: String): Int =
            candidates.firstNotNullOfOrNull { candidate ->
                names.indexOf(candidate).takeIf { it >= 0 }
            } ?: -1

        // Found by name rather than by position: Apple has renamed and reordered these between
        // exports, and the file is wide enough that a fixed index would silently read the wrong
        // field rather than fail. Several plausible names are accepted for each because which one
        // you get depends on the vintage of the export.
        val title = column("song name", "content name", "track name", "item name")
        val artist = column("artist name", "container artist name", "album artist name")
        val album = column("album name", "container name")

        /**
         * How long the row actually played for.
         *
         * Only the play duration answers that. "Media duration" is the length of the track, so
         * reading it as a threshold would keep every skip of a long song and drop every complete
         * play of a short one - the opposite of what the threshold is for.
         */
        val played = column("play duration milliseconds")
        val time = column("event start timestamp", "event end timestamp", "play date time")

        /** Without a title there is nothing to match on, and this is not a play activity export. */
        val recognised: Boolean get() = title >= 0
    }

    /** A CSV splitter that understands quoting, because song titles contain commas. */
    internal fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(character)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    // ------------------------------------------------------------- row logic

    /**
     * What one row of an export turned out to be.
     *
     * Separated from the readers so the decisions - which field wins, what counts as a play, what
     * a title has to be stripped of - can be stated and tested without a file, a zip or a device.
     */
    internal sealed interface RowOutcome {
        data class Play(
            val artist: String,
            val title: String,
            val album: String?,
            val atSeconds: Long,
        ) : RowOutcome

        /** Recognised as history, but unusable: no title, no artist, or barely played. */
        data object Skip : RowOutcome

        /** Not a play at all - a different kind of row that happens to live in the same file. */
        data object NotHistory : RowOutcome
    }

    internal fun spotifyRow(
        msPlayed: Long?,
        trackName: String?,
        albumArtist: String?,
        albumName: String?,
        timestamp: String?,
    ): RowOutcome {
        // The play duration is what identifies a streaming-history row in either vintage.
        if (msPlayed == null) return RowOutcome.NotHistory
        val title = trackName?.takeIf { it.isNotBlank() } ?: return RowOutcome.Skip
        val artist = albumArtist?.takeIf { it.isNotBlank() } ?: return RowOutcome.Skip
        if (msPlayed < SPOTIFY_MIN_MS) return RowOutcome.Skip
        return RowOutcome.Play(
            artist = artist,
            title = title,
            album = albumName?.takeIf { it.isNotBlank() },
            atSeconds = timestamp.toEpochSecondsOrZero(),
        )
    }

    internal fun youTubeRow(
        header: String?,
        rawTitle: String?,
        channel: String?,
        time: String?,
    ): RowOutcome {
        if (!header.equals("YouTube Music", ignoreCase = true)) return RowOutcome.NotHistory
        // Removed by prefix rather than by locale-specific word, because a non-English Takeout
        // says something else entirely - and a title that keeps the verb matches nothing.
        val title = rawTitle?.removePrefix("Watched ")?.trim().orEmpty()
        if (title.isEmpty() || title.startsWith("https://")) return RowOutcome.Skip
        val artist = channel?.removeSuffix(" - Topic")?.trim().orEmpty()
        if (artist.isEmpty()) return RowOutcome.Skip
        return RowOutcome.Play(artist, title, album = null, atSeconds = time.toEpochSecondsOrZero())
    }

    internal fun appleRow(columns: AppleColumns, fields: List<String>): RowOutcome {
        val title = fields.getOrNull(columns.title)?.trim().orEmpty()
        // Apple has no artist column in some exports at all; those rows are unusable rather than
        // guessable, and saying so is better than matching every "Intro" in the library.
        val artist = columns.artist.takeIf { it >= 0 }
            ?.let { fields.getOrNull(it)?.trim() }
            .orEmpty()
        if (title.isEmpty() || artist.isEmpty()) return RowOutcome.Skip
        if (columns.played >= 0) {
            val played = fields.getOrNull(columns.played)?.trim()?.toLongOrNull()
            // A missing duration is not evidence of a skip: some vintages leave it empty, and
            // dropping those rows would silently discard the whole export.
            if (played != null && played < APPLE_MIN_MS) return RowOutcome.Skip
        }
        val album = columns.album.takeIf { it >= 0 }
            ?.let { fields.getOrNull(it)?.trim() }
            ?.takeIf { it.isNotEmpty() }
        val at = columns.time.takeIf { it >= 0 }
            ?.let { fields.getOrNull(it) }
            .toEpochSecondsOrZero()
        return RowOutcome.Play(artist, title, album, at)
    }

    // ----------------------------------------------------------------- shared

    /**
     * Walks a JSON array of objects, handing each one to [row] and never holding more than one.
     *
     * [JsonReader] is a pull parser, which is the only way these files can be read at all: the
     * document object model for a half-gigabyte watch history does not fit in a phone's heap, and
     * the tally being built out of it is a few thousand entries.
     */
    private fun streamJsonArray(
        stream: InputStream,
        row: (JsonReader) -> Boolean,
    ): Boolean {
        val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
        reader.isLenient = true
        return try {
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> readArrayOfRows(reader, row)
                // Some export variants wrap the list in an object with a single key.
                JsonToken.BEGIN_OBJECT -> {
                    var recognised = false
                    reader.beginObject()
                    while (reader.hasNext()) {
                        reader.nextName()
                        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                            recognised = readArrayOfRows(reader, row)
                            break
                        }
                        reader.skipValue()
                    }
                    recognised
                }
                else -> false
            }
        } catch (e: Exception) {
            // A truncated or malformed file still yields everything read before the damage, which
            // is worth far more to the user than refusing the whole import over its last line.
            Log.w(TAG, "Stopped reading JSON history early", e)
            false
        }
    }

    private fun readArrayOfRows(reader: JsonReader, row: (JsonReader) -> Boolean): Boolean {
        var recognised = false
        var count = 0
        reader.beginArray()
        while (reader.hasNext() && count < MAX_ENTRIES) {
            count++
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            if (row(reader)) recognised = true
        }
        return recognised
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString().takeIf { it.isNotBlank() }
        }

    private fun JsonReader.nextLongOrNull(): Long? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.NUMBER -> nextLong()
        // Some vintages quote the number.
        JsonToken.STRING -> nextString().trim().toLongOrNull()
        else -> {
            skipValue()
            null
        }
    }

    /** Collects plays per track as the walk proceeds, so no file is held in full. */
    private class Accumulator {
        private val tracks = HashMap<String, Entry>()
        var skipped = 0
            private set

        /** @return whether the row was recognised as this source's history at all. */
        fun accept(outcome: RowOutcome): Boolean = when (outcome) {
            is RowOutcome.Play -> {
                add(outcome.artist, outcome.title, outcome.album, outcome.atSeconds)
                true
            }
            RowOutcome.Skip -> {
                skipped++
                true
            }
            RowOutcome.NotHistory -> false
        }

        private fun add(artist: String, title: String, album: String?, atSeconds: Long) {
            val key = TrackKey.exact(artist, title)
            val entry = tracks.getOrPut(key) { Entry(artist, title, album) }
            entry.plays++
            if (atSeconds > entry.lastPlayed) entry.lastPlayed = atSeconds
            if (entry.album == null && album != null) entry.album = album
        }

        fun toTracks(): List<ImportedTrack> = tracks.values.map {
            ImportedTrack(
                artist = it.artist,
                title = it.title,
                album = it.album,
                plays = it.plays,
                lastPlayedSeconds = it.lastPlayed,
            )
        }

        private class Entry(val artist: String, val title: String, var album: String?) {
            var plays = 0
            var lastPlayed = 0L
        }
    }

    private fun finish(
        source: PlayCountSource,
        accumulator: Accumulator,
        filesRead: List<String>,
    ): Outcome {
        val tracks = accumulator.toTracks()
        if (tracks.isEmpty()) return Outcome.Unrecognised
        Log.d(TAG, "Parsed ${tracks.size} tracks for ${source.id} from ${filesRead.size} file(s)")
        return Outcome.Success(
            Parsed(
                source = source,
                tracks = tracks,
                skipped = accumulator.skipped,
                filesRead = filesRead,
            )
        )
    }

    /**
     * ISO-8601 as every one of these writes it, which is not quite the same in any two.
     *
     * Spotify's legacy export omits the zone and the seconds ("2021-03-14 09:26"); the extended one
     * and Takeout are proper instants. A timestamp that cannot be read costs only the accuracy of
     * LastPlayedDate, so it degrades to zero rather than dropping the play.
     */
    internal fun String?.toEpochSecondsOrZero(): Long {
        val trimmed = this?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0L
        runCatching { return Instant.parse(trimmed).epochSecond }
        runCatching { return Instant.parse(trimmed.replace(' ', 'T') + ":00Z").epochSecond }
        runCatching { return Instant.parse(trimmed.replace(' ', 'T') + "Z").epochSecond }
        return try {
            Instant.parse(trimmed.substringBefore(' ') + "T00:00:00Z").epochSecond
        } catch (e: DateTimeParseException) {
            0L
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    /** The picker often reports a generic type, so the magic decides rather than the name. */
    private fun looksLikeZip(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val magic = ByteArray(2)
            stream.read(magic) == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        } == true
    } catch (e: Exception) {
        false
    }

    /** A UTF-8 export often carries one, and it would otherwise be part of the first header name. */
    private const val BYTE_ORDER_MARK = "﻿"

    /** Keeps a parser's reader from closing the archive the entry came out of. */
    private class ShieldedInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun skip(n: Long): Long = delegate.skip(n)
        override fun close() = Unit
    }
}
