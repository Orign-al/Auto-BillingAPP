package com.moneyapp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.moneyapp.db.AppDatabase
import com.moneyapp.repository.OcrRepository

class UploadRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = OcrRepository(applicationContext)
        val pending = AppDatabase.get(applicationContext).ocrRecordDao().getPending(30)
        if (pending.isEmpty()) return Result.success()

        var anyFailed = false
        for (record in pending) {
            val ok = repository.uploadRecord(record)
            val status = if (ok) "uploaded" else "failed"
            repository.updateRecord(record.copy(uploadStatus = status))
            if (!ok) anyFailed = true
        }

        return if (anyFailed) Result.retry() else Result.success()
    }
}
