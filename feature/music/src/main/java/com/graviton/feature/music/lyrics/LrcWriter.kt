package com.graviton.feature.music.lyrics

import java.util.Locale

/**
 * Serialises a [LyricsDocument] back to LRC.
 *
 * The editor round-trips through this, so it must preserve everything the parser understood:
 * metadata tags, the offset, alternate timestamps for a repeated line, word-level timings
 * (enhanced LRC), and translations (written as a duplicate timestamp, which is the convention the
 * parser reads back).
 */
object LrcWriter {

    fun write(document: LyricsDocument): String = buildString {
        document.metadata.forEach { (key, value) ->
            if (value.isNotBlank()) appendLine("[$key:$value]")
        }
        if (document.offsetMs != 0L) appendLine("[offset:${document.offsetMs}]")
        if (isNotEmpty()) appendLine()

        document.lines.forEach { line ->
            val body = if (line.hasWordTiming) {
                line.words.joinToString("") { word -> "<${formatTimestamp(word.startMs)}>${word.text}" }
            } else {
                line.text
            }
            val stamps = (listOf(line.timeMs) + line.alternateTimesMs).distinct().sorted()
            val prefix = stamps.joinToString("") { "[${formatTimestamp(it)}]" }
            appendLine("$prefix$body")
            line.translation?.takeIf(String::isNotBlank)?.let { translation ->
                appendLine("[${formatTimestamp(line.timeMs)}]$translation")
            }
        }

        if (document.lines.isEmpty()) {
            document.unsynced?.let { append(it) }
        }
    }

    /** `mm:ss.xx`, the form virtually every LRC consumer accepts. */
    fun formatTimestamp(timeMs: Long): String {
        val safe = timeMs.coerceAtLeast(0L)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val hundredths = (safe % 1000) / 10
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    /**
     * Parses a user-typed timestamp back to milliseconds.
     *
     * Accepts `mm:ss`, `mm:ss.x`, `mm:ss.xx`, `mm:ss.xxx` and `hh:mm:ss.xx`. Returns `null` for
     * anything else so the editor can show a validation error instead of silently writing 0.
     */
    fun parseTimestamp(text: String): Long? {
        val match = Regex("""^\s*(?:(\d{1,2}):)?(\d{1,4}):([0-5]?\d)(?:[.:](\d{1,3}))?\s*$""")
            .matchEntire(text) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val fraction = match.groupValues[4]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }
}
