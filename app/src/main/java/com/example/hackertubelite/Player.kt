package com.example.hackertubelite

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Implemented by MainActivity so the WebView can go fullscreen (landscape, bars hidden). */
interface FullscreenController {
    fun enter(view: View, callback: WebChromeClient.CustomViewCallback)
    fun exit()
}

val LocalFullscreen = compositionLocalOf<FullscreenController?> { null }

/**
 * TEST BUILD: minimal YouTube embed with no extra hardening, to find what broke the picture.
 * The grey background and "Loading player..." text show whether the page paints at all.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(videoId: String, modifier: Modifier = Modifier) {
    val fullscreen = LocalFullscreen.current

    AndroidView(
        modifier = modifier,
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
                    "<style>html,body{margin:0;height:100%;background:#222;color:#aaa;font:14px sans-serif}" +
                    "p{position:absolute;top:45%;width:100%;text-align:center;margin:0}" +
                    "iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>" +
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
}