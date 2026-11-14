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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PlayerWithLocalAiView(ctx, videoId, fullscreen)
            },
            onRelease = { view ->
                view.cleanup()
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class PlayerWithLocalAiView(
    context: Context,
    private val videoId: String,
    private val fullscreen: FullscreenController?
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()

    private val webView: WebView
    private val statusText: TextView
    private val engineBadge: TextView
    private val chatContainer: LinearLayout
    private val chatScroll: ScrollView
    private val questionInput: EditText
    private val askButton: TextView

    @Volatile
    private var currentPlaybackSec: Int = 0

    @Volatile
    private var videoMeta = VideoMetadata(videoId = videoId)

    @Volatile
    private var mediaPipeLlmInstance: Any? = null

    @Volatile
    private var ollamaModelName: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0D0D0D"))
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // 1. TOP SECTION: 16:9 YouTube WebView
        webView = object : WebView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val height = (width * 9) / 16
                setMeasuredDimension(width, height)
            }
        }.apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onTimeUpdate(seconds: Double) {
                    currentPlaybackSec = seconds.toInt().coerceAtLeast(0)
                }
            }, "AndroidPlayerBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreen != null) fullscreen.enter(view, callback) else callback.onCustomViewHidden()
                }

                override fun onHideCustomView() {
                    fullscreen?.exit()
                }
            }

            val html = """
                <html><head>
                <meta name='viewport' content='width=device-width,initial-scale=1'>
                <style>
                    html,body{margin:0;background:#000;overflow:hidden;width:100%;height:100%}
                    p{margin:0;padding:12px;text-align:center;color:#888;font:14px sans-serif}
                    iframe{position:absolute;left:0;top:0;width:100%;height:100%;border:0}
                </style>
                </head><body>
                <p>Loading player...</p>
                <iframe id='ytplayer' src='https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&enablejsapi=1'
                    allow='autoplay; encrypted-media; fullscreen' allowfullscreen></iframe>
                <script>
                    var iframe = document.getElementById('ytplayer');
                    iframe.onload = function() {
                        setInterval(function() {
                            try {
                                iframe.contentWindow.postMessage(JSON.stringify({event: 'listening', id: 1}), '*');
                            } catch(e) {}
                        }, 1500);
                    };
                    window.addEventListener('message', function(e) {
                        try {
                            var data = JSON.parse(e.data);
                            if (data.event === 'infoDelivery' && data.info && data.info.currentTime) {
                                AndroidPlayerBridge.onTimeUpdate(data.info.currentTime);
                            }
                        } catch(err) {}
                    });
                </script>
                </body></html>
            """.trimIndent()

            loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
        }
        addView(webView)

        // 2. BOTTOM SECTION: Local AI Assistant Panel
        val aiPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // Header Row (Title + Engine Status Badge)
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
        aiPanel.addView(headerRow)

        // Video & Transcript Status Line
        statusText = TextView(context).apply {
            text = "Fetching video transcript & details..."
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        }
        aiPanel.addView(statusText)

        // Quick Prompt Chips
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
            "🎯 Explain Current Part" to "Explain what is being discussed right now around my current playback time.",
            "📌 Key Takeaways" to "What are the main takeaways or important points mentioned in this video?",
            "📜 Transcript Here" to "__SHOW_TRANSCRIPT__"
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
        aiPanel.addView(chipScroll)

        // Scrollable Chat Messages Area
        chatScroll = ScrollView(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210))
            background = roundedBg("#181818", dp(10), strokeColor = "#2A2A2A")
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        chatContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        chatScroll.addView(chatContainer)
        aiPanel.addView(chatScroll)

        // Input Row (EditText + Ask Button)
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
        aiPanel.addView(inputRow)

        addView(aiPanel)

        appendChatBubble(
            sender = "AI",
            message = "Ask me anything about this video! Tap a quick chip above or type your question below."
        )

        // Start background setup
        loadVideoMetadataAndTranscript()
        detectLocalAiBackends(showToastInChat = false)
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
                    "📺 $titleDisplay\nℹ️ No closed captions found (using title & description)"
                }
            }
        }
    }

    /**
     * Checks in order:
     * 1. Local Ollama running on phone (`http://127.0.0.1:11434`)
     * 2. On-device MediaPipe `.task` or `.bin` model in `/sdcard/Download/` or app filesDir
     * 3. Built-in On-Device Transcript Q&A engine (always works with zero setup)
     */
    private fun detectLocalAiBackends(showToastInChat: Boolean) {
        executor.execute {
            // 1. Check Localhost Ollama (e.g., via Termux `ollama serve`)
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

            // 3. Fallback: Built-in Extractive Transcript AI
            mainHandler.post {
                engineBadge.text = "⚡ Built-in Transcript AI"
                if (showToastInChat) {
                    appendChatBubble(
                        "System",
                        "Using Built-in Offline Transcript AI.\n" +
                            "• To use Ollama on phone: run `ollama serve` in Termux and tap this badge.\n" +
                            "• To use MediaPipe LLM: place a `.task` or `.bin` model in your Downloads folder."
                    )
                }
            }
        }
    }

    private fun submitQuestion(question: String) {
        val timeFormatted = formatSec(currentPlaybackSec)
        appendChatBubble("You ($timeFormatted)", question, isUser = true)
        val thinkingView = appendChatBubble("AI", "Thinking...")

        executor.execute {
            val relevantContext = buildContextWindow(videoMeta, currentPlaybackSec)
            val prompt = """
                You are a helpful video assistant running locally on a phone.
                Answer the user's question concisely based on the video information below.
                
                Video Title: ${videoMeta.title.ifBlank { videoId }}
                Channel: ${videoMeta.author.ifBlank { "Unknown" }}
                Current Playback Timestamp: $timeFormatted
                Description: ${videoMeta.description.take(500)}
                
                Transcript Excerpt:
                $relevantContext
                
                User Question: $question
            """.trimIndent()

            val answer = when {
                ollamaModelName != null -> {
                    queryLocalOllama(ollamaModelName!!, prompt)
                        ?: answerWithBuiltInTranscriptAi(question, videoMeta, currentPlaybackSec)
                }
                mediaPipeLlmInstance != null -> {
                    queryMediaPipeLlm(mediaPipeLlmInstance!!, prompt)
                        ?: answerWithBuiltInTranscriptAi(question, videoMeta, currentPlaybackSec)
                }
                else -> {
                    // Re-check Ollama quickly in case user started it after opening video
                    val freshOllama = checkLocalOllamaModel()
                    if (freshOllama != null) {
                        ollamaModelName = freshOllama
                        mainHandler.post { engineBadge.text = "🟢 Ollama: $freshOllama" }
                        queryLocalOllama(freshOllama, prompt)
                            ?: answerWithBuiltInTranscriptAi(question, videoMeta, currentPlaybackSec)
                    } else {
                        answerWithBuiltInTranscriptAi(question, videoMeta, currentPlaybackSec)
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
                    "No captions available for this video. Here is the description:\n\n${videoMeta.description.take(600)}"
                } else {
                    "No transcript or captions were found for this video."
                }
            )
            return
        }
        val minSec = (currentPlaybackSec - 90).coerceAtLeast(0)
        val maxSec = currentPlaybackSec + 90
        val nearby = cues.filter { it.startSec in minSec..maxSec }.ifEmpty { cues.take(15) }
        val snippet = nearby.take(20).joinToString("\n") { "[${formatSec(it.startSec)}] ${it.text}" }
        appendChatBubble("Transcript around ${formatSec(currentPlaybackSec)}", snippet)
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

    /**
     * Built-in offline extractive Q&A so the user gets immediate answers from the video's
     * transcript and description even without an external LLM loaded.
     */
    private fun answerWithBuiltInTranscriptAi(
        question: String,
        meta: VideoMetadata,
        currentSec: Int
    ): String {
        val qLower = question.lowercase()
        val allCues = meta.cues

        if (allCues.isEmpty() && meta.description.isBlank()) {
            return "Transcript is still loading or unavailable for this video (${meta.title.ifBlank { videoId }}). Try asking again in a moment."
        }

        // Handle "current part" / "right now" queries using playback timestamp
        if (qLower.contains("current") || qLower.contains("right now") || qLower.contains("this part")) {
            if (allCues.isNotEmpty()) {
                val nearby = allCues.filter { abs(it.startSec - currentSec) <= 60 }
                    .ifEmpty { allCues.take(8) }
                val joined = nearby.take(10).joinToString(" ") { it.text }
                return "Around [${formatSec(currentSec)}], the video discusses:\n\"$joined\""
            }
        }

        // Handle summary / takeaway requests
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

                sb.append("• Key moments from transcript:\n")
                for (cue in samples) {
                    sb.append("  [${formatSec(cue.startSec)}] ${cue.text}\n")
                }
            } else if (meta.description.isNotBlank()) {
                sb.append("• Overview: ${meta.description.take(350)}")
            }
            return sb.toString().trim()
        }

        // Keyword matching across transcript chunks
        val stopWords = setOf(
            "what", "when", "where", "which", "who", "whom", "this", "that", "these",
            "those", "does", "about", "video", "explain", "tell", "from", "with", "have"
        )
        val keywords = qLower.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }

        if (allCues.isNotEmpty() && keywords.isNotEmpty()) {
            // Group cues into ~3-line windows and score by keyword overlap + proximity to currentSec
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

        // Fallback to around current timestamp or description
        return if (allCues.isNotEmpty()) {
            val nearCurrent = allCues.filter { abs(it.startSec - currentSec) <= 75 }.ifEmpty { allCues.take(6) }
            "Based on the transcript near [${formatSec(currentSec)}]:\n\"${nearCurrent.take(8).joinToString(" ") { it.text }}\""
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

    /**
     * Finds any `.task` or `.bin` MediaPipe LLM model file in app storage or `/sdcard/Download/`.
     */
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

    /**
     * Uses reflection to initialize `com.google.mediapipe.tasks.genai.llminference.LlmInference`
     * so `Player.kt` compiles even if the user hasn't added the MediaPipe dependency to `build.gradle.kts`.
     */
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

    /**
     * Fetches YouTube video title, author, description, and timestamped captions using
     * YouTube's Innertube player endpoint (with noembed fallback).
     */
    private fun fetchYouTubeContext(vid: String): VideoMetadata {
        var title = ""
        var author = ""
        var description = ""
        var captionUrl: String? = null

        // 1. Try YouTube Innertube API
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

        // 2. Fallback: scrape watch page if Innertube didn't return captionTracks
        if (captionUrl == null || title.isBlank()) {
            try {
                val conn = (URL("https://www.youtube.com/watch?v=$vid&hl=en").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                }
                val html = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val marker = "var ytInitialPlayerResponse = "
                val startIdx = html.indexOf(marker)
                if (startIdx != -1) {
                    val jsonStart = startIdx + marker.length
                    val endIdx = html.indexOf("};", jsonStart)
                    if (endIdx != -1) {
                        val playerJson = JSONObject(html.substring(jsonStart, endIdx + 1))
                        playerJson.optJSONObject("videoDetails")?.let { details ->
                            if (title.isBlank()) title = details.optString("title")
                            if (author.isBlank()) author = details.optString("author")
                            if (description.isBlank()) description = details.optString("shortDescription")
                        }
                        val tracks = playerJson.optJSONObject("captions")
                            ?.optJSONObject("playerCaptionsTracklistRenderer")
                            ?.optJSONArray("captionTracks")
                        if (tracks != null && tracks.length() > 0) {
                            captionUrl = tracks.getJSONObject(0).optString("baseUrl").takeIf { it.isNotBlank() }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback for Title/Author via noembed if still blank
        if (title.isBlank()) {
            try {
                val noembed = URL("https://noembed.com/embed?url=https://www.youtube.com/watch?v=$vid").readText()
                val obj = JSONObject(noembed)
                title = obj.optString("title", "")
                author = obj.optString("author_name", "")
            } catch (_: Exception) {}
        }

        // 4. Download & parse captions
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
        webView.stopLoading()
        webView.destroy()
    }
}