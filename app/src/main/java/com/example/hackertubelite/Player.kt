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
            // 1. TOP: Your exact working YouTube WebView (untouched)
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

            // 2. BOTTOM: Separate Local AI Q&A section
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

    private val statusText: TextView
    private val engineBadge: TextView
    private val chatContainer: LinearLayout
    private val chatScroll: ScrollView
    private val questionInput: EditText
    private val askButton: TextView

    @Volatile
    private var videoMeta = VideoMetadata(videoId = videoId)

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

        // Transcript & Video Status
        statusText = TextView(context).apply {
            text = "Loading video transcript & details..."
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
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

        loadVideoMetadataAndTranscript()
        detectLocalAiBackends(showToastInChat = false)
    }

    private fun estimatedPlaybackSec(): Int {
        return ((System.currentTimeMillis() - openedAtMs) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun loadVideoMetadataAndTranscript() {
        executor.execute {
            val fetched = fetchYouTubeContext(videoId)
            videoMeta = fetched
            mainHandler.post {
                val titleDisplay = if (fetched.title.isNotBlank()) fetched.title else "Video $videoId"
                val cueCount = fetched.cues.size
                statusText.text = if (cueCount > 0) {
                    "📺 $titleDisplay\n✓ $cueCount caption lines loaded for Q&A"
                } else {
                    "📺 $titleDisplay\nℹ️ Using video title & description for Q&A"
                }
            }
        }
    }

    private fun detectLocalAiBackends(showToastInChat: Boolean) {
        executor.execute {
            // 1. Check Localhost Ollama on phone (127.0.0.1:11434)
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

            // 2. Check On-Device MediaPipe LLM (.task / .bin in Download or filesDir)
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

            // 3. Built-in Offline Transcript AI
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
            val relevantContext = buildContextWindow(videoMeta, currentSec)
            val prompt = """
                You are a concise video assistant running locally on a phone.
                Answer the user's question based on the video context below.
                
                Video Title: ${videoMeta.title.ifBlank { videoId }}
                Channel: ${videoMeta.author.ifBlank { "Unknown" }}
                Approx Playback Time: $timeFormatted
                Description: ${videoMeta.description.take(500)}
                
                Transcript Excerpt:
                $relevantContext
                
                User Question: $question
            """.trimIndent()

            val answer = when {
                ollamaModelName != null -> {
                    queryLocalOllama(ollamaModelName!!, prompt)
                        ?: answerWithBuiltInTranscriptAi(question, videoMeta, currentSec)
                }
                mediaPipeLlmInstance != null -> {
                    queryMediaPipeLlm(mediaPipeLlmInstance!!, prompt)
                        ?: answerWithBuiltInTranscriptAi(question, videoMeta, currentSec)
                }
                else -> {
                    val freshOllama = checkLocalOllamaModel()
                    if (freshOllama != null) {
                        ollamaModelName = freshOllama
                        mainHandler.post { engineBadge.text = "🟢 Ollama: $freshOllama" }
                        queryLocalOllama(freshOllama, prompt)
                            ?: answerWithBuiltInTranscriptAi(question, videoMeta, currentSec)
                    } else {
                        answerWithBuiltInTranscriptAi(question, videoMeta, currentSec)
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
        val cues = videoMeta.cues
        if (cues.isEmpty()) {
            appendChatBubble(
                "AI",
                if (videoMeta.description.isNotBlank()) {
                    "No closed captions found. Video description:\n\n${videoMeta.description.take(600)}"
                } else {
                    "No transcript or captions were found for this video."
                }
            )
            return
        }
        val currentSec = estimatedPlaybackSec()
        val minSec = (currentSec - 90).coerceAtLeast(0)
        val maxSec = currentSec + 90
        val nearby = cues.filter { it.startSec in minSec..maxSec }.ifEmpty { cues.take(15) }
        val snippet = nearby.take(20).joinToString("\n") { "[${formatSec(it.startSec)}] ${it.text}" }
        appendChatBubble("Transcript", snippet)
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
            return meta.description.ifBlank { "No transcript available." }.take(1500)
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

        if (allCues.isEmpty() && meta.description.isBlank()) {
            return "Transcript is still loading or unavailable for this video (${meta.title.ifBlank { videoId }})."
        }

        if (qLower.contains("current") || qLower.contains("right now") || qLower.contains("this part")) {
            if (allCues.isNotEmpty()) {
                val nearby = allCues.filter { abs(it.startSec - currentSec) <= 60 }
                    .ifEmpty { allCues.take(8) }
                val joined = nearby.take(10).joinToString(" ") { it.text }
                return "Around [${formatSec(currentSec)}], the video discusses:\n\"$joined\""
            }
        }

        if (qLower.contains("summar") || qLower.contains("takeaway") || qLower.contains("about")) {
            val sb = StringBuilder()
            if (meta.title.isNotBlank()) sb.append("• Title: ${meta.title}\n")
            if (meta.author.isNotBlank()) sb.append("• Channel: ${meta.author}\n")
            if (allCues.isNotEmpty()) {
                val step = (allCues.size / 4).coerceAtLeast(1)
                val samples = listOfNotNull(
                    allCues.getOrNull(0),
                    allCues.getOrNull(step),
                    allCues.getOrNull(step * 2),
                    allCues.getOrNull(step * 3)
                ).distinctBy { it.startSec }

                sb.append("• Key points across the video:\n")
                for (cue in samples) {
                    sb.append("  [${formatSec(cue.startSec)}] ${cue.text}\n")
                }
            } else if (meta.description.isNotBlank()) {
                sb.append("• Overview: ${meta.description.take(350)}")
            }
            return sb.toString().trim()
        }

        val stopWords = setOf(
            "what", "when", "where", "which", "who", "whom", "this", "that", "these",
            "those", "does", "about", "video", "explain", "tell", "from", "with", "have"
        )
        val keywords = qLower.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }

        if (allCues.isNotEmpty() && keywords.isNotEmpty()) {
            val windows = allCues.chunked(3)
            val scored = windows.map { chunk ->
                val textCombined = chunk.joinToString(" ") { it.text }
                val textLower = textCombined.lowercase()
                val keywordHits = keywords.count { kw -> textLower.contains(kw) }
                val timeProximityBonus = if (abs(chunk.first().startSec - currentSec) <= 120) 0.5 else 0.0
                Triple(chunk.first().startSec, textCombined, keywordHits + timeProximityBonus)
            }.sortedByDescending { it.third }

            val bestMatches = scored.filter { it.third >= 1.0 }.take(3)
            if (bestMatches.isNotEmpty()) {
                return bestMatches.joinToString("\n\n") { (sec, txt, _) ->
                    "[${formatSec(sec)}] \"$txt\""
                }
            }
        }

        return if (allCues.isNotEmpty()) {
            val nearCurrent = allCues.filter { abs(it.startSec - currentSec) <= 75 }.ifEmpty { allCues.take(6) }
            "From the transcript:\n\"${nearCurrent.take(8).joinToString(" ") { it.text }}\""
        } else {
            "From the video description:\n${meta.description.take(400)}"
        }
    }

    private fun checkLocalOllamaModel(): String? {
        return try {
            val conn = (URL("http://127.0.0.1:11434/api/tags").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1200
                readTimeout = 1500
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val models = JSONObject(body).optJSONArray("models")
                if (models != null && models.length() > 0) {
                    models.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryLocalOllama(model: String, prompt: String): String? {
        return try {
            val conn = (URL("http://127.0.0.1:11434/api/generate").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 60000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().apply {
                put("model", model)
                put("prompt", prompt)
                put("stream", false)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(resp).optString("response").trim().takeIf { it.isNotEmpty() }
            } else null
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

    private fun fetchYouTubeContext(vid: String): VideoMetadata {
        var title = ""
        var author = ""
        var description = ""
        var captionUrl: String? = null

        try {
            val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip")
            }
            val body = """
                {
                  "context": {
                    "client": {
                      "clientName": "ANDROID",
                      "clientVersion": "19.29.37",
                      "androidSdkVersion": 34,
                      "hl": "en",
                      "gl": "US"
                    }
                  },
                  "videoId": "$vid"
                }
            """.trimIndent()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            if (conn.responseCode == 200) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(responseStr)

                json.optJSONObject("videoDetails")?.let { details ->
                    title = details.optString("title", title)
                    author = details.optString("author", author)
                    description = details.optString("shortDescription", description)
                }

                val tracks = json.optJSONObject("captions")
                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                    ?.optJSONArray("captionTracks")

                if (tracks != null && tracks.length() > 0) {
                    var selectedTrack = tracks.getJSONObject(0)
                    for (i in 0 until tracks.length()) {
                        val t = tracks.getJSONObject(i)
                        if (t.optString("languageCode").startsWith("en")) {
                            selectedTrack = t
                            break
                        }
                    }
                    captionUrl = selectedTrack.optString("baseUrl").takeIf { it.isNotBlank() }
                }
            }
        } catch (_: Exception) {}

        if (title.isBlank()) {
            try {
                val noembed = URL("https://noembed.com/embed?url=https://www.youtube.com/watch?v=$vid").readText()
                val obj = JSONObject(noembed)
                title = obj.optString("title", "")
                author = obj.optString("author_name", "")
            } catch (_: Exception) {}
        }

        val cues = mutableListOf<TranscriptCue>()
        if (captionUrl != null) {
            try {
                val json3Url = if (captionUrl.contains("fmt=")) captionUrl else "$captionUrl&fmt=json3"
                val capText = URL(json3Url).readText()
                if (capText.trimStart().startsWith("{")) {
                    val events = JSONObject(capText).optJSONArray("events") ?: JSONArray()
                    for (i in 0 until events.length()) {
                        val ev = events.getJSONObject(i)
                        val startMs = ev.optLong("tStartMs", 0L)
                        val segs = ev.optJSONArray("segs") ?: continue
                        val lineBuilder = StringBuilder()
                        for (j in 0 until segs.length()) {
                            lineBuilder.append(segs.getJSONObject(j).optString("utf8", ""))
                        }
                        val clean = lineBuilder.toString().replace("\n", " ").trim()
                        if (clean.isNotEmpty()) {
                            cues.add(TranscriptCue((startMs / 1000L).toInt(), clean))
                        }
                    }
                } else {
                    val regex = Regex("""<text start="([\d.]+)"[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
                    for (match in regex.findAll(capText)) {
                        val sec = match.groupValues[1].toDoubleOrNull()?.toInt() ?: 0
                        val rawTxt = match.groupValues[2]
                        val decoded = Html.fromHtml(rawTxt, Html.FROM_HTML_MODE_LEGACY).toString()
                            .replace("\n", " ")
                            .trim()
                        if (decoded.isNotEmpty()) {
                            cues.add(TranscriptCue(sec, decoded))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return VideoMetadata(
            videoId = vid,
            title = title,
            author = author,
            description = description,
            cues = cues
        )
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