package com.animo.ar.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.animo.ar.Palette
import java.io.File
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Local 3D preview: one WebView + the packaged <model-viewer> for the whole management screen.
 * Selecting another pet only changes the GLB src through JS; the WebView/JS/GL context is reused,
 * so page switches and pet switches do not pay the WebView start-up cost again.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ModelPreview(
    modelFile: File,
    modifier: Modifier = Modifier,
    autoRotate: Boolean = true,
    onLoaded: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    var pageReady by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val webView = remember {
        createViewer(
            context = context,
            autoRotate = autoRotate,
            onPageReady = { pageReady = true },
            onLoaded = {
                loaded = true
                currentOnLoaded()
            },
            onError = { failed = true },
        )
    }

    LaunchedEffect(modelFile.path, modelFile.length(), pageReady) {
        if (!pageReady) return@LaunchedEffect
        loaded = false
        failed = false
        val encoded = URLEncoder.encode(modelFile.name, "UTF-8")
        val url = "https://appassets.androidplatform.net/pets/$encoded"
        webView.evaluateJavascript("window.loadModel(${JSONObject.quote(url)});", null)
    }

    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    Box(modifier) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        if (failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("预览加载失败", color = Palette.Mist, fontSize = 12.sp)
            }
        } else if (!loaded) {
            LoadingPreview(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun LoadingPreview(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "preview-loading")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "preview-loading-alpha",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "正在载入手办…",
            color = Palette.Mist.copy(alpha = alpha),
            fontSize = 12.sp,
        )
    }
}

private fun createViewer(
    context: Context,
    autoRotate: Boolean,
    onPageReady: () -> Unit,
    onLoaded: () -> Unit,
    onError: (String) -> Unit,
): WebView {
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler(
            "/pets/",
            WebViewAssetLoader.InternalStoragePathHandler(context, File(context.filesDir, "pets")),
        )
        .build()

    return WebView(context).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false

        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onModelLoaded() = post { onLoaded() }

                @JavascriptInterface
                fun onModelError(message: String) = post { onError(message) }
            },
            "AnimoBridge",
        )

        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = request.url.host != "appassets.androidplatform.net"

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                post { onPageReady() }
            }
        }

        val rotate = if (autoRotate) "1" else "0"
        loadUrl("https://appassets.androidplatform.net/assets/viewer.html?rotate=$rotate")
    }
}
