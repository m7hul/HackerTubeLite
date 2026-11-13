package com.example.hackertubelite

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One video. Short JSON keys keep the bundled asset small. */
@Serializable
data class Video(
    @SerialName("i") val id: String,
    @SerialName("t") val title: String,
    @SerialName("c") val channel: String = "",
    @SerialName("s") val summary: String = "",
    @SerialName("w") val why: String = "",
    @SerialName("k") val type: String = "",
    @SerialName("p") val topic: String = "",
    @SerialName("g") val tags: List<String> = emptyList(),
    @SerialName("a") val cats: List<String> = emptyList(),
    @SerialName("q") val quote: String = "",
    @SerialName("qi") val quoteId: Long = 0,
    @SerialName("qy") val quoteYear: String = "",
    @SerialName("f") val firstYear: Int = 0,
    @SerialName("l") val lastYear: Int = 0,
    @SerialName("r") val recommenders: Int = 0,
    @SerialName("n") val news: Int = 0,
    @SerialName("d") val dated: Int = 0,
    @SerialName("x") val score: Double = 0.0,
)

@Serializable
data class Cat(val id: String, val n: String)

@Serializable
data class Area(val id: String, val n: String, val c: List<Cat>)

@Serializable
data class Related(val m: List<String> = emptyList(), val p: List<String> = emptyList())

data class Mention(
    val author: String,
    val month: String,
    val stance: String,
    val text: String,
    val commentId: String,
)

/** Lower-cased text used by the local search. */
class SearchEntry(val title: String, val meta: String, val body: String)

val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

val TYPE_CHIPS = listOf(
    "talk" to "Talks",
    "explainer" to "Explainers",
    "demo" to "Demos",
    "lecture" to "Lectures",
    "documentary" to "Documentaries",
    "interview" to "Interviews",
    "tutorial" to "Tutorials",
    "course" to "Courses",
    "podcast" to "Podcasts",
    "commentary" to "Commentary",
)

fun Video.typeLabel(): String = type.replaceFirstChar { it.uppercase() }

fun Video.years(): String =
    if (firstYear == 0 || firstYear == lastYear) "$lastYear" else "$firstYear–$lastYear"

fun Video.hnLine(): String =
    when {
        recommenders == 1 -> "Recommended by 1 person on HN"
        recommenders > 1 -> "Recommended by $recommenders people on HN"
        else -> "Cited on HN"
    }

fun Video.thumbUrl(): String = "https://i.ytimg.com/vi/$id/hq2.jpg"
fun Video.watchUrl(): String = "https://www.youtube.com/watch?v=$id"
