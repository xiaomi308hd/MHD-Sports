package com.mhd.sports

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Chạy nền định kỳ: tải nguồn và lưu các trận vào ReplayStore, không cần mở ứng dụng. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val result = SourceLoader.loadCatalog(Config.DEFAULT_SOURCE_URL)
        if (result.channels.isNotEmpty()) {
            ReplayStore.merge(applicationContext, result.channels, System.currentTimeMillis())
        }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
