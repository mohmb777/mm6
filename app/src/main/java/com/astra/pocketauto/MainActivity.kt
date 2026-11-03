package com.astra.pocketauto

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val overlayRequestCode = 4101
    private val screenCaptureRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
        }

        val title = TextView(this).apply {
            text = "ASTRA Pocket Auto"
            textSize = 26f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "تحليل بصري مباشر — Hybrid 15 FPS"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 28)
        }

        val permissionButton = Button(this).apply {
            text = "منح إذن الظهور فوق التطبيقات"
            setOnClickListener { requestOverlayPermission() }
        }

        val startOverlayButton = Button(this).apply {
            text = "تشغيل النافذة العائمة"
            setOnClickListener { startOverlay() }
        }

        val startCaptureButton = Button(this).apply {
            text = "بدء بث الشاشة والتحليل"
            setOnClickListener { requestScreenCapture() }
        }

        val stopCaptureButton = Button(this).apply {
            text = "إيقاف بث الشاشة"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                Toast.makeText(this@MainActivity, "تم طلب إيقاف بث الشاشة", Toast.LENGTH_SHORT).show()
            }
        }

        val stopOverlayButton = Button(this).apply {
            text = "إيقاف النافذة العائمة"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "تم طلب إيقاف النافذة", Toast.LENGTH_SHORT).show()
            }
        }

        val note = TextView(this).apply {
            text = "سيطلب Android إذن مشاركة الشاشة. تتم معالجة عينات بصرية محليًا بمعدل مستهدف 15 إطارًا/ثانية. هذه النسخة لا تحدد CALL/PUT ولا تفتح صفقات."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        root.addView(title, matchWidth())
        root.addView(subtitle, matchWidth())
        root.addView(permissionButton, matchWidth())
        root.addView(startOverlayButton, matchWidth())
        root.addView(startCaptureButton, matchWidth())
        root.addView(stopCaptureButton, matchWidth())
        root.addView(stopOverlayButton, matchWidth())
        root.addView(note, matchWidth())

        setContentView(root)
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "الإذن ممنوح بالفعل", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, overlayRequestCode)
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        startService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "تم طلب تشغيل النافذة العائمة", Toast.LENGTH_SHORT).show()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), screenCaptureRequestCode)
    }

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "تم إرسال طلب بدء تحليل الشاشة", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Kept compatible with the project's minimum Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            overlayRequestCode -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "تم منح إذن النافذة العائمة", Toast.LENGTH_SHORT).show()
                }
            }
            screenCaptureRequestCode -> {
                if (resultCode == RESULT_OK && data != null) {
                    startCaptureService(resultCode, data)
                } else {
                    Toast.makeText(this, "لم تتم الموافقة على مشاركة الشاشة", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
