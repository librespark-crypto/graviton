package com.graviton.feature.music.lyrics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Editable representation of a lyrics document.
 *
 * The editor works on a list of structured rows, not on a blob of text, so a timestamp can be
 * changed without reparsing the whole file and word timings survive an unrelated text edit.
 */
@Stable
class LyricsEditorState(document: LyricsDocument) {
    val lines = mutableStateListOf<EditableLine>().apply {
        addAll(document.lines.map(EditableLine::from))
    }

    var offsetMs: Long by mutableLongStateOf(document.offsetMs)
        private set

    var isDirty: Boolean by mutableStateOf(false)
        private set

    private val metadata = document.metadata
    private val unsynced = document.unsynced
    private var originalFormat = document.format

    /** Index whose fields are currently expanded in the UI, or -1. */
    var expandedIndex: Int by mutableStateOf(-1)

    fun updateText(index: Int, text: String) = mutate {
        lines[index] = lines[index].copy(text = text)
    }

    fun updateTranslation(index: Int, translation: String) = mutate {
        lines[index] = lines[index].copy(translation = translation)
    }

    /** Returns false when [text] is not a valid timestamp, so the UI can show an error. */
    fun updateTimestamp(index: Int, text: String): Boolean {
        val parsed = LrcWriter.parseTimestamp(text) ?: return false
        mutate { lines[index] = lines[index].copy(timeMs = parsed) }
        return true
    }

    /** Stamps [index] with the live playback position — the editor's primary interaction. */
    fun setTimestampFromPosition(index: Int, positionMs: Long) = mutate {
        lines[index] = lines[index].copy(timeMs = positionMs.coerceAtLeast(0L))
    }

    fun addLineAfter(index: Int, positionMs: Long) = mutate {
        val insertAt = (index + 1).coerceIn(0, lines.size)
        lines.add(insertAt, EditableLine(timeMs = positionMs.coerceAtLeast(0L), text = ""))
        expandedIndex = insertAt
    }

    fun deleteLine(index: Int) = mutate {
        if (index in lines.indices) lines.removeAt(index)
        if (expandedIndex >= lines.size) expandedIndex = -1
    }

    fun moveLine(from: Int, to: Int) = mutate {
        if (from !in lines.indices || to !in lines.indices) return@mutate
        lines.add(to, lines.removeAt(from))
        expandedIndex = to
    }

    /** Shifts every timestamp, including word timings, by [deltaMs]. */
    fun shiftAll(deltaMs: Long) = mutate {
        for (index in lines.indices) {
            val line = lines[index]
            lines[index] = line.copy(
                timeMs = (line.timeMs + deltaMs).coerceAtLeast(0L),
                alternateTimesMs = line.alternateTimesMs.map { (it + deltaMs).coerceAtLeast(0L) },
                words = line.words.map {
                    it.copy(
                        startMs = (it.startMs + deltaMs).coerceAtLeast(0L),
                        endMs = (it.endMs + deltaMs).coerceAtLeast(0L),
                    )
                },
            )
        }
    }

    /** Sets the LRC `[offset:]` tag, which players apply at read time. */
    fun setOffset(value: Long) = mutate { offsetMs = value }

    /** Sorts by timestamp; used before saving so the file is monotonic. */
    fun sortByTime() = mutate {
        val sorted = lines.sortedBy { it.timeMs }
        lines.clear()
        lines.addAll(sorted)
    }

    fun toDocument(): LyricsDocument {
        val modelLines = lines
            .filterNot { it.text.isBlank() && it.translation.isNullOrBlank() && it.words.isEmpty() && it.timeMs == 0L }
            .sortedBy { it.timeMs }
            .map(EditableLine::toModel)
        return LyricsDocument(
            lines = modelLines,
            unsynced = if (modelLines.isEmpty()) unsynced else null,
            offsetMs = offsetMs,
            format = if (modelLines.isEmpty()) LyricsFormat.PLAIN else LyricsFormat.LRC,
            origin = LyricsOrigin.USER,
            metadata = metadata,
        )
    }

    fun toLrc(): String = LrcWriter.write(toDocument())

    /** Replaces the whole buffer, e.g. after importing a file. */
    fun load(document: LyricsDocument) = mutate {
        lines.clear()
        lines.addAll(document.lines.map(EditableLine::from))
        offsetMs = document.offsetMs
        originalFormat = document.format
        expandedIndex = -1
    }

    fun markSaved() {
        isDirty = false
    }

    private inline fun mutate(block: () -> Unit) {
        block()
        isDirty = true
    }
}

/** A single editable row. Word timings are carried through untouched unless the text changes. */
@Stable
data class EditableLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
    val alternateTimesMs: List<Long> = emptyList(),
    val id: Long = idFor(),
) {
    val timestamp: String get() = LrcWriter.formatTimestamp(timeMs)

    fun toModel(): LyricLine = LyricLine(
        timeMs = timeMs,
        text = text,
        words = words,
        translation = translation?.takeIf(String::isNotBlank),
        alternateTimesMs = alternateTimesMs,
        id = id,
    )

    companion object {
        private var counter = 1_000_000L

        @Synchronized
        private fun idFor(): Long = ++counter

        fun from(line: LyricLine) = EditableLine(
            timeMs = line.timeMs,
            text = line.text,
            translation = line.translation,
            words = line.words,
            alternateTimesMs = line.alternateTimesMs,
            id = line.id,
        )
    }
}
