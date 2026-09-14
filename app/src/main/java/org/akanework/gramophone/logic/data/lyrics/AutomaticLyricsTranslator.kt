package org.akanework.gramophone.logic.data.lyrics

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device lyric translation using the same ML Kit components as the supplied preview APK. */
object AutomaticLyricsTranslator {

    /**
     * Preserves timestamps, word timing, speakers and translations supplied by Jellyfin/LRC.
     * ML Kit is used only to fill blank [MediaStoreUtils.Lyric.translationContent] fields.
     */
    suspend fun translateMissing(
        lyrics: List<MediaStoreUtils.Lyric>,
        targetLanguageTag: String = Locale.getDefault().toLanguageTag(),
    ): List<MediaStoreUtils.Lyric> {
        val candidates = lyrics.filter {
            it.content.isNotBlank() && it.translationContent.isBlank()
        }
        if (candidates.isEmpty()) return lyrics

        val target = TranslateLanguage.fromLanguageTag(targetLanguageTag)
            ?: TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
            ?: return lyrics
        val sample = candidates.asSequence()
            .map { it.content.trim() }
            .joinToString("\n")
            .take(LANGUAGE_SAMPLE_CHARS)
        if (sample.isBlank()) return lyrics

        val identifier = LanguageIdentification.getClient()
        val detected = try {
            identifier.identifyLanguage(sample).await()
        } finally {
            identifier.close()
        }
        if (detected == UNDETERMINED_LANGUAGE) return lyrics
        val source = TranslateLanguage.fromLanguageTag(detected) ?: return lyrics
        if (source == target) return lyrics

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )
        return try {
            translator.downloadModelIfNeeded().await()
            val cache = HashMap<String, String>()
            lyrics.map { lyric ->
                if (lyric.content.isBlank() || lyric.translationContent.isNotBlank()) {
                    lyric
                } else {
                    val sourceText = lyric.content.trim()
                    val translated = cache[sourceText]
                        ?: translator.translate(sourceText).await().also { cache[sourceText] = it }
                    lyric.copy(translationContent = translated.trim())
                }
            }
        } catch (failure: Exception) {
            // A missing model or offline phone must never prevent the original lyrics appearing.
            Log.w(TAG, "Automatic lyric translation was unavailable", failure)
            lyrics
        } finally {
            translator.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { failure ->
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
        addOnCanceledListener { continuation.cancel() }
    }

    private const val UNDETERMINED_LANGUAGE = "und"
    private const val LANGUAGE_SAMPLE_CHARS = 1_500
    private const val TAG = "LyricsTranslator"
}
