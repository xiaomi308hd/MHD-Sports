package com.mhd.sports

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Net {
    const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    // OkHttp tự đi theo các chuyển hướng => link rút gọn của bên thứ 3 vẫn dùng được
    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Client dự phòng CHỈ dùng khi phát video đã thử hết các cách khác: chấp nhận chứng chỉ TLS lỗi/hết hạn,
     * TLS đời cũ và HTTP thường. Không dùng để tải danh sách nguồn.
     */
    val lenientClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        client.newBuilder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .build()
    }

    fun req(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Cache-Control", "no-cache, no-store, max-age=0")
        .header("Pragma", "no-cache")
        .build()

    /** Trả về (link cuối cùng sau khi giải rút gọn, Content-Type). */
    fun resolve(url: String, headers: Map<String, String> = emptyMap(), ua: String = UA): Pair<String, String?> {
        val b = Request.Builder().url(url).header("User-Agent", ua)
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute().use { it.request.url.toString() to it.header("Content-Type") }
    }
}
