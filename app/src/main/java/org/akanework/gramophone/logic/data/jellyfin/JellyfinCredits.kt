package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.toDashedUuid
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.Locale

/**
 * The credits behind a track, read off the Jellyfin server.
 *
 * The build this UI came from sources credits from a commercial catalogue, which this fork
 * deliberately does not do. Everything shown here is what the server already knows about the file:
 * who is credited on it, who released it, and what the audio actually is. The last of those is the
 * part no other screen exposes - the player's badge says "Hi-Res Lossless" but never the numbers
 * behind it.
 */
object JellyfinCredits {

    /** One credited person or organisation. */
    data class Credit(
        val name: String,
        /** What they did - "Composer", "Performer". Null when the server did not say. */
        val role: String?,
        /** Their artist image, when the server has one, for the row thumbnail. */
        val imageUrl: String?,
    )

    data class Section(val title: String, val credits: List<Credit>)

    data class Result(val sections: List<Section>)

    /**
     * Loads the credits for [mediaId], or null when signed out, offline, or the item is a local
     * file rather than a server one.
     */
    suspend fun load(context: Context, mediaId: String?): Result? {
        val api = JellyfinClientHolder.api()
        val userId = JellyfinClientHolder.credentials.userId
        if (api == null || userId == null) {
            Log.w(TAG, "Not signed in; no credits for $mediaId")
            return null
        }
        val remoteId = JellyfinItemResolver.remoteIdForMediaId(context, mediaId)
        if (remoteId == null) {
            Log.w(TAG, "No server id for mediaId $mediaId")
            return null
        }

        val item = runCatching {
            api.libraryApi.getItem(
                itemId = UUID.fromString(remoteId.toDashedUuid()),
                userId = UUID.fromString(userId.toDashedUuid()),
            ).content
        }.getOrElse {
            Log.w(TAG, "Credits for $remoteId failed: $it")
            return null
        }

        return parse(item, api)
    }

    private fun parse(item: BaseItemDto, api: ApiClient): Result {
        val sections = mutableListOf<Section>()

        // People arrive with a Type ("Composer", "Artist") and sometimes a free-text Role. The same
        // person is listed once per type, so "Bon Jovi - AlbumArtist" and "Bon Jovi - Artist" are two
        // entries for what a reader would call one credit; they are folded, keeping every distinct
        // role.
        val people = LinkedHashMap<String, MutableSet<String>>()
        val images = LinkedHashMap<String, String?>()
        item.people.orEmpty().forEach { person ->
                val name = person.name?.takeIf(String::isNotBlank) ?: return@forEach
                val role = person.role?.takeIf(String::isNotBlank) ?: person.type.toString()
                people.getOrPut(name) { linkedSetOf() }.apply {
                    if (role.isNotBlank() && !role.equals("Unknown", ignoreCase = true)) {
                        add(role.humanised())
                    }
                }
                images.getOrPut(name) {
                    person.primaryImageTag?.let { tag ->
                        api.imageApi.getItemImageUrl(
                            itemId = person.id,
                            imageType = ImageType.PRIMARY,
                            tag = tag,
                            maxWidth = THUMBNAIL_MAX_WIDTH,
                        )
                    }
                }
        }
        if (people.isNotEmpty()) {
            sections += Section(
                SECTION_PERFORMANCE,
                people.map { (name, roles) ->
                    Credit(name, roles.joinToString(", ").ifEmpty { null }, images[name])
                }
            )
        }

        val studios = item.studios.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) }
        if (studios.isNotEmpty()) {
            sections += Section(SECTION_RELEASE, studios.map { Credit(it, null, null) })
        }

        // The technical row. Built from the first audio stream, which is the one that plays.
        val firstSource = item.mediaSources?.firstOrNull()
        val audioStream = (firstSource?.mediaStreams ?: item.mediaStreams)
            ?.firstOrNull { it.type == MediaStreamType.AUDIO }
        val technical = mutableListOf<Credit>()
        audioStream?.let { stream ->
            stream.codec?.takeIf(String::isNotBlank)?.let {
                technical += Credit(it.uppercase(Locale.ROOT), LABEL_FORMAT, null)
            }
            val sampleRate = stream.sampleRate ?: 0
            val bitDepth = stream.bitDepth ?: 0
            if (sampleRate > 0) {
                val khz = "%.1f".format(sampleRate / 1000f).removeSuffix(".0")
                technical += Credit(
                    if (bitDepth > 0) "$bitDepth-bit / $khz kHz" else "$khz kHz",
                    LABEL_QUALITY,
                    null
                )
            }
            stream.bitRate?.takeIf { it > 0 }?.let {
                technical += Credit("${it / 1000} kbps", LABEL_BITRATE, null)
            }
            stream.channelLayout?.takeIf(String::isNotBlank)?.let {
                technical += Credit(it.humanised(), LABEL_CHANNELS, null)
            }
        }
        (firstSource?.container ?: item.container)?.takeIf(String::isNotBlank)?.let {
            technical += Credit(it.uppercase(Locale.ROOT), LABEL_CONTAINER, null)
        }
        if (technical.isNotEmpty()) sections += Section(SECTION_AUDIO, technical)

        return Result(sections)
    }

    /** "AlbumArtist" reads badly in a list; "Album Artist" is the same fact, spelled for a person. */
    private fun String.humanised(): String =
        replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replaceFirstChar { it.uppercase(Locale.ROOT) }

    private const val TAG = "JellyfinCredits"
    private const val THUMBNAIL_MAX_WIDTH = 128

    const val SECTION_PERFORMANCE = "Performance"
    const val SECTION_RELEASE = "Release"
    const val SECTION_AUDIO = "Audio"
    private const val LABEL_FORMAT = "Format"
    private const val LABEL_QUALITY = "Quality"
    private const val LABEL_BITRATE = "Bit rate"
    private const val LABEL_CHANNELS = "Channels"
    private const val LABEL_CONTAINER = "Container"

}
