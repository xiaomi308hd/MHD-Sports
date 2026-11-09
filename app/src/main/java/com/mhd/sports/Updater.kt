package com.mhd.sports

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cập nhật MHD Sports theo cơ chế JK Store:
 * GitHub Releases -> tải APK -> thử ADB nội bộ -> fallback trình cài đặt Android.
 */
object Updater {
    private val releaseUrl: String
        get() = "https://api.github.com/repos/${Config.GITHUB_REPO}/releases/latest"

    data class Release(
        val code: Int,
        val name: String,
        val apkUrl: String,
        val notes: String
    )

    fun localCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        )

    /** Đọc GitHub Releases/latest và tìm APK đính kèm của bản phát hành mới nhất. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$releaseUrl?t=${System.currentTimeMillis()}")
            .header("User-Agent", "MHDSports-Updater")
            .header("Accept", "application/vnd.github+json")
            .header("Cache-Control", "no-cache, no-store")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        Net.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) error("GitHub Releases: HTTP ${response.code}")
                val json = JSONObject(response.body?.string()
                    ?: error("GitHub không trả về dữ liệu phát hành"))

                val tag = json.optString("tag_name", "")
                // Chỉ chấp nhận tag do workflow phát hành: build-<versionCode>.
                // Không gom mọi chữ số trong tag vì v1.2.3 sẽ bị hiểu nhầm thành 123.
                val code = Regex("^build-(\\d+)$", RegexOption.IGNORE_CASE)
                    .matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
                    ?: error("Tag Release không hợp lệ: $tag (cần dạng build-88)")

                val assets = json.optJSONArray("assets")
                    ?: error("Bản phát hành chưa có danh sách APK")
                var apkUrl: String? = null
                var apkName: String? = null
                // Ưu tiên tên APK chuẩn của workflow; nếu repo cũ dùng tên khác thì lấy APK đầu tiên.
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (name.equals("MHD-Sports.apk", ignoreCase = true) &&
                        url.startsWith("https://")) {
                        apkName = name
                        apkUrl = url
                        break
                    }
                }
                if (apkUrl == null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val url = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                            apkName = name
                            apkUrl = url
                            break
                        }
                    }
                }
                if (apkUrl == null) return@withContext null
                val releaseName = json.optString("name", "").ifBlank { "MHD Sports $tag" }
                val body = json.optString("body", "").trim()
                Release(code, releaseName, apkUrl!!,
                    if (body.isBlank()) "Đã có phiên bản MHD Sports mới ($apkName)." else body)
            }
    }

    /** Tải APK vào file tạm, kiểm tra chữ ký ZIP/APK cơ bản rồi mới thay file cũ. */
    suspend fun download(
        ctx: Context,
        url: String,
        expectedVersionCode: Int,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Đường dẫn APK không hợp lệ" }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "updates").apply {
            if (!exists() && !mkdirs()) error("Không tạo được thư mục tải APK")
        }
        val file = File(dir, "update.apk")
        val temp = File(dir, "update.apk.tmp")
        temp.delete()
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MHDSports-Updater")
                .header("Accept", "application/vnd.android.package-archive")
                .header("Cache-Control", "no-cache")
                .build()
            Net.client.newBuilder().callTimeout(3, TimeUnit.MINUTES).build()
                .newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Tải APK thất bại: HTTP ${response.code}")
                val body = response.body ?: error("Máy chủ không trả về file APK")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            done += count
                            if (total > 0) {
                                val progress = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (progress != last) {
                                    last = progress
                                    withContext(Dispatchers.Main) { onProgress(progress) }
                                }
                            }
                        }
                        output.flush()
                    }
                }
                if (temp.length() < 2L) error("File APK tải về bị rỗng")
                if (total > 0 && temp.length() != total) error("File APK tải về không đầy đủ")
            }
            temp.inputStream().use {
                if (it.read() != 0x50 || it.read() != 0x4B) error("Tệp tải về không phải APK hợp lệ")
            }
            val archiveInfo = ctx.packageManager.getPackageArchiveInfo(
                temp.absolutePath, android.content.pm.PackageManager.GET_ACTIVITIES
            ) ?: error("Không đọc được thông tin APK tải về")
            if (archiveInfo.packageName != ctx.packageName) {
                error("APK không thuộc ứng dụng ${ctx.packageName}")
            }
            val downloadedCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
            if (downloadedCode != expectedVersionCode.toLong()) {
                error("VersionCode APK ($downloadedCode) không khớp Release ($expectedVersionCode)")
            }
            if (downloadedCode <= localCode(ctx)) {
                error("APK tải về không phải phiên bản mới hơn đang cài")
            }
            if (file.exists() && !file.delete()) error("Không thể thay thế APK cũ")
            if (!temp.renameTo(file)) error("Không thể hoàn tất file APK")
            withContext(Dispatchers.Main) { onProgress(100) }
            file
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /**
     * Thử cài bằng ADB localhost trước. Nếu TV chưa bật/không cho ADB,
     * trả về false để Activity mở trình cài đặt hệ thống.
     */
    private suspend fun installViaAdb(ctx: Context, file: File): Boolean =
        withContext(Dispatchers.IO) {
            var dadb: Dadb? = null
            try {
                val privateKey = File(ctx.filesDir, "adbkey")
                val publicKey = File(ctx.filesDir, "adbkey.pub")
                if (!privateKey.exists() || !publicKey.exists()) {
                    AdbKeyPair.generate(privateKey, publicKey)
                }
                val pair = AdbKeyPair.read(privateKey, publicKey)
                val client = Dadb.create("127.0.0.1", 5555, pair)
                dadb = client
                client.install(file, "-r")
                true
            } catch (_: Throwable) {
                false
            } finally {
                try { dadb?.close() } catch (_: Exception) { }
            }
        }

    /**
     * true = đã cài qua ADB; false = cần mở trình cài đặt hệ thống.
     * Hàm được gọi từ coroutine của Activity.
     */
    suspend fun install(act: Activity, file: File): Boolean {
        require(file.exists() && file.length() > 0L) { "Không tìm thấy file APK hợp lệ" }
        if (installViaAdb(act, file)) return true

        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !act.packageManager.canRequestPackageInstalls()) {
                act.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${act.packageName}"))
                )
                return@withContext
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", file)
            } else Uri.fromFile(file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            act.startActivity(intent)
        }
        return false
    }
}
