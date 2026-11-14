package com.example.hackertubelite

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Optional on-device language model (LaMini-Flan-T5-77M via transformers.js) in a hidden WebView.
 * OFF unless the user turns it on in Settings. When on, the first start downloads the model
 * (about 100 MB) from Hugging Face and loads transformers.js from jsDelivr. Questions and video
 * text are processed on the phone and never uploaded.
 */
object NeuralEngine {
    var status by mutableStateOf("Off")
        private set
    var ready by mutableStateOf(false)
        private set

    private var webView: WebView? = null
    private var pending: CompletableDeferred<String>? = null
    private val main = Handler(Looper.getMainLooper())

    private const val PAGE =
        "<html><head><meta charset='utf-8'></head><body>" +
            "<script type='module'>" +
            "import { pipeline, env } from 'https://cdn.jsdelivr.net/npm/@xenova/transformers@2.17.2';" +
            "env.allowLocalModels = false; env.useBrowserCache = true;" +
            "let gen = null;" +
            "async function boot() { try {" +
            "NeuralBridge.onProgress('Loading model...');" +
            "gen = await pipeline('text2text-generation', 'Xenova/LaMini-Flan-T5-77M', {" +
            "progress_callback: function(p) { if (p && p.status === 'progress' && typeof p.progress === 'number') {" +
            "NeuralBridge.onProgress('Downloading ' + Math.round(p.progress) + '%'); } } });" +
            "NeuralBridge.onReady();" +
            "} catch (e) { NeuralBridge.onError(String(e)); } }" +
            "window.runLocal = async function(t) {" +
            "if (!gen) { NeuralBridge.onAnswer(''); return; }" +
            "try { const o = await gen(t, { max_new_tokens: 140, repetition_penalty: 1.3 });" +
            "NeuralBridge.onAnswer((o && o[0] && o[0].generated_text) || '');" +
            "} catch (e) { NeuralBridge.onAnswer(''); } };" +
            "boot();" +
            "</script></body></html>"

    private class Bridge {
        @JavascriptInterface
        fun onProgress(s: String) {
            NeuralEngine.main.post { NeuralEngine.status = s.take(40) }
        }

        @JavascriptInterface
        fun onReady() {
            NeuralEngine.main.post {
                NeuralEngine.ready = true
                NeuralEngine.status = "Ready"
            }
        }

        @JavascriptInterface
        fun onError(s: String) {
            NeuralEngine.main.post {
                NeuralEngine.ready = false
                NeuralEngine.status = "Unavailable (needs internet on first use)"
            }
        }

        @JavascriptInterface
        fun onAnswer(s: String) {
            NeuralEngine.pending?.complete(s)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start(context: Context) {
        if (webView != null) return
        status = "Starting..."
        val wv = WebView(context.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        wv.addJavascriptInterface(Bridge(), "NeuralBridge")
        wv.webViewClient = WebViewClient()
        wv.loadDataWithBaseURL("https://huggingface.co", PAGE, "text/html", "utf-8", null)
        webView = wv
    }

    fun stop() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        ready = false
        status = "Off"
    }

    /** Returns the model's answer, or null if it isn't ready, fails or times out. */
    suspend fun ask(prompt: String): String? {
        val wv = webView ?: return null
        if (!ready) return null
        val result = CompletableDeferred<String>()
        pending = result
        withContext(Dispatchers.Main) {
            wv.evaluateJavascript("window.runLocal(" + JSONObject.quote(prompt) + ");", null)
        }
        val text = withTimeoutOrNull(40_000L) { result.await() }
        return text?.trim()?.takeIf { it.isNotBlank() }
    }
}
