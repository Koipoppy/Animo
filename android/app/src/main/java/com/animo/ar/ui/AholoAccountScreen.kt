package com.animo.ar.ui

import android.content.Intent
import android.view.ViewGroup
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.animo.ar.Palette
import com.animo.ar.data.AholoAccount
import java.text.NumberFormat

/**
 * Account entry in Settings. Passwords never pass through the app: login, key creation and the
 * full usage page stay on Aholo's official site in a WebView. Only the signed-in session cookie
 * is used, read-only, to show the same credit balance in the card.
 */
@Composable
fun AholoAccountCard() {
    val context = LocalContext.current
    var balance by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val consoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }

    LaunchedEffect(refresh) {
        val cookie = CookieManager.getInstance().getCookie(AholoAccount.HOST)
        if (cookie.isNullOrBlank()) {
            balance = null
            error = null
            return@LaunchedEffect
        }
        loading = true
        val result = AholoAccount.fetchBalance(cookie)
        loading = false
        result.onSuccess {
            balance = it
            error = null
        }.onFailure {
            balance = null
            error = it.message ?: "余额读取失败"
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            Text("Aholo 账号", color = Palette.Blossom, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Body(
                "登录官方账号，创建 API Key、查看余额。账号密码只在 Aholo 页面输入。",
                size = 11,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton("登录 / 管理 API Key", accent = true) {
                openConsole(context, AholoAccount.CONSOLE_URL, consoleLauncher)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("可用额度", color = Palette.Mist, fontSize = 11.sp)
                    Text(
                        when {
                            loading -> "读取中…"
                            balance != null -> NumberFormat.getIntegerInstance().format(balance)
                            else -> "登录后查看"
                        },
                        color = if (error != null && !error!!.contains("登录")) {
                            Palette.Coral
                        } else {
                            Palette.Cream
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "刷新",
                    color = Palette.Blossom,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { refresh++ }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Text(
                    "用量",
                    color = Palette.Blossom,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { openConsole(context, AholoAccount.USAGE_URL, consoleLauncher) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            if (error != null && !error!!.contains("登录")) {
                Spacer(Modifier.height(2.dp))
                Text(
                    error!!,
                    color = Palette.Coral.copy(alpha = 0.82f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** Full-screen official Aholo console; login and key creation stay on Aholo's own pages. */
@Composable
private fun AholoConsoleScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFFFFFFFF.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url ?: return false
                    val scheme = target.scheme ?: return false
                    if (scheme == "http" || scheme == "https" || scheme == "about") return false
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, target))
                    }
                    return true
                }
            }
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onClose()
    }

    DisposableEffect(webView) {
        onDispose {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B070A))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(
                    Icons.Default.Close,
                    "关闭",
                    46.dp,
                    onClick = onClose,
                    fill = Color(0xCC160B13),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Aholo 账号", color = Palette.Cream, fontSize = 17.sp)
                    Text(
                        "官方页面 · 登录 / API Key / 余额",
                        color = Palette.Mist,
                        fontSize = 11.sp,
                    )
                }
                RoundButton(
                    Icons.Default.Refresh,
                    "刷新页面",
                    46.dp,
                    onClick = { webView.reload() },
                    fill = Color(0xCC160B13),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(WindowInsets.navigationBars.asPaddingValues()),
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            // Loading while the WebView is still 0x0 makes Chromium weld the
                            // CSS viewport to height 0; the site then collapses its login sheet.
                            if (!loaded && coordinates.size.width > 0 && coordinates.size.height > 0) {
                                loaded = true
                                webView.loadUrl(url)
                            }
                        },
                )
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter),
                        color = Palette.Blossom,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}

private fun openConsole(
    context: android.content.Context,
    url: String,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    launcher.launch(
        Intent(context, AholoConsoleActivity::class.java)
            .putExtra(AholoConsoleActivity.EXTRA_URL, url)
    )
}

/**
 * The sign-in page is a full-screen SPA that uses 100dvh; putting its WebView in a Dialog makes
 * Chromium resolve viewport units to 0 on some devices, collapsing the login sheet. A dedicated
 * Activity window gives it a normal viewport.
 */
class AholoConsoleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: AholoAccount.CONSOLE_URL
        setContent {
            AholoConsoleScreen(url = url, onClose = { finish() })
        }
    }

    companion object {
        const val EXTRA_URL = "console_url"
    }
}
