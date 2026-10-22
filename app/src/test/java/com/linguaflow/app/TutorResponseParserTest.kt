package com.linguaflow.app

import com.linguaflow.app.engine.llm.TutorResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorResponseParserTest {

    @Test
    fun testCleanTemplateArtifacts() {
        val rawLeaked = """
            <start_of_turn>user
            Current Student Message: oi
            <end_of_turn>
            <start_of_turn>model
            Tutor: <reply>Oi! Que alegria ter você aqui para praticar inglês!</reply><fix>OK</fix><pron>OK</pron>
        """.trimIndent()

        val parsed = TutorResponseParser.parse(rawLeaked)

        assertEquals("Oi! Que alegria ter você aqui para praticar inglês!", parsed.reply)
        assertFalse(parsed.reply.contains("Current Student Message"))
        assertFalse(parsed.reply.contains("<start_of_turn>"))
        assertFalse(parsed.reply.contains("Tutor:"))
    }

    @Test
    fun testUntaggedTemplateLeakCleaned() {
        val rawUntagged = "Tutor: Olá! Como posso te ajudar hoje a praticar inglês?"
        val parsed = TutorResponseParser.parse(rawUntagged)

        assertEquals("Olá! Como posso te ajudar hoje a praticar inglês?", parsed.reply)
        assertFalse(parsed.reply.startsWith("Tutor:"))
    }

    @Test
    fun testBilingualParsingWithCorrectionsAndPronunciation() {
        val rawXml = """
            <reply>Oi! Suco de fruta é muito comum, nós dizemos fruit juice. Repete comigo: fruit juice!</reply>
            <fix>"Juice fruit" -> "Fruit juice" | Em inglês o modificador vem antes do substantivo.</fix>
            <pron>Em 'juice', o 'j' tem som suave /dʒuːs/.</pron>
        """.trimIndent()

        val parsed = TutorResponseParser.parse(rawXml)

        assertTrue(parsed.reply.contains("fruit juice"))
        assertTrue(parsed.hasCorrections)
        assertEquals("\"Juice fruit\" -> \"Fruit juice\" | Em inglês o modificador vem antes do substantivo.", parsed.corrections)
        assertTrue(parsed.hasPronunciationTip)
        assertEquals("Em 'juice', o 'j' tem som suave /dʒuːs/.", parsed.pronunciationTip)
    }
}
