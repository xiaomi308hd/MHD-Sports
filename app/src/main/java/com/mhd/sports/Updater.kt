
package com.mhd.sports

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kiểm tra phiên bản từ version.json trên GitHub,
 * tải APK và mở trình cài đặt Android.
 */
object Updater {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/xiaomi308hd/MHD-Sports/main/version.json"

    /** Client riêng để tải APK (~8 MB): timeout rộng hơn client chung vì mạng TV/điện thoại có thể chậm. */
    private val downloadClient by lazy {
        Net.client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class Release(
        val code: Int,
        val name: String,
        val apkUrl: String,
        val notes: String
    )

    /** Lấy versionCode của ứng dụng đang cài. */
    fun localCode(ctx: Context): Long {
        return PackageInfoCompat.getLongVersionCode(
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        )
    }

    /**
     * Đọc version.json từ GitHub.
     * Không cần GitHub Releases.
     */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {

        val request = Request.Builder()
            .url("$VERSION_URL?t=${System.currentTimeMillis()}")
            .header("User-Agent", Net.UA)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .build()

        Net.client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    error("Không đọc được version.json: HTTP ${response.code}")
                }

                val content = response.body?.string()
                    ?: error("GitHub không trả về dữ liệu version.json")

                val json = JSONObject(content)

                val code = json.optInt("versionCode", 0)
                val name = json.optString("versionName", "").trim()
                val apkUrl = json.optString("apkUrl", "").trim()
                val notes = json.optString("notes", "").trim()

                if (code <= 0) {
                    error("versionCode không hợp lệ trong version.json")
                }

                if (name.isBlank()) {
                    error("Thiếu versionName trong version.json")
                }

                if (!apkUrl.startsWith("https://")) {
                    error("apkUrl không hợp lệ hoặc chưa được cấu hình")
                }

                Release(
                    code = code,
                    name = name,
                    apkUrl = apkUrl,
                    notes = notes
                )
            }
    }

    /**
     * Tải APK và báo tiến trình 0-100%.
     */
    suspend fun download(
        ctx: Context,
        url: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {

        require(url.startsWith("https://")) {
            "Đường dẫn APK không hợp lệ"
        }

        val dir = File(
            ctx.getExternalFilesDir(null) ?: ctx.cacheDir,
            "updates"
        ).apply {
            if (!exists() && !mkdirs()) {
                error("Không tạo được thư mục tải APK")
            }
        }

        val file = File(dir, "update.apk")
        val tempFile = File(dir, "update.apk.tmp")

        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                return@withContext downloadOnce(url, dir, file, tempFile, onProgress)
            } catch (e: Exception) {
                lastError = e
                tempFile.delete()
                if (attempt < 3) kotlinx.coroutines.delay(1500L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("Tải APK thất bại")
    }

    private suspend fun downloadOnce(
        url: String,
        dir: File,
        file: File,
        tempFile: File,
        onProgress: (Int) -> Unit
    ): File {
        if (tempFile.exists()) tempFile.delete()

        try {
            downloadClient.newCall(Net.req(url)).execute().use { response ->

                if (!response.isSuccessful) {
                    error("Tải APK thất bại: HTTP ${response.code}")
                }

                val body = response.body
                    ?: error("Máy chủ không trả về file APK")

                val total = body.contentLength()

                body.byteStream().use { input ->
                    tempFile.outputStream().buffered().use { output ->

                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastProgress = -1

                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break

                            output.write(buffer, 0, count)
                            downloaded += count

                            if (total > 0) {
                                val progress =
                                    (downloaded * 100 / total)
                                        .toInt()
                                        .coerceIn(0, 100)

                                if (progress != lastProgress) {
                                    lastProgress = progress

                                    withContext(Dispatchers.Main) {
                                        onProgress(progress)
                                    }
                                }
                            }
                        }

                        output.flush()
                    }
                }

                if (tempFile.length() <= 0L) {
                    error("File APK tải về bị rỗng")
                }

                if (total > 0 && tempFile.length() != total) {
                    error("File APK tải về không đầy đủ")
                }
            }

            if (file.exists() && !file.delete()) {
                error("Không thể thay thế file APK cũ")
            }

            if (!tempFile.renameTo(file)) {
                error("Không thể hoàn tất file APK")
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
            }

            return file

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * Mở trình cài đặt APK.
     * Trả về false nếu cần cấp quyền cài đặt ứng dụng
     * từ nguồn không xác định.
     */
    fun install(act: Activity, file: File): Boolean {

        if (!file.exists() || file.length() <= 0L) {
            error("Không tìm thấy file APK hợp lệ")
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !act.packageManager.canRequestPackageInstalls()
        ) {
            act.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${act.packageName}")
                )
            )

            return false
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                act,
                "${act.packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                "application/vnd.android.package-archive"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            act.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            error("Máy không có trình cài đặt APK. Hãy dùng nút “Mở bằng trình duyệt”.")
        }

        return true
    }

    /** Dự phòng: giao link APK cho trình duyệt / trình tải của hệ thống. */
    fun openInBrowser(act: Activity, url: String) {
        act.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
