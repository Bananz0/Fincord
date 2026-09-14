package org.akanework.gramophone.ui.home

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import uk.akane.accord.ui.adapters.BannerItem
import java.io.File

/**
 * Disk cache for the Home Feed.
 * Persists the rendered [HomeSection] and [HomeCard] state to disk so cold starts display
 * the exact previous session's feed instantly on Frame 1 without any jarring title swaps.
 */
object HomeFeedCache {
    private const val TAG = "HomeFeedCache"
    private const val CACHE_FILE_NAME = "home_feed_cache.json"
    private const val BANNER_CACHE_FILE_NAME = "home_banner_cache.json"

    @Volatile
    private var memorySections: List<HomeSection>? = null
    @Volatile
    private var memoryBanners: List<BannerItem>? = null

    private fun persistentFile(context: Context, name: String) = File(context.filesDir, name)

    /**
     * Keep the last good feed outside cacheDir. Android may purge cacheDir whenever storage is
     * tight, which turned an otherwise ordinary launch back into a full feed rebuild.
     */
    private fun readableFile(context: Context, name: String): File? {
        val persistent = persistentFile(context, name)
        if (persistent.exists()) return persistent
        // One-time migration from builds that treated the home feed as disposable data.
        return File(context.cacheDir, name).takeIf(File::exists)
    }

