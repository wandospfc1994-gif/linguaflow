package com.linguaflow.app.engine.llm

import com.linguaflow.app.data.model.TutorFeedback

object TutorResponseParser {

    private val replyRegex = Regex("<reply>(.*?)(?:</reply>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val fixRegex = Regex("<fix>(.*?)(?:</fix>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val pronRegex = Regex("<pron>(.*?)(?:</pron>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun parse(rawText: String): TutorFeedback {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return TutorFeedback(reply = "", rawOutput = "")
        }

        val hasAnyTags = trimmed.contains("<reply>", ignoreCase = true) ||
            trimmed.contains("<fix>", ignoreCase = true) ||
            trimmed.contains("<pron>", ignoreCase = true)

        if (!hasAnyTags) {
            // Fallback for untagged model outputs
            return parseUntagged(trimmed)
        }

        var replyContent = replyRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
        var fixContent = fixRegex.find(trimmed)?.groupValues?.get(1)?.trim()
        var pronContent = pronRegex.find(trimmed)?.groupValues?.get(1)?.trim()

        // Clean up remaining tags if unclosed tags overlapped
        if (replyContent.contains("<fix>", ignoreCase = true)) {
            replyContent = replyContent.substringBefore("<fix>").trim()
        }
        if (fixContent?.contains("<pron>", ignoreCase = true) == true) {
            fixContent = fixContent.substringBefore("<pron>").trim()
        }

        val normalizedFix = if (fixContent.isNullOrBlank() || fixContent.equals("OK", ignoreCase = true) || fixContent.equals("N/A", ignoreCase = true)) {
            null
        } else {
            fixContent
        }

        val normalizedPron = if (pronContent.isNullOrBlank() || pronContent.equals("OK", ignoreCase = true) || pronContent.equals("N/A", ignoreCase = true)) {
            null
        } else {
            pronContent
        }

        return TutorFeedback(
            reply = replyContent.ifBlank { trimmed.substringBefore("<").trim() },
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
            return TutorFeedback(reply = text, rawOutput = text)
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
            reply = reply,
            corrections = fix?.takeIf { !it.equals("OK", ignoreCase = true) },
            pronunciationTip = pron?.takeIf { !it.equals("OK", ignoreCase = true) },
            rawOutput = text
        )
    }
}
