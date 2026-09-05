package com.graviton.feature.music.lyrics

import java.io.StringReader
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Clean-room LRC/TTML parser.
 *
 * Every entry point is total: malformed input degrades to unsynchronised text or to an empty
 * document, and never throws. That is a hard requirement — lyrics come from user files and from
 * the network, and a bad file must not be able to take the player down.
 */
object LyricsParser {
    private val lrcTimestamp = Regex("""\[(\d{1,4}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")
    private val enhancedTimestamp = Regex("""<(\d{1,4}):([0-5]?\d)(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]\s*$""", RegexOption.IGNORE_CASE)
    private val metadataTag = Regex("""^\s*\[([a-zA-Z_]+):(.*)]\s*$""")

    fun parse(raw: String?): LyricsDocument {
        if (raw.isNullOrBlank()) return LyricsDocument.Empty
        val trimmed = raw.trimStart()
        return if (trimmed.startsWith("<") && trimmed.contains("<tt", ignoreCase = true)) {
            parseTtml(raw)
        } else {
            parseLrc(raw)
        }
    }

    /**
     * Parses standard and enhanced LRC.
     *
     * Handles: multiple timestamps per line, `[offset:]`, metadata tags, blank timed lines
     * (instrumental gaps, which are meaningful for scrolling), word-level `<mm:ss.xx>` timings,
     * and the widespread convention of a duplicate timestamp meaning "translation of the line
     * above". Unparseable lines are collected as plain text rather than discarded.
     */
    fun parseLrc(raw: String): LyricsDocument = runCatching {
        var offset = 0L
        val metadata = mutableMapOf<String, String>()
        val parsed = mutableListOf<LyricLine>()
        val unsynced = mutableListOf<String>()

        raw.lineSequence().forEach { sourceLine ->
            offsetTag.matchEntire(sourceLine)?.let { match ->
                offset = match.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }
            val stamps = lrcTimestamp.findAll(sourceLine).toList()
            if (stamps.isEmpty()) {
                val meta = metadataTag.matchEntire(sourceLine)
                if (meta != null) {
                    metadata[meta.groupValues[1].lowercase()] = meta.groupValues[2].trim()
                } else if (sourceLine.isNotBlank()) {
                    unsynced += sourceLine
                }
                return@forEach
            }
            val content = lrcTimestamp.replace(sourceLine, "").trim()
            val words = parseEnhancedWords(content)
            val plainText = if (words.isEmpty()) content else words.joinToString("") { it.text }.trim()

            stamps.forEach { stamp ->
                val lineStart = (stamp.toMillis() + offset).coerceAtLeast(0L)
                val shiftedWords = words.map { word ->
                    word.copy(
                        startMs = (word.startMs + offset).coerceAtLeast(0L),
                        endMs = (word.endMs + offset).coerceAtLeast(0L),
                    )
                }
                parsed += LyricLine(timeMs = lineStart, text = plainText, words = shiftedWords)
            }
        }

        val merged = mergeTranslations(parsed.sortedBy { it.timeMs })
        val bounded = withLineEnds(merged)

        LyricsDocument(
            lines = bounded,
            unsynced = unsynced.joinToString("\n").takeIf(String::isNotBlank),
            offsetMs = offset,
            format = if (bounded.isEmpty()) LyricsFormat.PLAIN else LyricsFormat.LRC,
            metadata = metadata,
        )
    }.getOrElse { LyricsDocument(emptyList(), raw.takeIf(String::isNotBlank), format = LyricsFormat.PLAIN) }

    /**
     * Parses TTML properly rather than stripping tags.
     *
     * Supported: timed `<p>` paragraphs, timed `<span>` children (word-level karaoke), nested
     * spans whose timing is inherited from the parent when absent, `dur` as an alternative to
     * `end`, translation spans identified by `ttm:role`/`xml:lang`, background vocals, and
     * `xml:space="preserve"`. Constructs Graviton has no UI for (styling regions, animation) are
     * ignored rather than treated as an error.
     *
     * The document builder is configured to reject DOCTYPE and external entities: lyrics files are
     * untrusted input and must not be able to reach the filesystem or the network.
     */
    fun parseTtml(raw: String): LyricsDocument = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
        val paragraphs = document.getElementsByTagNameNS("*", "p")

        val lines = buildList {
            for (index in 0 until paragraphs.length) {
                val element = paragraphs.item(index) as? Element ?: continue
                val start = parseTtmlTime(element.attributeAny("begin")) ?: continue
                val end = parseTtmlTime(element.attributeAny("end"))
                    ?: parseTtmlTime(element.attributeAny("dur"))?.let { start + it }

                val preserveSpace = element.inheritedAttribute("space") == "preserve"
                val translationSpans = mutableListOf<Element>()
                val words = mutableListOf<LyricWord>()
                val mainText = StringBuilder()

                collectContent(
                    node = element,
                    parentStart = start,
                    parentEnd = end,
                    preserveSpace = preserveSpace,
                    words = words,
                    text = mainText,
                    translations = translationSpans,
                )

                val translationElement = translationSpans.firstOrNull()
                val text = mainText.toString().let { if (preserveSpace) it else it.trim() }
                val role = element.attributeAny("role")

                add(
                    LyricLine(
                        timeMs = start,
                        text = if (words.isNotEmpty() && text.isBlank()) {
                            words.joinToString("") { it.text }.trim()
                        } else {
                            text
                        },
                        endMs = end,
                        words = words.toList(),
                        translation = translationElement?.textContent?.trim()?.takeIf(String::isNotBlank),
                        translationLanguage = translationElement?.inheritedAttribute("lang")?.takeIf(String::isNotBlank),
                        voice = element.attributeAny("agent").ifBlank { element.attributeAny("voice") }.takeIf(String::isNotBlank),
                        isBackground = role.contains("background", ignoreCase = true),
                    ),
                )
            }
        }.sortedBy { it.timeMs }

        if (lines.isEmpty()) {
            // A TTML file with no timed paragraphs is still readable as plain text.
            val text = document.documentElement?.textContent?.trim()
            LyricsDocument(emptyList(), text?.takeIf(String::isNotBlank), format = LyricsFormat.PLAIN)
        } else {
            LyricsDocument(withLineEnds(lines), null, format = LyricsFormat.TTML)
        }
    }.getOrElse {
        // Malformed XML: fall back to whatever readable text is in the file. Never rethrow.
        LyricsDocument(emptyList(), stripMarkup(raw).takeIf(String::isNotBlank), format = LyricsFormat.PLAIN)
    }

    /**
     * Walks a paragraph's children, gathering word timings and text.
     *
     * Spans without their own `begin` inherit the enclosing timing, which is how nested TTML
     * expresses "this fragment belongs to the parent's window".
     */
    private fun collectContent(
        node: Node,
        parentStart: Long,
        parentEnd: Long?,
        preserveSpace: Boolean,
        words: MutableList<LyricWord>,
        text: StringBuilder,
        translations: MutableList<Element>,
    ) {
        for (index in 0 until node.childNodes.length) {
            when (val child = node.childNodes.item(index)) {
                is Element -> {
                    if (child.isTranslation()) {
                        translations += child
                        continue
                    }
                    val start = parseTtmlTime(child.attributeAny("begin")) ?: parentStart
                    val end = parseTtmlTime(child.attributeAny("end"))
                        ?: parseTtmlTime(child.attributeAny("dur"))?.let { start + it }
                        ?: parentEnd
                    val hasOwnTiming = child.attributeAny("begin").isNotBlank()
                    val hasElementChildren = (0 until child.childNodes.length)
                        .any { child.childNodes.item(it) is Element }

                    if (hasElementChildren) {
                        collectContent(child, start, end, preserveSpace, words, text, translations)
                    } else {
                        val content = child.textContent.orEmpty()
                        if (content.isNotEmpty()) {
                            text.append(content)
                            // Only emit a word when the source actually timed it: word timings are
                            // never fabricated from a line's duration.
                            if (hasOwnTiming) words += LyricWord(content, start, end ?: start)
                        }
                    }
                }
                else -> {
                    val content = child.nodeValue.orEmpty()
                    if (content.isNotEmpty()) {
                        text.append(if (preserveSpace) content else content.replace(Regex("\\s+"), " "))
                        // Whitespace between timed spans belongs to the previous word so the
                        // karaoke highlight does not visibly skip the gaps.
                        if (words.isNotEmpty() && content.isNotBlank()) {
                            val last = words.removeAt(words.lastIndex)
                            words += last.copy(text = last.text + content)
                        }
                    }
                }
            }
        }
    }

    private fun Element.isTranslation(): Boolean {
        val role = attributeAny("role")
        if (role.contains("translation", ignoreCase = true)) return true
        if (attributeAny("type").contains("translation", ignoreCase = true)) return true
        // A span carrying its own xml:lang inside a line is, by convention, a translation.
        return localName == "span" && attributeAny("lang").isNotBlank()
    }

    private fun parseEnhancedWords(content: String): List<LyricWord> {
        val matches = enhancedTimestamp.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexedNotNull { index, match ->
            val start = match.toMillis()
            val textStart = match.range.last + 1
            val next = matches.getOrNull(index + 1)
            val textEnd = next?.range?.first ?: content.length
            if (textStart > textEnd) return@mapIndexedNotNull null
            val text = content.substring(textStart, textEnd)
            if (text.isEmpty()) null else LyricWord(text, start, next?.toMillis() ?: start)
        }
    }

    /** Consecutive lines at the same timestamp are the original + its translation. */
    private fun mergeTranslations(sorted: List<LyricLine>): List<LyricLine> {
        val merged = mutableListOf<LyricLine>()
        sorted.forEach { line ->
            val previous = merged.lastOrNull()
            val isTranslationOfPrevious = previous != null &&
                previous.timeMs == line.timeMs &&
                previous.text != line.text &&
                previous.text.isNotBlank() &&
                line.text.isNotBlank() &&
                previous.translation == null
            if (isTranslationOfPrevious) {
                merged[merged.lastIndex] = previous!!.copy(translation = line.text)
            } else {
                merged += line
            }
        }
        return merged
    }

    /** Gives every line an end time, defaulting to the start of the next line. */
    private fun withLineEnds(lines: List<LyricLine>): List<LyricLine> = lines.mapIndexed { index, line ->
        line.copy(endMs = line.endMs ?: lines.getOrNull(index + 1)?.timeMs)
    }

    private fun MatchResult.toMillis(): Long {
        val fraction = groupValues[3]
        val milliseconds = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return groupValues[1].toLong() * 60_000L + groupValues[2].toLong() * 1_000L + milliseconds
    }

    /** Accepts clock time, offset time (`12.5s`, `900ms`, `3f`) and frame-less hh:mm:ss.ms. */
    internal fun parseTtmlTime(value: String): Long? {
        val text = value.trim()
        if (text.isBlank()) return null
        when {
            text.endsWith("ms") -> return text.dropLast(2).toDoubleOrNull()?.toLong()
            text.endsWith("s") -> return text.dropLast(1).toDoubleOrNull()?.times(1000)?.toLong()
            text.endsWith("m") -> return text.dropLast(1).toDoubleOrNull()?.times(60_000)?.toLong()
            text.endsWith("h") -> return text.dropLast(1).toDoubleOrNull()?.times(3_600_000)?.toLong()
        }
        val parts = text.split(':')
        if (parts.size !in 2..4) return null
        // A 4-part value is hh:mm:ss:frames; frames are dropped rather than guessed at a rate.
        val clock = if (parts.size == 4) parts.dropLast(1) else parts
        val seconds = clock.last().toDoubleOrNull() ?: return null
        val minutes = clock.getOrNull(clock.lastIndex - 1)?.toLongOrNull() ?: return null
        val hours = clock.getOrNull(clock.size - 3)?.toLongOrNull() ?: 0L
        return (hours * 3_600_000L + minutes * 60_000L + seconds * 1000).toLong()
    }

    private fun Element.attributeAny(localName: String): String {
        if (hasAttribute(localName)) return getAttribute(localName)
        for (index in 0 until attributes.length) {
            val item = attributes.item(index)
            if (item.localName == localName || item.nodeName.substringAfter(':') == localName) {
                return item.nodeValue.orEmpty()
            }
        }
        return ""
    }

    /** Resolves an attribute that TTML allows to be inherited from an ancestor (`xml:space`, `xml:lang`). */
    private fun Element.inheritedAttribute(localName: String): String {
        var current: Node? = this
        while (current is Element) {
            val value = current.attributeAny(localName)
            if (value.isNotBlank()) return value
            current = current.parentNode
        }
        return ""
    }

    private fun stripMarkup(raw: String): String = raw
        .replace(Regex("<[^>]*>"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .lines()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("\n")
}