    fun save(context: Context, sections: List<HomeSection>) {
        if (sections.isEmpty()) return
        memorySections = sections
        try {
            // Most cards in a shelf point at the same 30-50 track mix. Writing that identical ID
            // list into every card grew the cache past a megabyte and made its main-thread cold
            // start parse defeat the purpose of having a cache. Store each distinct list once.
            val mediaSets = linkedMapOf<List<String>, Int>()
            val jsonArray = JSONArray()
            sections.forEach { section ->
                val sectionObj = JSONObject().apply {
                    put("id", section.id)
                    put("title", section.title)
                    put("subtitle", section.subtitle)
                    put("style", section.style.name)

                    val cardsArray = JSONArray()
                    section.cards.forEach { card ->
                        val cardObj = JSONObject().apply {
                            put("title", card.title)
                            put("subtitle", card.subtitle)
                            put("cover", card.cover?.toString())
                            put("startIndex", card.startIndex)
                            put("target", card.target.name)
                            val ids = card.mediaIds
                            if (ids.isNotEmpty()) {
                                val mediaSet = mediaSets.getOrPut(ids) { mediaSets.size }
                                put("mediaSet", mediaSet)
                            }
                            put("collageCovers", card.collageCovers.map(Uri::toString).toJsonArray())
                        }
                        cardsArray.put(cardObj)
                    }
                    put("cards", cardsArray)
                }
                jsonArray.put(sectionObj)
            }

            val mediaSetsArray = JSONArray()
            mediaSets.keys.forEach { mediaSetsArray.put(it.toJsonArray()) }
            val root = JSONObject().apply {
                put("version", 3)
                put("mediaSets", mediaSetsArray)
                put("sections", jsonArray)
            }
            persistentFile(context, CACHE_FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save home feed cache", e)
        }
    }

    fun load(context: Context): List<HomeSection>? {
        memorySections?.let { return it }
        val file = readableFile(context, CACHE_FILE_NAME) ?: return null
        return try {
            val text = file.readText()
            if (text.isBlank()) return null
            val (jsonArray, mediaSets) = if (text.trimStart().startsWith("{")) {
                val root = JSONObject(text)
                if (root.optInt("version", 0) < 3) return null
                val sets = root.optJSONArray("mediaSets")
                val decodedSets = buildList {
                    if (sets != null) {
                        for (index in 0 until sets.length()) {
                            add(sets.optJSONArray(index).toStringList())
                        }
                    }
                }
                root.getJSONArray("sections") to decodedSets
            } else {
                // Version 1 wrote a bare array and duplicated mediaIds inside every card.
                JSONArray(text) to emptyList()
            }
            val sections = mutableListOf<HomeSection>()

            for (i in 0 until jsonArray.length()) {
                val sectionObj = jsonArray.getJSONObject(i)
                val id = sectionObj.getString("id")
                val title = sectionObj.getString("title")
                val subtitle = sectionObj.optString("subtitle").takeIf { it.isNotBlank() && it != "null" }
                val styleName = sectionObj.optString("style", HomeSectionStyle.ROW.name)
                val style = runCatching { HomeSectionStyle.valueOf(styleName) }.getOrDefault(HomeSectionStyle.ROW)

                val cardsArray = sectionObj.getJSONArray("cards")
                val cards = mutableListOf<HomeCard>()

                for (j in 0 until cardsArray.length()) {
                    val cardObj = cardsArray.getJSONObject(j)
                    val cardTitle = cardObj.getString("title")
                    val cardSubtitle = cardObj.optString("subtitle").takeIf { it.isNotBlank() && it != "null" }
                    val coverStr = cardObj.optString("cover").takeIf { it.isNotBlank() && it != "null" }
                    val coverUri = coverStr?.let { Uri.parse(it) }
                    val mediaSet = cardObj.optInt("mediaSet", -1)
                    val mediaIds = if (mediaSet in mediaSets.indices) {
                        mediaSets[mediaSet]
                    } else {
                        cardObj.optJSONArray("mediaIds").toStringList()
                    }
                    val collageCovers = cardObj.optJSONArray("collageCovers")
                        .toStringList()
                        .map(Uri::parse)

                    cards.add(
                        HomeCard(
                            title = cardTitle,
                            subtitle = cardSubtitle,
                            cover = coverUri,
                            collageCovers = collageCovers,
                            songs = emptyList(),
                            startIndex = cardObj.optInt("startIndex", 0),
                            cachedMediaIds = mediaIds,
                            target = runCatching {
                                HomeCardTarget.valueOf(
                                    cardObj.optString("target", HomeCardTarget.MIX.name)
                                )
                            }.getOrDefault(HomeCardTarget.MIX),
                        )
                    )
                }

                if (cards.isNotEmpty()) {
                    sections.add(
                        HomeSection(
                            id = id,
                            title = title,
                            subtitle = subtitle,
                            cards = cards,
                            style = style
                        )
                    )
                }
            }

            sections.ifEmpty { null }?.also { memorySections = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load home feed cache", e)
            null
        }
    }

    fun saveBanners(context: Context, banners: List<BannerItem>) {
        if (banners.isEmpty()) return
        memoryBanners = banners
        try {
            val array = JSONArray()
            banners.forEach { banner ->
                array.put(JSONObject().apply {
                    put("id", banner.id)
                    put("title", banner.title)
                    put("subtitle", banner.subtitle)
                    put("artistsSummary", banner.artistsSummary)
                    put("cover", banner.cover?.toString())
                    put("mediaIds", banner.mediaIds.toJsonArray())
                })
            }
            val root = JSONObject().apply {
                put("version", 2)
                put("banners", array)
            }
            persistentFile(context, BANNER_CACHE_FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save home banner cache", e)
        }
    }

    fun loadBanners(context: Context): List<BannerItem>? {
        memoryBanners?.let { return it }
        val file = readableFile(context, BANNER_CACHE_FILE_NAME) ?: return null
        return try {
            val cachedText = file.readText()
            // Version 1 stored the banner list as a bare array. Treat it as a cache miss
            // without logging a parse error; the next successful refresh replaces it.
            if (cachedText.trimStart().startsWith("[")) return null
            val root = JSONObject(cachedText)
            if (root.optInt("version", 0) < 2) return null
            val array = root.getJSONArray("banners")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.optString("id")
                    val title = item.optString("title")
                    if (id.isBlank() || title.isBlank()) continue
                    add(BannerItem(
                        id = id,
                        title = title,
                        subtitle = item.nullableString("subtitle"),
                        artistsSummary = item.nullableString("artistsSummary"),
                        cover = item.nullableString("cover")?.let(Uri::parse),
                        songs = emptyList(),
                        cachedMediaIds = item.optJSONArray("mediaIds").toStringList()
                    ))
                }
            }.ifEmpty { null }?.also { memoryBanners = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load home banner cache", e)
            null
        }
    }

    private fun Iterable<String>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach(array::put)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun JSONObject.nullableString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
}
