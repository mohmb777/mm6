package com.astra.pocketauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var statusView: TextView? = null
    private var receiverRegistered = false

    private val frameStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_FRAME_STATUS) return
            val activity = intent.getStringExtra(ScreenCaptureService.EXTRA_ACTIVITY) ?: "جارٍ التحليل"
            val frames = intent.getLongExtra(ScreenCaptureService.EXTRA_FRAME_COUNT, 0L)
            statusView?.text = "$activity\nإطارات معالجة: $frames"
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerFrameReceiver()
        showOverlay()
    }

    private fun registerFrameReceiver() {
        val filter = IntentFilter(ScreenCaptureService.ACTION_FRAME_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(frameStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(frameStatusReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(0xEE18212F.toInt())
        }

        val header = TextView(this).apply {
            text = "ASTRA  ⋮"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(4, 2, 4, 6)
        }

        statusView = TextView(this).apply {
            text = "بانتظار موافقة مشاركة الشاشة"
            textSize = 10f
            setTextColor(0xFFFFD180.toInt())
            gravity = Gravity.CENTER
        }

        val closeButton = Button(this).apply {
            text = "إغلاق"
            textSize = 10f
            setOnClickListener { stopSelf() }
        }

        panel.addView(header)
        panel.addView(statusView)
        panel.addView(closeButton)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 160
        }

        header.setOnTouchListener(DragTouchListener(params))
        windowManager.addView(panel, params)
        overlayView = panel
    }

    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    overlayView?.let { windowManager.updateViewLayout(it, params) }
                    return true
                }
            }
            return false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ASTRA Floating Window",
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
            .setContentTitle("ASTRA Pocket Auto")
            .setContentText("النافذة العائمة تعمل")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(frameStatusReceiver)
            receiverRegistered = false
        }
        overlayView?.let {
            if (::windowManager.isInitialized) windowManager.removeView(it)
        }
        overlayView = null
        statusView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "astra_overlay"
        private const val NOTIFICATION_ID = 701
    }
}
