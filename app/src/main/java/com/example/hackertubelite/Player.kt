package com.example.hackertubelite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/** Implemented by MainActivity so the WebView can go fullscreen (landscape, bars hidden). */
interface FullscreenController {
    fun enter(view: View, callback: WebChromeClient.CustomViewCallback)
    fun exit()
}

val LocalFullscreen = compositionLocalOf<FullscreenController?> { null }

private data class TranscriptCue(val startSec: Int, val text: String)

private data class VideoMetadata(
    val videoId: String,
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val cues: List<TranscriptCue> = emptyList()
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(videoId: String, modifier: Modifier = Modifier) {
    val fullscreen = LocalFullscreen.current

    key(videoId) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. TOP: Working 16:9 YouTube WebView (untouched)
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false

                        webChromeClient = object : WebChromeClient() {
                            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                                val f = fullscreen
                                if (f != null) f.enter(view, callback) else callback.onCustomViewHidden()
                            }

                            override fun onHideCustomView() {
                                fullscreen?.exit()
                            }
                        }

                        val html = "<html><head>" +
                            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                            "<style>html,body{margin:0;background:#000;overflow:hidden}" +
                            "p{margin:0;padding:12px;text-align:center;color:#888;font:14px sans-serif}" +
                            "iframe{position:absolute;left:0;top:0;width:100vw;height:56.25vw;border:0}</style>" +
                            "</head><body><p>Loading player...</p>" +
                            "<iframe src='https://www.youtube-nocookie.com/embed/${videoId}?autoplay=1&playsinline=1&rel=0' " +
                            "allow='autoplay; encrypted-media; fullscreen' allowfullscreen></iframe>" +
                            "</body></html>"
                        loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
                    }
                },
                onRelease = {
                    it.stopLoading()
                    it.destroy()
                },
            )

            // 2. BOTTOM: Local AI Video Q&A Section
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    LocalAiPanelView(ctx, videoId)
                },
                onRelease = {
                    it.cleanup()
                }
            )
        }
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class LocalAiPanelView(
    context: Context,
    private val videoId: String
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val openedAtMs = System.currentTimeMillis()
    private val fetchLock = Any()

    private val statusText: TextView
    private val engineBadge: TextView
    private val chatContainer: LinearLayout
    private val chatScroll: ScrollView
    private val questionInput: EditText
    private val askButton: TextView

    @Volatile
    private var videoMeta = VideoMetadata(videoId = videoId)

    @Volatile
    private var isFetchingMeta = true

    @Volatile
    private var mediaPipeLlmInstance: Any? = null

    @Volatile
    private var ollamaModelName: String? = null

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(12))
        setBackgroundColor(Color.parseColor("#121212"))
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Header Row
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val headerTitle = TextView(context).apply {
            text = "🤖 Local Video AI"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        engineBadge = TextView(context).apply {
            text = "Checking Local AI..."
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg("#1E272C", dp(12))
            setOnClickListener { detectLocalAiBackends(showToastInChat = true) }
        }

        headerRow.addView(headerTitle)
        headerRow.addView(engineBadge)
        addView(headerRow)

        // Transcript & Video Status (tap to retry if needed)
        statusText = TextView(context).apply {
            text = "⏳ Loading video transcript & captions..."
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener {
                text = "🔄 Retrying transcript download..."
                loadVideoMetadataAndTranscript(forceRetry = true)
            }
        }
        addView(statusText)

        // Quick Action Chips
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        val quickPrompts = listOf(
            "⚡ Summarize Video" to "Summarize what this video is about in 3-4 bullet points.",
            "🎯 Explain Current Part" to "Explain what is being discussed around my current playback time.",
            "📌 Key Takeaways" to "What are the main takeaways or important points in this video?",
            "📜 Show Transcript" to "__SHOW_TRANSCRIPT__"
        )

        for ((label, promptText) in quickPrompts) {
            val chip = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#E0E0E0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedBg("#263238", dp(16), strokeColor = "#37474F")
                val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(8)
                layoutParams = lp
                setOnClickListener {
                    if (promptText == "__SHOW_TRANSCRIPT__") {
                        showCurrentTranscriptExcerpt()
                    } else {
                        submitQuestion(promptText)
                    }
                }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)
        addView(chipScroll)

        // Chat Messages Scroll Area
        chatScroll = ScrollView(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190))
            background = roundedBg("#181818", dp(10), strokeColor = "#2A2A2A")
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }

        chatContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        chatScroll.addView(chatContainer)
        addView(chatScroll)

        // Question Input Row
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        questionInput = EditText(context).apply {
            hint = "Ask about this video..."
            setHintTextColor(Color.parseColor("#757575"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg("#1E1E1E", dp(20), strokeColor = "#333333")
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val q = text.toString().trim()
                    if (q.isNotEmpty()) {
                        setText("")
                        hideKeyboard()
                        submitQuestion(q)
                    }
                    true
                } else false
            }
        }

        askButton = TextView(context).apply {
            text = "Ask"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundedBg("#00E676", dp(20))
            val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener {
                val q = questionInput.text.toString().trim()
                if (q.isNotEmpty()) {
                    questionInput.setText("")
                    hideKeyboard()
                    submitQuestion(q)
                }
            }
        }

        inputRow.addView(questionInput)
        inputRow.addView(askButton)
        addView(inputRow)

        appendChatBubble(
            sender = "AI",
            message = "Ask me anything about this video! Tap a chip above or type a question below."
        )

        loadVideoMetadataAndTranscript(forceRetry = false)
        detectLocalAiBackends(showToastInChat = false)
    }

    private fun estimatedPlaybackSec(): Int {
        return ((System.currentTimeMillis() - openedAtMs) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun loadVideoMetadataAndTranscript(forceRetry: Boolean) {
        executor.execute {
            val fetched = ensureMetadataLoaded(forceRetry)
            mainHandler.post {
                updateStatusBanner(fetched)
            }
        }
    }

    private fun ensureMetadataLoaded(forceRetry: Boolean = false): VideoMetadata {
        synchronized(fetchLock) {
            if (!forceRetry && !isFetchingMeta && (videoMeta.cues.isNotEmpty() || videoMeta.description.isNotBlank())) {
                return videoMeta
            }
            isFetchingMeta = true
            val fetched = fetchYouTubeContext(videoId)
            videoMeta = fetched
            isFetchingMeta = false
            return fetched
        }
    }

    private fun updateStatusBanner(meta: VideoMetadata) {
        val titleDisplay = if (meta.title.isNotBlank()) meta.title else "Video $videoId"
        val cueCount = meta.cues.size
        statusText.text = when {
            cueCount > 0 -> "📺 $titleDisplay\n✅ $cueCount caption lines loaded for Q&A"
            meta.description.isNotBlank() -> "📺 $titleDisplay\nℹ️ Captions unavailable; description loaded (${meta.description.length} chars) — tap to retry"
            else -> "📺 $titleDisplay\n⚠️ Captions didn't load — tap here to retry"
        }
    }

    private fun detectLocalAiBackends(showToastInChat: Boolean) {
        executor.execute {
            val detectedOllama = checkLocalOllamaModel()
            if (detectedOllama != null) {
                ollamaModelName = detectedOllama
                mainHandler.post {
                    engineBadge.text = "🟢 Ollama: $detectedOllama"
                    if (showToastInChat) {
                        appendChatBubble("System", "Connected to local Ollama model: $detectedOllama")
                    }
                }
                return@execute
            }

            if (mediaPipeLlmInstance == null) {
                val modelFile = findOnDeviceModelFile(context)
                if (modelFile != null) {
                    val loaded = tryInitMediaPipeModel(context, modelFile)
                    if (loaded != null) {
                        mediaPipeLlmInstance = loaded
                        mainHandler.post {
                            engineBadge.text = "🟢 On-Device: ${modelFile.name.take(14)}"
                            if (showToastInChat) {
                                appendChatBubble("System", "Loaded on-device model: ${modelFile.name}")
                            }
                        }
                        return@execute
                    }
                }
            } else {
                return@execute
            }

            mainHandler.post {
                engineBadge.text = "⚡ Built-in Transcript AI"
                if (showToastInChat) {
                    appendChatBubble(
                        "System",
                        "Running Built-in Offline Transcript AI.\n" +
                            "• For local Ollama: run `ollama serve` in Termux and tap this badge.\n" +
                            "• For MediaPipe LLM: place a `.task` or `.bin` model in Downloads."
                    )
                }
            }
        }
    }

    private fun submitQuestion(question: String) {
        val currentSec = estimatedPlaybackSec()
        val timeFormatted = formatSec(currentSec)
        appendChatBubble("You", question, isUser = true)
        val thinkingView = appendChatBubble("AI", "Thinking...")

        executor.execute {
            val meta = if (videoMeta.cues.isEmpty()) ensureMetadataLoaded(forceRetry = true) else videoMeta
            mainHandler.post { updateStatusBanner(meta) }

            val relevantContext = buildContextWindow(meta, currentSec)
            val prompt = """
                You are a concise video assistant running locally on a phone.
                Answer the user's question based on the video context below.
                
                Video Title: ${meta.title.ifBlank { videoId }}
                Channel: ${meta.author.ifBlank { "Unknown" }}
                Approx Playback Time: $timeFormatted
                Description: ${meta.description.take(600)}
                
                Transcript Excerpt:
                $relevantContext
                
                User Question: $question
            """.trimIndent()

            val answer = when {
                ollamaModelName != null -> {
                    queryLocalOllama(ollamaModelName!!, prompt)
                        ?: answerWithBuiltInTranscriptAi(question, meta, currentSec)
                }
                mediaPipeLlmInstance != null -> {
                    queryMediaPipeLlm(mediaPipeLlmInstance!!, prompt)
                        ?: answerWithBuiltInTranscriptAi(question, meta, currentSec)
                }
                else -> {
                    val freshOllama = checkLocalOllamaModel()
                    if (freshOllama != null) {
                        ollamaModelName = freshOllama
                        mainHandler.post { engineBadge.text = "🟢 Ollama: $freshOllama" }
                        queryLocalOllama(freshOllama, prompt)
                            ?: answerWithBuiltInTranscriptAi(question, meta, currentSec)
                    } else {
                        answerWithBuiltInTranscriptAi(question, meta, currentSec)
                    }
                }
            }

            mainHandler.post {
                thinkingView.text = "AI: $answer"
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun showCurrentTranscriptExcerpt() {
        val loadingBubble = appendChatBubble("AI", "Loading transcript excerpt...")
        executor.execute {
            val meta = if (videoMeta.cues.isEmpty()) ensureMetadataLoaded(forceRetry = true) else videoMeta
            val currentSec = estimatedPlaybackSec()

            val resultText = if (meta.cues.isNotEmpty()) {
                val minSec = (currentSec - 90).coerceAtLeast(0)
                val maxSec = currentSec + 120
                val nearby = meta.cues.filter { it.startSec in minSec..maxSec }.ifEmpty { meta.cues.take(20) }
                "Transcript (${meta.cues.size} total lines):\n" +
                    nearby.take(25).joinToString("\n") { "[${formatSec(it.startSec)}] ${it.text}" }
            } else if (meta.description.isNotBlank()) {
                "No closed-caption track returned; video description:\n\n${meta.description.take(700)}"
            } else {
                "Could not load captions for ${meta.title.ifBlank { videoId }}. Check connection and tap the status bar above to retry."
            }

            mainHandler.post {
                updateStatusBanner(meta)
                loadingBubble.text = resultText
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun appendChatBubble(sender: String, message: String, isUser: Boolean = false): TextView {
        val bubble = TextView(context).apply {
            text = "$sender: $message"
            setTextColor(if (isUser) Color.parseColor("#E8F5E9") else Color.parseColor("#ECEFF1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBg(
                colorHex = if (isUser) "#1B3B2B" else "#222629",
                radiusPx = dp(10)
            )
            val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
        chatContainer.addView(bubble)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    private fun buildContextWindow(meta: VideoMetadata, currentSec: Int): String {
        if (meta.cues.isEmpty()) {
            return meta.description.ifBlank { "Video Title: ${meta.title} by ${meta.author}" }.take(1500)
        }
        val minSec = (currentSec - 180).coerceAtLeast(0)
        val maxSec = currentSec + 180
        val windowCues = meta.cues.filter { it.startSec in minSec..maxSec }
        val selected = if (windowCues.size >= 5) windowCues else meta.cues.take(60)
        return selected.take(80).joinToString("\n") { "[${formatSec(it.startSec)}] ${it.text}" }
    }

    private fun answerWithBuiltInTranscriptAi(
        question: String,
        meta: VideoMetadata,
        currentSec: Int
    ): String {
        val qLower = question.lowercase()
        val allCues = meta.cues

        if (qLower.contains("current") || qLower.contains("right now") || qLower.contains("this part")) {
            if (allCues.isNotEmpty()) {
                val nearby = allCues.filter { abs(it.startSec - currentSec) <= 60 }
                    .ifEmpty { allCues.take(10) }
                val joined = nearby.take(12).joinToString(" ") { it.text }
                return "Around [${formatSec(currentSec)}], the speaker says:\n\"$joined\""
            }
        }

        if (qLower.contains("summar") || qLower.contains("takeaway") || qLower.contains("about") || qLower.contains("point")) {
            val sb = StringBuilder()
            if (meta.title.isNotBlank()) sb.append("• Video: ${meta.title}\n")
            if (meta.author.isNotBlank()) sb.append("• Speaker / Channel: ${meta.author}\n")
            if (allCues.isNotEmpty()) {
                val total = allCues.size
                val indices = listOf(0, total / 5, (total * 2) / 5, (total * 3) / 5, (total * 4) / 5)
                    .distinct()
                    .filter { it in allCues.indices }

                sb.append("• Key highlights across the talk:\n")
                for (idx in indices) {
                    val chunk = allCues.subList(idx, (idx + 3).coerceAtMost(total))
                    val text = chunk.joinToString(" ") { it.text }
                    sb.append("  [${formatSec(chunk.first().startSec)}] $text\n")
                }
            } else if (meta.description.isNotBlank()) {
                sb.append("• Overview: ${meta.description.take(450)}")
            } else {
                sb.append("• Topic: ${meta.title}")
            }
            return sb.toString().trim()
        }

        val stopWords = setOf(
            "what", "when", "where", "which", "who", "whom", "this", "that", "these",
            "those", "does", "about", "video", "explain", "tell", "from", "with", "have", "how", "why"
        )
        val keywords = qLower.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in stopWords }

        if (allCues.isNotEmpty() && keywords.isNotEmpty()) {
            val windows = allCues.chunked(4)
            val scored = windows.map { chunk ->
                val textCombined = chunk.joinToString(" ") { it.text }
                val textLower = textCombined.lowercase()
                val keywordHits = keywords.count { kw -> textLower.contains(kw) }
                val timeBonus = if (abs(chunk.first().startSec - currentSec) <= 120) 0.4 else 0.0
                Triple(chunk.first().startSec, textCombined, keywordHits + timeBonus)
            }.sortedByDescending { it.third }

            val bestMatches = scored.filter { it.third >= 1.0 }.take(3)
            if (bestMatches.isNotEmpty()) {
                return bestMatches.joinToString("\n\n") { (sec, txt, _) ->
                    "[${formatSec(sec)}] \"$txt\""
                }
            }
        }

        return when {
            allCues.isNotEmpty() -> {
                val nearCurrent = allCues.filter { abs(it.startSec - currentSec) <= 75 }.ifEmpty { allCues.take(8) }
                "From the transcript near [${formatSec(currentSec)}]:\n\"${nearCurrent.take(10).joinToString(" ") { it.text }}\""
            }
            meta.description.isNotBlank() -> {
                "From the video description:\n${meta.description.take(500)}"
            }
            else -> {
                "Video: \"${meta.title}\" by ${meta.author}. (No closed captions were returned by YouTube for this video.)"
            }
        }
    }

    /**
     * Multi-layered YouTube metadata & caption fetcher:
     * 1. Innertube ANDROID_VR, IOS, and ANDROID 20.x clients
     * 2. Watch page HTML bracket-balanced extractor for `captionTracks` and `shortDescription`
     * 3. Piped public API fallback
     * 4. Universal parser supporting json3, srv3 (<p t="...">), srv1 (<text start="...">), and vtt
     */
    private fun fetchYouTubeContext(vid: String): VideoMetadata {
        var title = ""
        var author = ""
        var description = ""
        val captionCandidates = mutableListOf<Pair<String, String>>() // (url, userAgent)

        data class ClientSpec(val name: String, val version: String, val ua: String, val extraJson: String = "")

        val clients = listOf(
            ClientSpec(
                name = "ANDROID_VR",
                version = "1.60.19",
                ua = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1)",
                extraJson = """"deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12","""
            ),
            ClientSpec(
                name = "IOS",
                version = "20.10.4",
                ua = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X; en_US)",
                extraJson = """"deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.1.22D72","""
            ),
            ClientSpec(
                name = "ANDROID",
                version = "20.10.38",
                ua = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US)",
                extraJson = """"androidSdkVersion":34,"osName":"Android","osVersion":"14","""
            )
        )

        // 1. Try Innertube clients
        for (client in clients) {
            if (captionCandidates.isNotEmpty() && title.isNotBlank() && description.isNotBlank()) break
            try {
                val payload = """
                    {
                      "context": {
                        "client": {
                          "clientName": "${client.name}",
                          "clientVersion": "${client.version}",
                          ${client.extraJson}
                          "hl": "en",
                          "gl": "US"
                        }
                      },
                      "videoId": "$vid"
                    }
                """.trimIndent()

                val responseStr = httpPostJson(
                    urlStr = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    jsonBody = payload,
                    userAgent = client.ua
                ) ?: continue

                val json = JSONObject(responseStr)
                json.optJSONObject("videoDetails")?.let { details ->
                    if (title.isBlank()) title = details.optString("title", "")
                    if (author.isBlank()) author = details.optString("author", "")
                    if (description.isBlank()) description = details.optString("shortDescription", "")
                }

                val tracks = json.optJSONObject("captions")
                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                    ?.optJSONArray("captionTracks")

                val bestUrl = pickBestCaptionUrl(tracks)
                if (bestUrl != null) {
                    captionCandidates.add(bestUrl to client.ua)
                }
            } catch (_: Exception) {}
        }

        // 2. Try Watch Page HTML extraction with balanced JSON array matching
        if (captionCandidates.isEmpty() || description.isBlank()) {
            try {
                val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                val html = httpGet(
                    urlStr = "https://www.youtube.com/watch?v=$vid&hl=en",
                    userAgent = desktopUa
                )
                if (!html.isNullOrBlank()) {
                    if (title.isBlank()) {
                        Regex(""""title":\s*"((?:[^"\\]|\\.)*)"""").find(html)?.let {
                            title = unescapeJsonString(it.groupValues[1])
                        }
                    }
                    if (description.isBlank()) {
                        Regex(""""shortDescription":\s*"((?:[^"\\]|\\.)*)"""").find(html)?.let {
                            description = unescapeJsonString(it.groupValues[1])
                        }
                    }
                    val tracksJsonArr = extractJsonArrayAfterKey(html, "\"captionTracks\":")
                    if (tracksJsonArr != null) {
                        val bestUrl = pickBestCaptionUrl(JSONArray(tracksJsonArr))
                        if (bestUrl != null) {
                            captionCandidates.add(bestUrl to desktopUa)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Parse captions from candidate URLs
        var parsedCues = emptyList<TranscriptCue>()
        for ((rawUrl, ua) in captionCandidates) {
            parsedCues = downloadAndParseCaptions(rawUrl, ua)
            if (parsedCues.isNotEmpty()) break
        }

        // 4. Fallback: Piped API if YouTube throttled direct timedtext requests
        if (parsedCues.isEmpty() || description.isBlank()) {
            val pipedHosts = listOf(
                "https://pipedapi.kavin.rocks",
                "https://api.piped.private.coffee"
            )
            for (host in pipedHosts) {
                if (parsedCues.isNotEmpty() && description.isNotBlank()) break
                try {
                    val streamJson = httpGet("$host/streams/$vid", "Mozilla/5.0") ?: continue
                    val obj = JSONObject(streamJson)
                    if (title.isBlank()) title = obj.optString("title", "")
                    if (author.isBlank()) author = obj.optString("uploader", "")
                    if (description.isBlank()) description = obj.optString("description", "")

                    if (parsedCues.isEmpty()) {
                        val subs = obj.optJSONArray("subtitles")
                        if (subs != null && subs.length() > 0) {
                            var subUrl = subs.getJSONObject(0).optString("url")
                            for (i in 0 until subs.length()) {
                                val s = subs.getJSONObject(i)
                                if (s.optString("code").startsWith("en")) {
                                    subUrl = s.optString("url")
                                    break
                                }
                            }
                            if (subUrl.isNotBlank()) {
                                parsedCues = downloadAndParseCaptions(subUrl, "Mozilla/5.0")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 5. Final fallback for title/author via noembed
        if (title.isBlank()) {
            try {
                val noembed = httpGet("https://noembed.com/embed?url=https://www.youtube.com/watch?v=$vid", "Mozilla/5.0")
                if (noembed != null) {
                    val obj = JSONObject(noembed)
                    title = obj.optString("title", "")
                    author = obj.optString("author_name", "")
                }
            } catch (_: Exception) {}
        }

        return VideoMetadata(
            videoId = vid,
            title = title,
            author = author,
            description = description,
            cues = parsedCues
        )
    }

    private fun pickBestCaptionUrl(tracks: JSONArray?): String? {
        if (tracks == null || tracks.length() == 0) return null
        var selected = tracks.getJSONObject(0)
        for (i in 0 until tracks.length()) {
            val t = tracks.getJSONObject(i)
            val lang = t.optString("languageCode", "")
            val kind = t.optString("kind", "")
            if (lang.startsWith("en") && kind != "asr") {
                selected = t
                break
            } else if (lang.startsWith("en")) {
                selected = t
            }
        }
        return selected.optString("baseUrl").takeIf { it.isNotBlank() }
    }

    private fun downloadAndParseCaptions(baseUrl: String, userAgent: String): List<TranscriptCue> {
        // Attempt 1: Force fmt=json3 (replacing any existing fmt=srv3/srv1)
        val json3Url = if (baseUrl.contains("fmt=")) {
            baseUrl.replace(Regex("fmt=[^&]+"), "fmt=json3")
        } else {
            "$baseUrl&fmt=json3"
        }

        val attempts = listOf(json3Url, baseUrl).distinct()
        for (url in attempts) {
            val raw = httpGet(url, userAgent) ?: continue
            val parsed = parseCaptionPayload(raw)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    /**
     * Parses JSON3, SRV3 (<p t="ms">), SRV1 (<text start="sec">), and WebVTT caption formats.
     */
    private fun parseCaptionPayload(payload: String): List<TranscriptCue> {
        val trimmed = payload.trimStart()
        if (trimmed.isEmpty()) return emptyList()
        val cues = mutableListOf<TranscriptCue>()

        // Format A: JSON3
        if (trimmed.startsWith("{")) {
            try {
                val events = JSONObject(trimmed).optJSONArray("events") ?: JSONArray()
                for (i in 0 until events.length()) {
                    val ev = events.getJSONObject(i)
                    val startMs = ev.optLong("tStartMs", 0L)
                    val segs = ev.optJSONArray("segs") ?: continue
                    val sb = StringBuilder()
                    for (j in 0 until segs.length()) {
                        sb.append(segs.getJSONObject(j).optString("utf8", ""))
                    }
                    val clean = sb.toString().replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                    if (clean.isNotEmpty()) {
                        cues.add(TranscriptCue((startMs / 1000L).toInt(), clean))
                    }
                }
                if (cues.isNotEmpty()) return cues
            } catch (_: Exception) {}
        }

        // Format B: SRV3 XML (<p t="12340" d="3000"><s>word</s>...</p>)
        val srv3Regex = Regex("""<p\s+[^>]*\bt="(\d+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        for (match in srv3Regex.findAll(trimmed)) {
            val startMs = match.groupValues[1].toLongOrNull() ?: 0L
            val innerHtml = match.groupValues[2].replace(Regex("<[^>]+>"), "")
            val decoded = Html.fromHtml(innerHtml, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (decoded.isNotEmpty()) {
                cues.add(TranscriptCue((startMs / 1000L).toInt(), decoded))
            }
        }
        if (cues.isNotEmpty()) return cues

        // Format C: SRV1 XML (<text start="12.34" dur="3.2">...</text>)
        val srv1Regex = Regex("""<text\s+[^>]*\bstart="([\d.]+)"[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
        for (match in srv1Regex.findAll(trimmed)) {
            val startSec = match.groupValues[1].toDoubleOrNull()?.toInt() ?: 0
            val innerHtml = match.groupValues[2].replace(Regex("<[^>]+>"), "")
            val decoded = Html.fromHtml(innerHtml, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (decoded.isNotEmpty()) {
                cues.add(TranscriptCue(startSec, decoded))
            }
        }
        if (cues.isNotEmpty()) return cues

        // Format D: WebVTT
        if (trimmed.startsWith("WEBVTT")) {
            val lines = trimmed.lines()
            val timeRegex = Regex("""(?:(\d+):)?(\d{2}):(\d{2})[.,]\d+\s*-->""")
            var currentSec = -1
            val textBuf = StringBuilder()
            for (line in lines) {
                val m = timeRegex.find(line)
                if (m != null) {
                    if (currentSec >= 0 && textBuf.isNotEmpty()) {
                        cues.add(TranscriptCue(currentSec, textBuf.toString().trim()))
                    }
                    textBuf.clear()
                    val hrs = m.groupValues[1].toIntOrNull() ?: 0
                    val mins = m.groupValues[2].toIntOrNull() ?: 0
                    val secs = m.groupValues[3].toIntOrNull() ?: 0
                    currentSec = hrs * 3600 + mins * 60 + secs
                } else if (currentSec >= 0 && line.isNotBlank() && !line.startsWith("NOTE")) {
                    val cleanLine = line.replace(Regex("<[^>]+>"), "").trim()
                    if (cleanLine.isNotEmpty()) {
                        if (textBuf.isNotEmpty()) textBuf.append(" ")
                        textBuf.append(cleanLine)
                    }
                }
            }
            if (currentSec >= 0 && textBuf.isNotEmpty()) {
                cues.add(TranscriptCue(currentSec, textBuf.toString().trim()))
            }
        }

        return cues
    }

    private fun extractJsonArrayAfterKey(source: String, key: String): String? {
        val keyIdx = source.indexOf(key)
        if (keyIdx == -1) return null
        val startBracket = source.indexOf('[', keyIdx + key.length)
        if (startBracket == -1) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in startBracket until source.length) {
            val c = source[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) {
                        return source.substring(startBracket, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun unescapeJsonString(raw: String): String {
        return try {
            JSONObject("""{"v":"$raw"}""").optString("v", raw)
        } catch (_: Exception) {
            raw.replace("\\n", "\n").replace("\\\"", "\"")
        }
    }

    private fun httpGet(urlStr: String, userAgent: String): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+999")
            }
            if (conn.responseCode in 200..299) {
                val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(conn.inputStream)
                } else {
                    conn.inputStream
                }
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun httpPostJson(urlStr: String, jsonBody: String, userAgent: String): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
            if (conn.responseCode in 200..299) {
                val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(conn.inputStream)
                } else {
                    conn.inputStream
                }
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun checkLocalOllamaModel(): String? {
        return try {
            val body = httpGet("http://127.0.0.1:11434/api/tags", "HackerTubeLite") ?: return null
            val models = JSONObject(body).optJSONArray("models")
            if (models != null && models.length() > 0) {
                models.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryLocalOllama(model: String, prompt: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("model", model)
                put("prompt", prompt)
                put("stream", false)
            }
            val resp = httpPostJson("http://127.0.0.1:11434/api/generate", payload.toString(), "HackerTubeLite")
                ?: return null
            JSONObject(resp).optString("response").trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun findOnDeviceModelFile(ctx: Context): File? {
        val searchDirs = listOfNotNull(
            ctx.filesDir,
            ctx.getExternalFilesDir(null),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        for (dir in searchDirs) {
            val files = dir.listFiles() ?: continue
            val match = files.firstOrNull { file ->
                file.isFile && (file.name.endsWith(".task", true) || file.name.endsWith(".bin", true))
            }
            if (match != null && match.canRead()) return match
        }
        return null
    }

    private fun tryInitMediaPipeModel(ctx: Context, modelFile: File): Any? {
        return try {
            val llmClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
            val builderMethod = optionsClass.getMethod("builder")
            val builder = builderMethod.invoke(null)
            val builderClass = builder.javaClass

            builderClass.getMethod("setModelPath", String::class.java).invoke(builder, modelFile.absolutePath)
            try {
                builderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType).invoke(builder, 512)
            } catch (_: Exception) {}

            val options = builderClass.getMethod("build").invoke(builder)
            val createMethod = llmClass.getMethod("createFromOptions", Context::class.java, optionsClass)
            createMethod.invoke(null, ctx, options)
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryMediaPipeLlm(llmInstance: Any, prompt: String): String? {
        return try {
            val generateMethod = llmInstance.javaClass.getMethod("generateResponse", String::class.java)
            (generateMethod.invoke(llmInstance, prompt) as? String)?.trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatSec(totalSec: Int): String {
        val mins = totalSec / 60
        val secs = totalSec % 60
        return "%02d:%02d".format(mins, secs)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun roundedBg(colorHex: String, radiusPx: Int, strokeColor: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = radiusPx.toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), Color.parseColor(strokeColor))
            }
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(questionInput.windowToken, 0)
    }

    fun cleanup() {
        try {
            mediaPipeLlmInstance?.let { instance ->
                instance.javaClass.getMethod("close").invoke(instance)
            }
        } catch (_: Throwable) {}
        mediaPipeLlmInstance = null
        executor.shutdownNow()
    }
}