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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.remember
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/** Implemented by MainActivity so the WebView can go fullscreen (landscape, bars hidden). */
interface FullscreenController {
    fun enter(view: View, callback: WebChromeClient.CustomViewCallback)
    fun exit()
}

val LocalFullscreen = compositionLocalOf<FullscreenController?> { null }

private data class TranscriptCue(val startSec: Int, val text: String)

private data class TranscriptPassage(val startSec: Int, val endSec: Int, val text: String)

private data class VideoMetadata(
    val videoId: String,
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val cues: List<TranscriptCue> = emptyList(),
    val passages: List<TranscriptPassage> = emptyList()
)

private class SharedPlayerClock {
    val currentSec = AtomicInteger(0)
    val isLiveSynced = AtomicBoolean(false)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(videoId: String, modifier: Modifier = Modifier) {
    val fullscreen = LocalFullscreen.current

    key(videoId) {
        val sharedClock = remember(videoId) { SharedPlayerClock() }

        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. TOP: Working 16:9 YouTube Player WebView with Live Timestamp Bridge
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onTime(seconds: Double) {
                                if (!seconds.isNaN() && seconds >= 0.0) {
                                    sharedClock.currentSec.set(seconds.toInt())
                                    sharedClock.isLiveSynced.set(true)
                                }
                            }
                        }, "AndroidPlayerBridge")

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
                            "<iframe id='ytplayer' src='https://www.youtube-nocookie.com/embed/${videoId}?autoplay=1&playsinline=1&rel=0&enablejsapi=1' " +
                            "allow='autoplay; encrypted-media; fullscreen' allowfullscreen></iframe>" +
                            "<script src='https://www.youtube.com/iframe_api'></script>" +
                            "<script>" +
                            "var ytPlayerObj = null;" +
                            "function onYouTubeIframeAPIReady() {" +
                            "  try {" +
                            "    ytPlayerObj = new YT.Player('ytplayer', {" +
                            "      events: {" +
                            "        'onReady': function(e) {" +
                            "          setInterval(function() {" +
                            "            try {" +
                            "              if (ytPlayerObj && ytPlayerObj.getCurrentTime) {" +
                            "                AndroidPlayerBridge.onTime(ytPlayerObj.getCurrentTime());" +
                            "              }" +
                            "            } catch(err) {}" +
                            "          }, 500);" +
                            "        }" +
                            "      }" +
                            "    });" +
                            "  } catch(e) {}" +
                            "}" +
                            "(function(){" +
                            "  var f = document.getElementById('ytplayer');" +
                            "  function subscribeWidget() {" +
                            "    try {" +
                            "      f.contentWindow.postMessage(JSON.stringify({event:'listening',id:'ytplayer',channel:'widget'}),'*');" +
                            "    } catch(e) {}" +
                            "  }" +
                            "  f.addEventListener('load', function(){ subscribeWidget(); setInterval(subscribeWidget, 1000); });" +
                            "  window.addEventListener('message', function(e) {" +
                            "    try {" +
                            "      var d = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;" +
                            "      if (d && d.info && typeof d.info.currentTime === 'number') {" +
                            "        AndroidPlayerBridge.onTime(d.info.currentTime);" +
                            "      }" +
                            "    } catch(err) {}" +
                            "  });" +
                            "})();" +
                            "</script>" +
                            "</body></html>"
                        loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
                    }
                },
                onRelease = {
                    it.stopLoading()
                    it.destroy()
                }
            )

            // 2. BOTTOM: Local AI Panel synced with the video player
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    LocalAiPanelView(ctx, videoId, sharedClock)
                },
                onRelease = {
                    it.cleanup()
                }
            )
        }
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetJavaScriptEnabled")
private class LocalAiPanelView(
    context: Context,
    private val videoId: String,
    private val playerClock: SharedPlayerClock
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val fetchLock = Any()

    private val timeBadge: TextView
    private val engineBadge: TextView
    private val statusText: TextView
    private val chatContainer: LinearLayout
    private val chatScroll: ScrollView
    private val questionInput: EditText
    private val askButton: TextView

    private var wasmAiWebView: WebView? = null

    @Volatile
    private var isWasmAiReady = false

    @Volatile
    private var isWasmAiLoading = false

    private var activeWasmLatch: CountDownLatch? = null
    private val latestWasmResult = AtomicReference<String?>(null)

    @Volatile
    private var videoMeta = VideoMetadata(videoId = videoId)

    @Volatile
    private var isFetchingMeta = true

    @Volatile
    private var mediaPipeLlmInstance: Any? = null

    @Volatile
    private var ollamaModelName: String? = null

    private val clockTicker = object : Runnable {
        override fun run() {
            val sec = playerClock.currentSec.get()
            val synced = playerClock.isLiveSynced.get()
            timeBadge.text = "⏱️ ${formatSec(sec)}"
            timeBadge.setTextColor(
                if (synced) Color.parseColor("#00E676") else Color.parseColor("#B0BEC5")
            )
            mainHandler.postDelayed(this, 500L)
        }
    }

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
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val headerTitle = TextView(context).apply {
            text = "🤖 Local Video AI"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        timeBadge = TextView(context).apply {
            text = "⏱️ 00:00"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg("#1A2621", dp(12), strokeColor = "#2E4C3E")
            val lp = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(6)
            layoutParams = lp
        }

        engineBadge = TextView(context).apply {
            text = "⏳ Loading AI..."
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg("#1E272C", dp(12))
            setOnClickListener {
                if (!isWasmAiReady && !isWasmAiLoading) {
                    initOnDeviceWasmLlm()
                } else {
                    detectLocalAiBackends(showToastInChat = true)
                }
            }
        }

        headerRow.addView(headerTitle)
        headerRow.addView(timeBadge)
        headerRow.addView(engineBadge)
        addView(headerRow)

        // Status Line
        statusText = TextView(context).apply {
            text = "⏳ Fetching video transcript & captions..."
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener {
                text = "🔄 Reloading transcript..."
                loadVideoMetadataAndTranscript(forceRetry = true)
            }
        }
        addView(statusText)

        // Quick Prompt Chips
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        val quickPrompts = listOf(
            "🎯 Explain Current Part" to "What is the speaker explaining right now at this moment in the video?",
            "⚡ Summarize Video" to "Summarize the main topic and core points of this video.",
            "📌 Key Takeaways" to "What are the main takeaways and conclusions from this video?",
            "📜 Live Transcript" to "__SHOW_TRANSCRIPT__"
        )

        for ((label, promptText) in quickPrompts) {
            val chip = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#E0E0E0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedBg("#263238", dp(16), strokeColor = "#37474F")
                val lp = LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
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

        // Scrollable Chat Box
        chatScroll = ScrollView(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
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
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chatScroll.addView(chatContainer)
        addView(chatScroll)

        // Input Row
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        questionInput = EditText(context).apply {
            hint = "Ask anything about this video..."
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
            val lp = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
            message = "Synced with the video player! Ask any question below or tap '🎯 Explain Current Part' while watching."
        )

        mainHandler.post(clockTicker)
        loadVideoMetadataAndTranscript(forceRetry = false)
        detectLocalAiBackends(showToastInChat = false)
        initOnDeviceWasmLlm()
    }

    private fun initOnDeviceWasmLlm() {
        if (isWasmAiReady || isWasmAiLoading) return
        isWasmAiLoading = true

        mainHandler.post {
            try {
                val wv = WebView(context).apply {
                    layoutParams = LayoutParams(1, 1)
                    visibility = View.GONE
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onProgress(status: String) {
                            mainHandler.post {
                                if (ollamaModelName == null && mediaPipeLlmInstance == null && !isWasmAiReady) {
                                    engineBadge.text = status
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onReady() {
                            isWasmAiReady = true
                            isWasmAiLoading = false
                            mainHandler.post {
                                if (ollamaModelName == null && mediaPipeLlmInstance == null) {
                                    engineBadge.text = "🟢 Local Neural AI"
                                    engineBadge.setTextColor(Color.parseColor("#00E676"))
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onError(err: String) {
                            isWasmAiLoading = false
                            mainHandler.post {
                                if (ollamaModelName == null && mediaPipeLlmInstance == null) {
                                    engineBadge.text = "⚡ Smart Transcript AI"
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onAnswer(text: String) {
                            latestWasmResult.set(text.trim())
                            activeWasmLatch?.countDown()
                        }
                    }, "LocalNeuralBridge")

                    webViewClient = WebViewClient()

                    val aiHtml = """
                        <!DOCTYPE html>
                        <html><head><meta charset="utf-8"></head><body>
                        <script type="module">
                        import { pipeline, env } from 'https://cdn.jsdelivr.net/npm/@xenova/transformers@2.17.2';
                        env.allowLocalModels = false;
                        env.useBrowserCache = true;

                        let generator = null;

                        async function boot() {
                            try {
                                LocalNeuralBridge.onProgress("⏳ Loading AI...");
                                generator = await pipeline('text2text-generation', 'Xenova/LaMini-Flan-T5-77M', {
                                    progress_callback: (p) => {
                                        if (p && p.status === 'progress' && typeof p.progress === 'number') {
                                            LocalNeuralBridge.onProgress("📥 AI " + Math.round(p.progress) + "%");
                                        }
                                    }
                                });
                                LocalNeuralBridge.onReady();
                            } catch (e) {
                                LocalNeuralBridge.onError(String(e));
                            }
                        }

                        window.runLocalInference = async function(promptText) {
                            if (!generator) {
                                LocalNeuralBridge.onAnswer("");
                                return;
                            }
                            try {
                                const out = await generator(promptText, {
                                    max_new_tokens: 140,
                                    temperature: 0.2,
                                    repetition_penalty: 1.3
                                });
                                const ans = (out && out[0] && out[0].generated_text) ? out[0].generated_text : "";
                                LocalNeuralBridge.onAnswer(ans);
                            } catch (err) {
                                LocalNeuralBridge.onAnswer("");
                            }
                        };

                        boot();
                        </script>
                        </body></html>
                    """.trimIndent()

                    loadDataWithBaseURL("https://huggingface.co", aiHtml, "text/html", "utf-8", null)
                }
                wasmAiWebView = wv
                addView(wv)
            } catch (_: Throwable) {
                isWasmAiLoading = false
            }
        }
    }

    private fun queryWasmNeuralLlm(prompt: String): String? {
        if (!isWasmAiReady || wasmAiWebView == null) return null
        return try {
            val latch = CountDownLatch(1)
            activeWasmLatch = latch
            latestWasmResult.set(null)
            val escapedPrompt = JSONObject.quote(prompt)
            mainHandler.post {
                wasmAiWebView?.evaluateJavascript("window.runLocalInference($escapedPrompt);", null)
            }
            latch.await(25, TimeUnit.SECONDS)
            latestWasmResult.get()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
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
            cueCount > 0 -> "📺 $titleDisplay\n✅ $cueCount caption lines synced (${meta.passages.size} segments)"
            meta.description.isNotBlank() -> "📺 $titleDisplay\nℹ️ Using description (${meta.description.length} chars) — tap to retry captions"
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
                    engineBadge.setTextColor(Color.parseColor("#00E676"))
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
                            engineBadge.text = "🟢 On-Device: ${modelFile.name.take(12)}"
                            engineBadge.setTextColor(Color.parseColor("#00E676"))
                            if (showToastInChat) {
                                appendChatBubble("System", "Loaded MediaPipe model: ${modelFile.name}")
                            }
                        }
                        return@execute
                    }
                }
            } else {
                return@execute
            }

            mainHandler.post {
                if (isWasmAiReady) {
                    engineBadge.text = "🟢 Local Neural AI"
                    engineBadge.setTextColor(Color.parseColor("#00E676"))
                    if (showToastInChat) {
                        appendChatBubble("System", "On-Device Neural AI (LaMini-Flan-T5) is active.")
                    }
                } else if (showToastInChat) {
                    appendChatBubble(
                        "System",
                        "Status:\n• On-Device Neural AI: ${if (isWasmAiLoading) "Loading model..." else "Tap badge to retry"}\n" +
                            "• Video Clock: ${if (playerClock.isLiveSynced.get()) "Live synced (${formatSec(playerClock.currentSec.get())})" else "Waiting for playback"}"
                    )
                }
            }
        }
    }

    private fun submitQuestion(question: String) {
        val currentSec = playerClock.currentSec.get()
        val timeFormatted = formatSec(currentSec)
        appendChatBubble("You ($timeFormatted)", question, isUser = true)
        val thinkingView = appendChatBubble("AI", "Analyzing video transcript at $timeFormatted...")

        executor.execute {
            val meta = if (videoMeta.cues.isEmpty()) ensureMetadataLoaded(forceRetry = true) else videoMeta
            mainHandler.post { updateStatusBanner(meta) }

            val livePassage = getLivePlaybackPassage(meta, currentSec)
            val relevantPassages = retrieveRelevantPassages(question, meta, currentSec, topK = 3)

            // Keep context concise (~320 words max) and put Question FIRST so compact local models never truncate the question
            val compactTranscriptContext = buildString {
                if (livePassage.isNotBlank()) {
                    append("Current playback [$timeFormatted]: ${livePassage.take(450)}\n")
                }
                for (p in relevantPassages) {
                    append("[${formatSec(p.startSec)}]: ${p.text.take(350)}\n")
                }
                if (isEmpty() && meta.description.isNotBlank()) {
                    append(meta.description.take(700))
                }
            }.trim()

            val neuralPrompt = """
                Question: $question
                Answer the question above using this transcript from "${meta.title.ifBlank { videoId }}" by ${meta.author.ifBlank { "the speaker" }}:
                $compactTranscriptContext
                Answer:
            """.trimIndent()

            val neuralAnswer = when {
                ollamaModelName != null -> queryLocalOllama(ollamaModelName!!, neuralPrompt)
                mediaPipeLlmInstance != null -> queryMediaPipeLlm(mediaPipeLlmInstance!!, neuralPrompt)
                isWasmAiReady -> queryWasmNeuralLlm(neuralPrompt)
                else -> null
            }

            val finalAnswer = if (!neuralAnswer.isNullOrBlank() && neuralAnswer.length > 12) {
                val timestamps = relevantPassages.take(2).joinToString(", ") { formatSec(it.startSec) }
                if (timestamps.isNotEmpty()) {
                    "$neuralAnswer\n\n(Referenced at $timestamps)"
                } else {
                    neuralAnswer
                }
            } else {
                synthesizeAnswerFromTranscript(question, meta, currentSec, livePassage, relevantPassages)
            }

            mainHandler.post {
                thinkingView.text = "AI: $finalAnswer"
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun getLivePlaybackPassage(meta: VideoMetadata, currentSec: Int): String {
        if (meta.cues.isEmpty()) return ""
        val window = meta.cues.filter { it.startSec in (currentSec - 30)..(currentSec + 30) }
            .ifEmpty { meta.cues.take(12) }
        return cleanSpokenText(window.joinToString(" ") { it.text })
    }

    private fun retrieveRelevantPassages(
        question: String,
        meta: VideoMetadata,
        currentSec: Int,
        topK: Int
    ): List<TranscriptPassage> {
        if (meta.passages.isEmpty()) return emptyList()
        val qLower = question.lowercase()

        if (qLower.contains("current") || qLower.contains("right now") || qLower.contains("this moment") || qLower.contains("this part")) {
            return meta.passages.sortedBy { abs(it.startSec - currentSec) }.take(2)
        }

        if (qLower.contains("summar") || qLower.contains("takeaway") || qLower.contains("overview")) {
            val total = meta.passages.size
            val indices = listOf(0, total / 4, total / 2, (total * 3) / 4, total - 1)
                .distinct()
                .filter { it in meta.passages.indices }
            return indices.map { meta.passages[it] }
        }

        val stopWords = setOf(
            "what", "when", "where", "which", "who", "whom", "this", "that", "these",
            "those", "does", "about", "video", "explain", "tell", "from", "with", "have",
            "how", "why", "can", "you", "the", "and", "for", "are", "was", "were", "speaker", "talk"
        )

        val queryTokens = qLower.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in stopWords }
            .distinct()

        if (queryTokens.isEmpty()) {
            return meta.passages.sortedBy { abs(it.startSec - currentSec) }.take(topK)
        }

        val scored = meta.passages.map { passage ->
            val pLower = passage.text.lowercase()
            var score = 0.0
            for (token in queryTokens) {
                if (pLower.contains(token)) {
                    score += 2.5
                } else if (token.length >= 5 && pLower.contains(token.take(4))) {
                    score += 1.2
                }
            }
            if (abs(passage.startSec - currentSec) <= 90) {
                score += 0.8
            }
            passage to score
        }.sortedByDescending { it.second }

        return scored.filter { it.second > 0.0 }.take(topK).map { it.first }.ifEmpty {
            meta.passages.sortedBy { abs(it.startSec - currentSec) }.take(2)
        }
    }

    private fun synthesizeAnswerFromTranscript(
        question: String,
        meta: VideoMetadata,
        currentSec: Int,
        livePassage: String,
        relevantPassages: List<TranscriptPassage>
    ): String {
        val qLower = question.lowercase()

        if (meta.cues.isEmpty() && meta.description.isBlank()) {
            return "Captions are still loading for this video. Tap the status line above to reload."
        }

        if (qLower.contains("current") || qLower.contains("right now") || qLower.contains("this moment") || qLower.contains("this part")) {
            if (livePassage.isNotBlank()) {
                return "At [${formatSec(currentSec)}], ${meta.author.ifBlank { "the speaker" }} is explaining:\n\n$livePassage"
            }
        }

        if (qLower.contains("summar") || qLower.contains("takeaway") || qLower.contains("overview")) {
            val sb = StringBuilder()
            sb.append("Summary of \"${meta.title.ifBlank { videoId }}\"")
            if (meta.author.isNotBlank()) sb.append(" by ${meta.author}")
            sb.append(":\n\n")
            if (relevantPassages.isNotEmpty()) {
                for ((idx, p) in relevantPassages.take(4).withIndex()) {
                    sb.append("${idx + 1}. [${formatSec(p.startSec)}] ${summarizeParagraph(p.text, 2)}\n\n")
                }
            } else if (meta.description.isNotBlank()) {
                sb.append(meta.description.take(500))
            }
            return sb.toString().trim()
        }

        if (relevantPassages.isNotEmpty()) {
            val primary = relevantPassages.first()
            val supporting = relevantPassages.drop(1).take(2)
            val sb = StringBuilder()
            sb.append("In the talk at [${formatSec(primary.startSec)}], ${meta.author.ifBlank { "the speaker" }} explains:\n")
            sb.append(summarizeParagraph(primary.text, 3))
            if (supporting.isNotEmpty()) {
                sb.append("\n\nAdditional context (${supporting.joinToString(", ") { formatSec(it.startSec) }}):\n")
                sb.append(supporting.joinToString("\n• ", prefix = "• ") { summarizeParagraph(it.text, 2) })
            }
            return sb.toString().trim()
        }

        return if (livePassage.isNotBlank()) {
            "Around [${formatSec(currentSec)}], the speaker discusses:\n$livePassage"
        } else {
            meta.description.take(450).ifBlank { "No matching transcript lines found." }
        }
    }

    private fun cleanSpokenText(raw: String): String {
        val cleaned = raw
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\b(um|uh|er|ah)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.isEmpty()) "" else cleaned.replaceFirstChar { it.uppercase() }
    }

    private fun summarizeParagraph(text: String, maxSentences: Int): String {
        val clean = cleanSpokenText(text)
        val sentences = clean.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val result = if (sentences.size <= maxSentences) clean else sentences.take(maxSentences).joinToString(" ")
        return if (result.endsWith(".") || result.endsWith("?") || result.endsWith("!")) result else "$result."
    }

    private fun showCurrentTranscriptExcerpt() {
        val currentSec = playerClock.currentSec.get()
        val loadingBubble = appendChatBubble("AI", "Fetching transcript around ${formatSec(currentSec)}...")
        executor.execute {
            val meta = if (videoMeta.cues.isEmpty()) ensureMetadataLoaded(forceRetry = true) else videoMeta
            val resultText = if (meta.cues.isNotEmpty()) {
                val minSec = (currentSec - 45).coerceAtLeast(0)
                val maxSec = currentSec + 75
                val nearby = meta.cues.filter { it.startSec in minSec..maxSec }.ifEmpty { meta.cues.take(18) }
                "Live Transcript around [${formatSec(currentSec)}]:\n" +
                    nearby.take(22).joinToString("\n") { cue ->
                        val pointer = if (abs(cue.startSec - currentSec) <= 4) "▶ " else "   "
                        "$pointer[${formatSec(cue.startSec)}] ${cue.text}"
                    }
            } else if (meta.description.isNotBlank()) {
                "No closed captions found; video description:\n\n${meta.description.take(700)}"
            } else {
                "Could not load captions for ${meta.title.ifBlank { videoId }}. Tap the status bar above to retry."
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
            val lp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
        chatContainer.addView(bubble)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    private fun fetchYouTubeContext(vid: String): VideoMetadata {
        var title = ""
        var author = ""
        var description = ""
        val captionCandidates = mutableListOf<Pair<String, String>>()

        data class ClientSpec(val name: String, val version: String, val ua: String, val extraJson: String)

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

        if (captionCandidates.isEmpty() || description.isBlank()) {
            try {
                val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                val html = httpGet("https://www.youtube.com/watch?v=$vid&hl=en", desktopUa)
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

        var parsedCues = emptyList<TranscriptCue>()
        for ((rawUrl, ua) in captionCandidates) {
            parsedCues = downloadAndParseCaptions(rawUrl, ua)
            if (parsedCues.isNotEmpty()) break
        }

        if (parsedCues.isEmpty() || description.isBlank()) {
            val pipedHosts = listOf("https://pipedapi.kavin.rocks", "https://api.piped.private.coffee")
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
            cues = parsedCues,
            passages = buildPassagesFromCues(parsedCues)
        )
    }

    private fun buildPassagesFromCues(cues: List<TranscriptCue>): List<TranscriptPassage> {
        if (cues.isEmpty()) return emptyList()
        val result = mutableListOf<TranscriptPassage>()
        var chunkStart = cues.first().startSec
        var lastSec = chunkStart
        val sb = StringBuilder()

        for (cue in cues) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(cue.text)
            lastSec = cue.startSec
            if (cue.startSec - chunkStart >= 25 || sb.length >= 280) {
                result.add(TranscriptPassage(chunkStart, lastSec, cleanSpokenText(sb.toString())))
                sb.clear()
                chunkStart = cue.startSec
            }
        }
        if (sb.isNotEmpty()) {
            result.add(TranscriptPassage(chunkStart, lastSec, cleanSpokenText(sb.toString())))
        }
        return result
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
        val json3Url = if (baseUrl.contains("fmt=")) {
            baseUrl.replace(Regex("fmt=[^&]+"), "fmt=json3")
        } else {
            "$baseUrl&fmt=json3"
        }
        for (url in listOf(json3Url, baseUrl).distinct()) {
            val raw = httpGet(url, userAgent) ?: continue
            val parsed = parseCaptionPayload(raw)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun parseCaptionPayload(payload: String): List<TranscriptCue> {
        val trimmed = payload.trimStart()
        if (trimmed.isEmpty()) return emptyList()
        val cues = mutableListOf<TranscriptCue>()

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

        val srv3Regex = Regex("""<p\s+[^>]*\bt="(\d+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        for (match in srv3Regex.findAll(trimmed)) {
            val startMs = match.groupValues[1].toLongOrNull() ?: 0L
            val innerHtml = match.groupValues[2].replace(Regex("<[^>]+>"), "")
            val decoded = Html.fromHtml(innerHtml, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            if (decoded.isNotEmpty()) {
                cues.add(TranscriptCue((startMs / 1000L).toInt(), decoded))
            }
        }
        if (cues.isNotEmpty()) return cues

        val srv1Regex = Regex("""<text\s+[^>]*\bstart="([\d.]+)"[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
        for (match in srv1Regex.findAll(trimmed)) {
            val startSec = match.groupValues[1].toDoubleOrNull()?.toInt() ?: 0
            val innerHtml = match.groupValues[2].replace(Regex("<[^>]+>"), "")
            val decoded = Html.fromHtml(innerHtml, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            if (decoded.isNotEmpty()) {
                cues.add(TranscriptCue(startSec, decoded))
            }
        }
        if (cues.isNotEmpty()) return cues

        if (trimmed.startsWith("WEBVTT")) {
            val timeRegex = Regex("""(?:(\d+):)?(\d{2}):(\d{2})[.,]\d+\s*-->""")
            var currentSec = -1
            val textBuf = StringBuilder()
            for (line in trimmed.lines()) {
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
                    if (depth == 0) return source.substring(startBracket, i + 1)
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
            val builder = optionsClass.getMethod("builder").invoke(null)
            val builderClass = builder.javaClass
            builderClass.getMethod("setModelPath", String::class.java).invoke(builder, modelFile.absolutePath)
            try {
                builderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType).invoke(builder, 512)
            } catch (_: Exception) {}
            val options = builderClass.getMethod("build").invoke(builder)
            llmClass.getMethod("createFromOptions", Context::class.java, optionsClass).invoke(null, ctx, options)
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
        mainHandler.removeCallbacks(clockTicker)
        try {
            wasmAiWebView?.stopLoading()
            wasmAiWebView?.destroy()
        } catch (_: Throwable) {}
        wasmAiWebView = null
        try {
            mediaPipeLlmInstance?.let { instance ->
                instance.javaClass.getMethod("close").invoke(instance)
            }
        } catch (_: Throwable) {}
        mediaPipeLlmInstance = null
        executor.shutdownNow()
    }
}