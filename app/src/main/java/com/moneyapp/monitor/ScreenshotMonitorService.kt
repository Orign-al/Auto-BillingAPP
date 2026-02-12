package com.moneyapp.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.moneyapp.R
import com.moneyapp.ocr.OcrEngine
import com.moneyapp.repository.OcrRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScreenshotMonitorService : Service() {
    private val ocrEngine = OcrEngine()
    private lateinit var observer: ContentObserver
    private lateinit var repository: OcrRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        repository = OcrRepository(this)

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                handleChange()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun handleChange() {
        try {
            val latest = queryLatestScreenshot() ?: return
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastId = prefs.getLong(KEY_LAST_ID, -1L)
            val lastDate = prefs.getLong(KEY_LAST_DATE, -1L)

            if (latest.id == lastId || latest.dateAdded <= lastDate) {
                return
            }

            prefs.edit()
                .putLong(KEY_LAST_ID, latest.id)
                .putLong(KEY_LAST_DATE, latest.dateAdded)
                .apply()

            ocrEngine.recognizeText(this, latest.uri) { result ->
                Log.i(TAG, "OCR text (${latest.displayName}): ${result.text}")
                if (result.text.isNotBlank()) {
                    serviceScope.launch {
                        repository.addRecord(
                            imageUri = latest.uri.toString(),
                            displayName = latest.displayName,
                            rawText = result.text
                        )
                    }
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Missing media permission", error)
        } catch (error: Exception) {
            Log.e(TAG, "Screenshot monitor failed", error)
        }
    }

    private fun queryLatestScreenshot(): ScreenshotInfo? {
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()

        val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%Screenshots%")
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/Screenshots/%")
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            val id = cursor.getLong(0)
            val dateAdded = cursor.getLong(1)
            val displayName = cursor.getString(2) ?: "screenshot"
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

            return ScreenshotInfo(id, dateAdded, displayName, uri)
        }

        return null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot OCR",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Screenshot OCR running")
            .setContentText("Monitoring screenshots for OCR")
            .setOngoing(true)
            .build()
    }

    private data class ScreenshotInfo(
        val id: Long,
        val dateAdded: Long,
        val displayName: String,
        val uri: Uri
    )

    companion object {
        private const val TAG = "ScreenshotMonitor"
        private const val CHANNEL_ID = "screenshot_ocr"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "screenshot_monitor"
        private const val KEY_LAST_ID = "last_id"
        private const val KEY_LAST_DATE = "last_date"
    }
}
