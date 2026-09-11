package com.graviton.feature.music.lyrics

import androidx.compose.runtime.Immutable

/** Which syntax the lyrics were parsed from. Kept on the document so the editor can round-trip. */
enum class LyricsFormat {
    /** Plain, unsynchronised text. */
    PLAIN,

    /** Standard or enhanced (word-timed) LRC. */
    LRC,

    /** Timed Text Markup Language, including Apple-style word-timed TTML. */
    TTML,
}

/** Where the lyrics came from. Surfaced in the UI so the user knows what they are editing. */
enum class LyricsOrigin {
    NONE,
    EMBEDDED,
    SIDECAR,
    REMOTE,
    USER,
    CACHE,
}

/**
 * A timed word.
 *
 * [endMs] may equal [startMs] when the source only supplies a start time; the viewer treats a
 * zero-length word as "instant", never as a divide-by-zero.
 */
@Immutable
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** 0f before the word, 1f after it, linear in between. Never fabricates timing. */
    fun progressAt(positionMs: Long): Float = when {
        durationMs == 0L -> if (positionMs >= startMs) 1f else 0f
        positionMs <= startMs -> 0f
        positionMs >= endMs -> 1f
        else -> (positionMs - startMs).toFloat() / durationMs
    }
}

/**
 * One line of lyrics.
 *
 * [id] is a stable identity for Compose list keys. Line index is deliberately not used as a key:
 * the editor inserts, deletes and reorders lines, and an index key would make Compose reuse the
 * wrong row's state.
 */
@Immutable
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    /** BCP-47 tag of [translation] when the source declared one. Never assumed. */
    val translationLanguage: String? = null,
    val voice: String? = null,
    val isBackground: Boolean = false,
    /** Additional timestamps the same text was declared at, preserved for LRC round-tripping. */
    val alternateTimesMs: List<Long> = emptyList(),
    val id: Long = nextId(),
) {
    val hasWordTiming: Boolean get() = words.isNotEmpty()
    val isBlank: Boolean get() = text.isBlank() && translation.isNullOrBlank()

    /** The word active at [positionMs], or `null` when there is no word-level timing. */
    fun activeWordIndex(positionMs: Long): Int {
        if (words.isEmpty()) return -1
        val index = words.indexOfLast { positionMs >= it.startMs }
        return index
    }

    companion object {
        private var counter = 0L

        @Synchronized
        private fun nextId(): Long = ++counter
    }
}

/**
 * A parsed lyrics document.
 *
 * The UI consumes this, never a raw string. Everything the viewer and editor need — timings, word
 * timings, translations, offset and provenance — is represented explicitly.
 */
@Immutable
data class LyricsDocument(
    val lines: List<LyricLine>,
    val unsynced: String?,
    val offsetMs: Long = 0L,
    val source: String? = null,
    val instrumental: Boolean = false,
    val format: LyricsFormat = LyricsFormat.PLAIN,
    val origin: LyricsOrigin = LyricsOrigin.NONE,
    /** Metadata tags recovered from the source (`ti`, `ar`, `al`, `by`, …). */
    val metadata: Map<String, String> = emptyMap(),
) {
    val isSynced: Boolean get() = lines.isNotEmpty()
    val hasWordTiming: Boolean get() = lines.any { it.hasWordTiming }
    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrBlank() }
    val isEmpty: Boolean get() = lines.isEmpty() && unsynced.isNullOrBlank()

    /**
     * Index of the line active at [positionMs], or -1 before the first line.
     *
     * Binary search, because this runs on every playback tick.
     */
    fun lineAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines.first().timeMs) return -1
        val found = lines.binarySearchBy(positionMs) { it.timeMs }
        return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }

    companion object {
        val Empty = LyricsDocument(emptyList(), null)
    }
}
