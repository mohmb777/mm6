package com.astra.pocketauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.content.pm.ServiceInfo

/**
 * Captures the screen only after explicit Android MediaProjection consent.
 * Frames are sampled locally at a target interval of 66.7ms (15 FPS).
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val analyzer = FrameMotionAnalyzer()
    private var lastProcessedNs = 0L
    private var processedFrames = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCaptureResources()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = getResultData(intent)
        if (resultCode != RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun getResultData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val activeProjection = manager.getMediaProjection(resultCode, resultData)
        projection = activeProjection

        workerThread = HandlerThread("ASTRA-Frame-Analysis").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        activeProjection.registerCallback(projectionCallback, workerHandler)
        virtualDisplay = activeProjection.createVirtualDisplay(
            "ASTRA-Live-Screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            workerHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - lastProcessedNs >= FRAME_INTERVAL_NS) {
                    lastProcessedNs = now
                    val result = analyzer.analyze(image)
                    processedFrames++
                    if (processedFrames % 8L == 0L) {
                        val update = Intent(ACTION_FRAME_STATUS).setPackage(packageName).apply {
                            putExtra(EXTRA_ACTIVITY, result.activity)
                            putExtra(EXTRA_MOTION_SCORE, result.motionScore)
                            putExtra(EXTRA_FRAME_COUNT, processedFrames)
                        }
                        sendBroadcast(update)
                    }
                }
            } catch (_: Exception) {
                // A damaged or unavailable frame is skipped; capture continues.
            } finally {
                image.close()
            }
        }, workerHandler)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ASTRA Screen Analysis",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("ASTRA — تحليل الشاشة")
            .setContentText("التقاط محلي مستهدف 15 إطارًا/ثانية")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun stopCaptureResources() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection = null
        analyzer.reset()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    override fun onDestroy() {
        stopCaptureResources()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "astra.result_code"
        const val EXTRA_RESULT_DATA = "astra.result_data"
        const val ACTION_FRAME_STATUS = "com.astra.pocketauto.FRAME_STATUS"
        const val EXTRA_ACTIVITY = "astra.activity"
        const val EXTRA_MOTION_SCORE = "astra.motion_score"
        const val EXTRA_FRAME_COUNT = "astra.frame_count"

        private const val CHANNEL_ID = "astra_screen_analysis"
        private const val NOTIFICATION_ID = 702
        private const val RESULT_OK = -1
        private const val FRAME_INTERVAL_NS = 66_666_667L
    }
}
