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

/** Kiểm tra bản mới trong GitHub Releases, tải APK và mở trình cài đặt. */
object Updater {
    data class Release(val code: Int, val name: String, val apkUrl: String, val notes: String)

    fun localCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        if (Config.GITHUB_REPO.isBlank() || Config.GITHUB_REPO.startsWith("your-name"))
            error("Chưa cài GITHUB_REPO trong Models.kt")
        val req = Request.Builder()
            .url("https://api.github.com/repos/${Config.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MHDSports")
            .header("Cache-Control", "no-cache")
            .build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GitHub trả về mã ${r.code} (repo cần để Public và đã có bản phát hành)")
            val j = JSONObject(r.body?.string() ?: "{}")
            val tag = j.optString("tag_name")
            val code = Regex("""(\d+)\s*$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@use null
            val assets = j.optJSONArray("assets") ?: return@use null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            url?.let { Release(code, tag, it, j.optString("body")) }
        }
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "update.apk")
        if (f.exists()) f.delete()
        Net.client.newCall(Net.req(url)).execute().use { r ->
            if (!r.isSuccessful) error("Tải lỗi, mã ${r.code}")
            val body = r.body ?: error("Không có dữ liệu")
            val total = body.contentLength()
            body.byteStream().use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; withContext(Dispatchers.Main) { onProgress(p) } }
                        }
                    }
                }
            }
        }
        f
    }

    /** true nếu đã mở trình cài đặt; false nếu cần người dùng cấp quyền "cài từ nguồn không rõ" trước. */
    fun install(act: Activity, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = if (Build.VERSION.SDK_INT >= 24)
            FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f)
        else Uri.fromFile(f)
        act.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
