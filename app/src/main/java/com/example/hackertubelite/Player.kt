package com.example.hackertubelite

import android.annotation.SuppressLint
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Implemented by MainActivity so the WebView can go fullscreen (landscape, bars hidden). */
interface FullscreenController {
    fun enter(view: View, callback: WebChromeClient.CustomViewCallback)
    fun exit()
}

val LocalFullscreen = compositionLocalOf<FullscreenController?> { null }

private class WebHolder {
    var web: WebView? = null
}

/**
 * Some phones can't decode YouTube's AV1/VP9 streams and show a black picture with working sound.
 * This tells the player those codecs are unsupported so it serves H.264 instead.
 */
private const val CODEC_SCRIPT =
    "(function(){var ok=function(t){return !(t&&/av01|vp09|vp9|vp8/i.test(t));};" +
        "var ms=window.MediaSource;" +
        "if(ms&&ms.isTypeSupported){var o=ms.isTypeSupported.bind(ms);" +
        "ms.isTypeSupported=function(t){return ok(t)&&o(t);};}" +
        "var p=HTMLMediaElement.prototype.canPlayType;" +
        "HTMLMediaElement.prototype.canPlayType=function(t){return ok(t)?p.call(this,t):'';};})();"

private val ALLOWED_HOSTS = setOf("www.youtube-nocookie.com", "www.youtube.com")

/**
 * YouTube's official embedded player in a locked-down WebView.
 * Only the embed page may load; everything else is blocked.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(videoId: String, modifier: Modifier = Modifier) {
    val fullscreen = LocalFullscreen.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { WebHolder() }

    // Pause playback when the app goes to the background.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> holder.web?.onPause()
                Lifecycle.Event.ON_RESUME -> holder.web?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // No cookies survive after leaving the player.
    DisposableEffect(Unit) {
        onDispose { CookieManager.getInstance().removeAllCookies(null) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                holder.web = this
                settings.javaScriptEnabled = true // required by the YouTube player
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.safeBrowsingEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(
                        this, CODEC_SCRIPT, setOf("https://www.youtube-nocookie.com")
                    )
                }

                webViewClient = object : WebViewClient() {
                    // Returning true blocks the navigation.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        val ok = u.scheme == "https" && u.host in ALLOWED_HOSTS &&
                            (u.path ?: "").startsWith("/embed/")
                        return !ok
                    }
                }
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
                    "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}" +
                    "iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>" +
                    "</head><body>" +
                    "<iframe src='https://www.youtube-nocookie.com/embed/${videoId}?autoplay=1&playsinline=1&rel=0' " +
                    "allow='autoplay; encrypted-media; fullscreen' allowfullscreen></iframe>" +
                    "</body></html>"
                loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
            }
        },
        onRelease = {
            holder.web = null
            it.stopLoading()
            it.destroy()
        },
    )
}