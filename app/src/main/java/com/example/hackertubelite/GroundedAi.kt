package com.example.hackertubelite

data class AiContext(val video: Video, val comments: List<Mention>)

/**
 * Answers questions about a video using only what the app already has: HackerTube's summary,
 * "why HN watches this", the top quote and Hacker News comments. No network, no transcript.
 */
object GroundedAi {
    val quick = listOf(
        "Summarize" to "Summarize this video.",
        "Why watch it?" to "Why is this worth watching?",
        "What do people say?" to "What do commenters say about it?",
        "Who is it for?" to "Who is this for, and what level is it?",
    )

    private val STOP = setOf(
        "the", "and", "for", "are", "was", "were", "what", "when", "where", "which", "who", "how",
        "why", "this", "that", "these", "those", "does", "about", "video", "with", "have", "you",
        "can", "tell", "explain", "from", "talk", "speaker", "they", "their", "there", "into",
    )

    /** Prompt for the optional on-device model. */
    fun prompt(question: String, c: AiContext): String {
        val v = c.video
        val notes = buildString {
            append("Title: ").append(v.title).append(". Channel: ").append(v.channel).append(". ")
            if (v.why.isNotBlank()) append("Why people recommend it: ").append(v.why).append(" ")
            if (v.summary.isNotBlank()) append("Summary: ").append(v.summary).append(" ")
            for (m in c.comments.take(3)) append("A commenter said: ").append(m.text).append(" ")
        }.take(1400)
        return "Answer the question using only the notes.\nNotes: $notes\nQuestion: $question\nAnswer:"
    }

    /** Offline answer built from the stored text. */
    fun answer(question: String, c: AiContext): String {
        val v = c.video
        val ql = question.lowercase()
        fun has(vararg words: String) = words.any { ql.contains(it) }

        return when {
            has("comment", "people say", "commenter", "opinion", "review", "think of") -> commentsAnswer(c)
            has("level", "beginner", "prereq", "who is this for", "audience", "for whom") -> levelAnswer(v)
            has("why", "worth", "should i watch") -> {
                val base = v.why.ifBlank { v.summary }
                if (base.isBlank()) fallback(v) else base + "\n\n" + v.hnLine() + if (v.lastYear > 0) " (${v.years()})." else "."
            }
            has("summar", "overview", "what is this", "what's this") -> {
                val text = listOf(v.summary, v.why).filter { it.isNotBlank() }.joinToString("\n\n")
                text.ifBlank { fallback(v) }
            }
            else -> retrieve(question, c)
        }
    }

    private fun commentsAnswer(c: AiContext): String {
        if (c.comments.isNotEmpty()) {
            return c.comments.take(3).joinToString("\n\n") { "“${it.text}”\n— ${it.author}, ${it.month}" }
        }
        val v = c.video
        return if (v.quote.isNotBlank()) {
            "“${v.quote}”\n— Hacker News" + if (v.quoteYear.isNotBlank()) ", ${v.quoteYear}" else ""
        } else {
            "No Hacker News comments are stored for this video."
        }
    }

    private fun levelAnswer(v: Video): String {
        val level = when (v.level) {
            "intro" -> "It is aimed at beginners, with no background assumed."
            "intermediate" -> "It assumes some background in the topic."
            "advanced" -> "It is aimed at experienced viewers."
            else -> "HackerTube does not mark it as beginner or advanced, so it suits a general audience."
        }
        val kind = if (v.type.isBlank()) "video" else v.type
        return "This is a $kind by ${v.channel}. $level"
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length > 20 }

    private fun retrieve(question: String, c: AiContext): String {
        val v = c.video
        val tokens = question.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP }.distinct()
        val pool = sentences(v.summary) + sentences(v.why) + sentences(v.quote) +
            c.comments.flatMap { sentences(it.text) }
        if (tokens.isEmpty() || pool.isEmpty()) return fallback(v)
        val best = pool
            .map { s -> s to tokens.count { s.lowercase().contains(it) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
        return if (best.isEmpty()) {
            "I couldn’t find that in HackerTube’s notes for this video.\n\n" + fallback(v)
        } else {
            best.joinToString("\n\n")
        }
    }

    private fun fallback(v: Video): String =
        v.summary.ifBlank { "There isn’t much stored text about this video yet." }
}
