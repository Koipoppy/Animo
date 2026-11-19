package com.animo.ar.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Read-only bridge to the Aholo web console.
 *
 * Aholo does not publish an account/login/balance API, so the app never handles the password:
 * the user signs in on the official page in a WebView. This client only reuses that WebView
 * session cookie to read the same credit counter the console itself displays.
 */
object AholoAccount {
    const val HOST = "https://labs.aholo.cn"
    const val CONSOLE_URL = "$HOST/api-keys"
    const val USAGE_URL = "$HOST/usage"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchBalance(cookie: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$HOST/aholo/api/credit/remain")
                .header("Accept", "application/json")
                .header("Referer", USAGE_URL)
                .header("Cookie", cookie)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val json = JSONObject(body)
                val code = json.optString("c")
                if (code.isNotEmpty() && code != "0") {
                    if (code == "10003" || code == "10004") {
                        error("请先登录 Aholo 账号")
                    }
                    error(json.optString("m", "余额读取失败"))
                }
                val detail = json.optJSONObject("d")
                when {
                    detail?.has("availableCredits") == true -> detail.optLong("availableCredits")
                    json.has("availableCredits") -> json.optLong("availableCredits")
                    else -> error("没有读取到余额")
                }
            }
        }
    }
}
