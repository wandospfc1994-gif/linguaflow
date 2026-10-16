package com.linguaflow.app.data.model

data class TutorFeedback(
    val reply: String,
    val corrections: String? = null,
    val pronunciationTip: String? = null,
    val rawOutput: String = ""
) {
    val hasCorrections: Boolean
        get() = !corrections.isNullOrBlank() && !corrections.equals("OK", ignoreCase = true)

    val hasPronunciationTip: Boolean
        get() = !pronunciationTip.isNullOrBlank() && !pronunciationTip.equals("OK", ignoreCase = true)
}
