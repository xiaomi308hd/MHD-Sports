package com.mhd.sports

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Net {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    // OkHttp tự đi theo các chuyển hướng => link rút gọn của bên thứ 3 vẫn dùng được
    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun req(url: String): Request = Request.Builder().url(url).header("User-Agent", UA).build()

    /** Trả về (link cuối cùng sau khi giải rút gọn, Content-Type). */
    fun resolve(url: String): Pair<String, String?> =
        client.newCall(req(url)).execute().use { it.request.url.toString() to it.header("Content-Type") }
}
