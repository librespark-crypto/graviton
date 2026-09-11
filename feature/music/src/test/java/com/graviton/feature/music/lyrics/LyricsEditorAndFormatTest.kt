package com.graviton.feature.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsEditorAndFormatTest {

    @Test fun lrc_recordsFormatAndMetadata() {
        val doc = LyricsParser.parse("[ti:Song]\n[ar:Artist]\n[00:01.00]Line")
        assertEquals(LyricsFormat.LRC, doc.format)
        assertEquals("Song", doc.metadata["ti"])
        assertEquals("Artist", doc.metadata["ar"])
    }

    @Test fun lineAt_returnsMinusOneBeforeFirstLine() {
        val doc = LyricsParser.parse("[00:10.00]Late")
        assertEquals(-1, doc.lineAt(0))
        assertEquals(0, doc.lineAt(10_000))
    }

    @Test fun malformedLrc_doesNotThrowAndKeepsText() {
        val doc = LyricsParser.parse("[99:99:99]broken\n[00:xx]also broken\nplain text")
        assertNotNull(doc)
        assertTrue(doc.unsynced!!.contains("plain text"))
    }

    @Test fun writer_roundTripsTimestampsWordsAndTranslation() {
        val original = LyricsParser.parse(
            "[00:01.00]<00:01.00>Hello <00:01.50>world\n[00:05.20]Second",
        )
        val reparsed = LyricsParser.parse(LrcWriter.write(original))
        assertEquals(original.lines.map { it.timeMs }, reparsed.lines.map { it.timeMs })
        assertTrue(reparsed.hasWordTiming)
    }

    @Test fun writer_preservesOffsetTag() {
        val doc = LyricsParser.parse("[offset:-300]\n[00:02.00]Line")
        val text = LrcWriter.write(doc)
        assertTrue(text.contains("[offset:-300]"))
    }

    @Test fun timestampParser_acceptsCommonFormsAndRejectsGarbage() {
        assertEquals(61_500L, LrcWriter.parseTimestamp("01:01.50"))
        assertEquals(61_000L, LrcWriter.parseTimestamp("01:01"))
        assertEquals(3_661_000L, LrcWriter.parseTimestamp("01:01:01"))
        assertNull(LrcWriter.parseTimestamp("nope"))
        assertNull(LrcWriter.parseTimestamp("1:99"))
    }

    @Test fun timestampFormatter_isStable() {
        assertEquals("00:00.00", LrcWriter.formatTimestamp(0))
        assertEquals("01:05.25", LrcWriter.formatTimestamp(65_250))
        assertEquals("00:00.00", LrcWriter.formatTimestamp(-500))
    }

    @Test fun editor_setsTimestampFromPlaybackPosition() {
        val state = LyricsEditorState(LyricsParser.parse("[00:01.00]One\n[00:02.00]Two"))
        state.setTimestampFromPosition(1, 9_400L)
        assertEquals(9_400L, state.lines[1].timeMs)
        assertTrue(state.isDirty)
    }

    @Test fun editor_addsDeletesAndReordersLines() {
        val state = LyricsEditorState(LyricsParser.parse("[00:01.00]One\n[00:02.00]Two"))
        state.addLineAfter(0, 1_500L)
        assertEquals(3, state.lines.size)
        assertEquals(1_500L, state.lines[1].timeMs)

        state.moveLine(2, 0)
        assertEquals("Two", state.lines[0].text)

        state.deleteLine(0)
        assertEquals(2, state.lines.size)
    }

    @Test fun editor_shiftAllMovesLineAndWordTimings() {
        val state = LyricsEditorState(LyricsParser.parse("[00:01.00]<00:01.00>Hi"))
        state.shiftAll(500L)
        assertEquals(1_500L, state.lines[0].timeMs)
        assertEquals(1_500L, state.lines[0].words.first().startMs)
    }

    @Test fun editor_shiftNeverProducesNegativeTimestamps() {
        val state = LyricsEditorState(LyricsParser.parse("[00:00.10]Early"))
        state.shiftAll(-5_000L)
        assertEquals(0L, state.lines[0].timeMs)
    }

    @Test fun editor_rejectsInvalidTimestampInput() {
        val state = LyricsEditorState(LyricsParser.parse("[00:01.00]One"))
        assertFalse(state.updateTimestamp(0, "garbage"))
        assertTrue(state.updateTimestamp(0, "00:04.00"))
        assertEquals(4_000L, state.lines[0].timeMs)
    }

    @Test fun editor_exportsSortedLrcThatReparses() {
        val state = LyricsEditorState(LyricsDocument.Empty)
        state.addLineAfter(-1, 5_000L)
        state.updateText(0, "Later")
        state.addLineAfter(0, 1_000L)
        state.updateText(1, "Earlier")

        val reparsed = LyricsParser.parse(state.toLrc())
        assertEquals(listOf("Earlier", "Later"), reparsed.lines.map { it.text })
    }

    @Test fun wordProgress_neverDividesByZero() {
        val instant = LyricWord("x", 1_000L, 1_000L)
        assertEquals(0f, instant.progressAt(999L))
        assertEquals(1f, instant.progressAt(1_000L))
    }

    @Test fun ttml_supportsDurAttributeAndWhitespacePreservation() {
        val raw = """<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:xml="http://www.w3.org/XML/1998/namespace"><body><div>
            <p begin="1s" dur="2s">Spaced   out</p></div></body></tt>"""
        val doc = LyricsParser.parse(raw)
        assertEquals(1_000L, doc.lines.single().timeMs)
        assertEquals(3_000L, doc.lines.single().endMs)
        assertEquals(LyricsFormat.TTML, doc.format)
    }

    @Test fun ttml_withoutWordTimingFallsBackToLineSync() {
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:01.000" end="00:00:02.000">Plain line</p></div></body></tt>"""
        val doc = LyricsParser.parse(raw)
        assertTrue(doc.isSynced)
        assertFalse(doc.hasWordTiming)
        assertEquals("Plain line", doc.lines.single().text)
    }

    @Test fun ttml_offsetTimeUnitsAreUnderstood() {
        assertEquals(1_500L, LyricsParser.parseTtmlTime("1.5s"))
        assertEquals(250L, LyricsParser.parseTtmlTime("250ms"))
        assertEquals(90_000L, LyricsParser.parseTtmlTime("1.5m"))
        assertNull(LyricsParser.parseTtmlTime("bogus"))
    }

    @Test fun malformedTtml_returnsReadableTextInsteadOfCrashing() {
        val doc = LyricsParser.parse("<tt><body><p begin='1s'>unclosed text")
        assertFalse(doc.isSynced)
        assertNotNull(doc.unsynced)
    }
}
