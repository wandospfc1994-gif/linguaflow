package com.linguaflow.app.engine.llm

import com.linguaflow.app.data.model.TutorFeedback

object TutorResponseParser {

    private val replyRegex = Regex("<reply>(.*?)(?:</reply>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val fixRegex = Regex("<fix>(.*?)(?:</fix>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val pronRegex = Regex("<pron>(.*?)(?:</pron>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /**
     * Remove vazamento de tags de controle do modelo e de templates de prompt.
     */
    fun cleanTemplateArtifacts(text: String): String {
        return text
            .replace(Regex("<start_of_turn>(user|model)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<end_of_turn>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)Current Student Message:.*?(?:\n|$)"), "")
            .replace(Regex("(?i)Conversation History:.*?(?:\n|$)"), "")
            .replace(Regex("(?i)^(?:Tutor|Assistant|AI|Model|Student)\\s*:\\s*"), "")
            .trim()
    }

    fun parse(rawText: String): TutorFeedback {
        val trimmed = cleanTemplateArtifacts(rawText)
        if (trimmed.isEmpty()) {
            return TutorFeedback(reply = "", rawOutput = "")
        }

        val hasAnyTags = trimmed.contains("<reply>", ignoreCase = true) ||
            trimmed.contains("<fix>", ignoreCase = true) ||
            trimmed.contains("<pron>", ignoreCase = true)

        if (!hasAnyTags) {
            // Fallback para modelos que não geraram tags XML
            return parseUntagged(trimmed)
        }

        var replyContent = replyRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
        var fixContent = fixRegex.find(trimmed)?.groupValues?.get(1)?.trim()
        var pronContent = pronRegex.find(trimmed)?.groupValues?.get(1)?.trim()

        // Remove tags caso tenha ocorrido vazamento dentro dos blocos
        if (replyContent.contains("<fix>", ignoreCase = true)) {
            replyContent = replyContent.substringBefore("<fix>").trim()
        }
        if (fixContent?.contains("<pron>", ignoreCase = true) == true) {
            fixContent = fixContent.substringBefore("<pron>").trim()
        }

        // Limpeza profunda de resquícios de template no conteúdo do reply
        replyContent = cleanTemplateArtifacts(replyContent)

        val normalizedFix = if (fixContent.isNullOrBlank() || fixContent.equals("OK", ignoreCase = true) || fixContent.equals("N/A", ignoreCase = true)) {
            null
        } else {
            cleanTemplateArtifacts(fixContent)
        }

        val normalizedPron = if (pronContent.isNullOrBlank() || pronContent.equals("OK", ignoreCase = true) || pronContent.equals("N/A", ignoreCase = true)) {
            null
        } else {
            cleanTemplateArtifacts(pronContent)
        }

        return TutorFeedback(
            reply = replyContent.ifBlank { cleanTemplateArtifacts(trimmed.substringBefore("<").trim()) },
            corrections = normalizedFix,
            pronunciationTip = normalizedPron,
            rawOutput = rawText
        )
    }

    private fun parseUntagged(text: String): TutorFeedback {
        val fixIndex = text.indexOf("Fix:", ignoreCase = true)
            .takeIf { it != -1 } ?: text.indexOf("Correções:", ignoreCase = true).takeIf { it != -1 }
        val pronIndex = text.indexOf("Pron:", ignoreCase = true)
            .takeIf { it != -1 } ?: text.indexOf("Pronúncia:", ignoreCase = true).takeIf { it != -1 }

        if (fixIndex == null && pronIndex == null) {
            val cleanOnly = cleanTemplateArtifacts(text)
            return TutorFeedback(reply = cleanOnly, rawOutput = text)
        }

        val reply = if (fixIndex != null && (pronIndex == null || fixIndex < pronIndex)) {
            text.substring(0, fixIndex).trim()
        } else if (pronIndex != null) {
            text.substring(0, pronIndex).trim()
        } else {
            text.trim()
        }

        val fix = if (fixIndex != null) {
            val end = pronIndex?.takeIf { it > fixIndex } ?: text.length
            text.substring(fixIndex, end).replace(Regex("^(Fix:|Correções:)", RegexOption.IGNORE_CASE), "").trim()
        } else null

        val pron = if (pronIndex != null) {
            val end = fixIndex?.takeIf { it > pronIndex } ?: text.length
            text.substring(pronIndex, end).replace(Regex("^(Pron:|Pronúncia:)", RegexOption.IGNORE_CASE), "").trim()
        } else null

        return TutorFeedback(
            reply = cleanTemplateArtifacts(reply),
            corrections = fix?.takeIf { !it.equals("OK", ignoreCase = true) }?.let { cleanTemplateArtifacts(it) },
            pronunciationTip = pron?.takeIf { !it.equals("OK", ignoreCase = true) }?.let { cleanTemplateArtifacts(it) },
            rawOutput = text
        )
    }
}
