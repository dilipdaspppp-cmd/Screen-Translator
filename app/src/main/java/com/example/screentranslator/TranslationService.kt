package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class TranslationService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayImage: ImageView? = null
    private var buttonView: TextView? = null
    private var buttonBg: GradientDrawable? = null
    private var buttonParams: android.view.WindowManager.LayoutParams? = null
    private var windowManager: android.view.WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentKeyIndex = 0
    private var currentModelIndex = 0
    private var screenWidth = 1
    private var screenHeight = 1
    private var isCapturing = false
    private var isOverlayShowing = false
    private var pendingCapture = false
    private var serviceOn = false
    private var buttonSize = 1

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Translator")
            .setContentText("ফ্লোটিং বাটন চালু আছে")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        setupOverlayImage()
        setupFloatingButton()
        serviceOn = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_TRANSLATION" -> {
                val resultCode = intent.getIntExtra("resultCode", -1)
                val data = intent.getParcelableExtra<Intent>("data")
                if (resultCode != -1 && data != null && mediaProjection == null) {
                    initProjection(resultCode, data)
                }
                Toast.makeText(this, "ফ্লোটিং বাটনে চাপ দিন, অনুবাদ হবে", Toast.LENGTH_LONG).show()
            }
            "PROJECTION_RESULT" -> {
                val resultCode = intent.getIntExtra("resultCode", -1)
                val data = intent.getParcelableExtra<Intent>("data")
                if (resultCode != -1 && data != null) {
                    initProjection(resultCode, data)
                }
                if (pendingCapture) {
                    pendingCapture = false
                    handler.postDelayed({ beginTranslation() }, 300)
                }
            }
            "STOP_TRANSLATION" -> {
                stopAll()
            }
        }
        return START_NOT_STICKY
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Toast.makeText(this, "স্ক্রিন ক্যাপচার অনুমতি পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenTranslator",
            screenWidth, screenHeight, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun setupFloatingButton() {
        buttonBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#2196F3"))
        }
        buttonView = TextView(this).apply {
            text = "অ"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = buttonBg
        }

        buttonSize = dpToPx(44)
        buttonParams = android.view.WindowManager.LayoutParams(
            buttonSize, buttonSize,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        buttonParams?.gravity = Gravity.TOP or Gravity.START
        buttonParams?.x = dpToPx(8)
        buttonParams?.y = screenHeight / 2

        attachButtonTouch()

        windowManager?.addView(buttonView, buttonParams)
    }

    private fun attachButtonTouch() {
        buttonView?.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0f
            private var startY = 0f
            private var downTime = 0L
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = (buttonParams?.x ?: 0).toFloat()
                        startY = (buttonParams?.y ?: 0).toFloat()
                        downTime = System.currentTimeMillis()
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) moved = true
                        if (moved) {
                            var nx = (startX + dx).toInt()
                            var ny = (startY + dy).toInt()
                            if (nx < 0) nx = 0
                            if (ny < 0) ny = 0
                            if (nx > screenWidth - buttonSize) nx = screenWidth - buttonSize
                            if (ny > screenHeight - buttonSize) ny = screenHeight - buttonSize
                            buttonParams?.x = nx
                            buttonParams?.y = ny
                            try {
                                windowManager?.updateViewLayout(v, buttonParams)
                            } catch (e: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dt = System.currentTimeMillis() - downTime
                        if (!moved && dt < 500) {
                            onButtonTap()
                        } else if (!moved && dt >= 500) {
                            Toast.makeText(this@TranslationService, "সার্ভিস বন্ধ হচ্ছে", Toast.LENGTH_SHORT).show()
                            stopAll()
                        }
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun setupOverlayImage() {
        overlayImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.INVISIBLE
        }
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        wm.addView(overlayImage, params)
    }

    private fun onButtonTap() {
        if (isCapturing) return
        if (isOverlayShowing) {
            overlayImage?.visibility = View.INVISIBLE
            isOverlayShowing = false
            setButtonColor("#2196F3")
        } else {
            beginTranslation()
        }
    }

    private fun beginTranslation() {
        if (mediaProjection == null) {
            pendingCapture = true
            setButtonColor("#FF9800")
            Toast.makeText(this, "একবার অনুমতি দিন, তারপর নিজে থেকেই অনুবাদ হবে", Toast.LENGTH_LONG).show()
            val i = Intent(this, CaptureActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return
        }
        isCapturing = true
        setButtonColor("#FF9800")
        Toast.makeText(this, "অনুবাদ হচ্ছে...", Toast.LENGTH_SHORT).show()
        buttonView?.visibility = View.GONE
        overlayImage?.visibility = View.INVISIBLE
        handler.postDelayed({ captureNow(0) }, 500)
    }

    private fun captureNow(retry: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            if (retry < 3) {
                handler.postDelayed({ captureNow(retry + 1) }, 300)
            } else {
                isCapturing = false
                buttonView?.visibility = View.VISIBLE
                setButtonColor("#2196F3")
                Toast.makeText(this, "স্ক্রিন ধরা যায়নি, আবার চাপ দিন", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val w = image.width
        val h = image.height
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * w

        val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        image.close()
        val bitmap = Bitmap.createBitmap(full, 0, 0, w, h)

        buttonView?.visibility = View.VISIBLE
        Thread { translateWithRotation(bitmap) }.start()
    }

    private fun translateWithRotation(bitmap: Bitmap) {
        val keys = getKeys()
        val models = getModels()

        if (keys.isEmpty() || models.isEmpty()) {
            handler.post {
                Toast.makeText(this, "সেটিংসে key বা model নেই", Toast.LENGTH_SHORT).show()
                isCapturing = false
                setButtonColor("#2196F3")
            }
            return
        }

        var apiBitmap = bitmap
        if (apiBitmap.width > 1024) {
            val ratio = 1024.0f / apiBitmap.width
            apiBitmap = Bitmap.createScaledBitmap(
                bitmap, 1024, (bitmap.height * ratio).toInt(), true
            )
        }

        val totalCombos = keys.size * models.size
        var attempts = 0
        var resultText: String? = null

        while (attempts < totalCombos && resultText == null) {
            val key = keys[currentKeyIndex]
            val model = models[currentModelIndex]
            saveCurrentCombo(key, model)
            val result = callGeminiAPI(key, model, apiBitmap)
            if (result != null) {
                resultText = result
            } else {
                moveToNextCombo(keys.size, models.size)
                attempts++
                Thread.sleep(300)
            }
        }

        if (resultText != null) {
            val finalText = resultText
            handler.post {
                drawAndShowOverlay(finalText)
                isOverlayShowing = true
                isCapturing = false
                setButtonColor("#4CAF50")
                Toast.makeText(this, "অনুবাদ সম্পন্ন", Toast.LENGTH_SHORT).show()
            }
        } else {
            handler.post {
                Toast.makeText(this, "সব লিমিট শেষ, ১ মিনিট পর আবার চেষ্টা করছি", Toast.LENGTH_LONG).show()
            }
            Thread.sleep(60000)
            currentKeyIndex = 0
            currentModelIndex = 0
            handler.post { captureNow(0) }
        }
    }

    private fun callGeminiAPI(key: String, model: String, bitmap: Bitmap): String? {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 70, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val prompt = "এই ছবির সব লেখা শনাক্ত করো এবং বাংলায় অনুবাদ করো। " +
                    "প্রতিটি লেখার জন্য একটি করে entry দাও। " +
                    "উত্তর অবশ্যই valid JSON হবে, structure এমন: " +
                    "translations নামে একটি array, প্রতিটি item এ original_text, translated_text, " +
                    "এবং box নামে object যার ভেতরে x, y, width, height। " +
                    "box এর মান 0 থেকে 1000 স্কেলে দিও, ছবির বাম-উপর কোণা থেকে ধরে। " +
                    "JSON ছাড়া অন্য কিছু লিখবে না।"

            val json = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/png")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + key
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string()
            response.close()

            if (code == 200 && body != null) {
                val jsonResponse = JSONObject(body)
                val candidates = jsonResponse.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    if (parts.length() > 0) {
                        var text = parts.getJSONObject(0).getString("text")
                        val s = text.indexOf('{')
                        val e = text.lastIndexOf('}')
                        if (s >= 0 && e > s) {
                            text = text.substring(s, e + 1)
                        }
                        return text
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun drawAndShowOverlay(responseText: String) {
        try {
            val json = JSONObject(responseText)
            val translations = json.getJSONArray("translations")

            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            for (i in 0 until translations.length()) {
                val item = translations.getJSONObject(i)
                val translatedText = item.optString("translated_text", "")
                if (translatedText.isBlank()) continue
                val box = item.getJSONObject("box")
                val nx = box.optDouble("x", 0.0)
                val ny = box.optDouble("y", 0.0)
                val nw = box.optDouble("width", 100.0)
                val nh = box.optDouble("height", 50.0)

                val left = (nx / 1000.0 * screenWidth).toFloat()
                val top = (ny / 1000.0 * screenHeight).toFloat()
                val right = left + (nw / 1000.0 * screenWidth).toFloat()
                val bottom = top + (nh / 1000.0 * screenHeight).toFloat()
                val boxWidth = right - left
                val boxHeight = bottom - top

                val bgPaint = Paint().apply { color = Color.argb(220, 20, 20, 20) }
                canvas.drawRect(left, top, right, bottom, bgPaint)

                val paint = Paint().apply {
                    color = Color.WHITE
                    isAntiAlias = true
                    textSize = boxHeight * 0.75f
                }
                while (paint.measureText(translatedText) > boxWidth && paint.textSize > 10f) {
                    paint.textSize = paint.textSize - 2f
                }
                val baseline = top + boxHeight / 2f - (paint.descent() + paint.ascent()) / 2f
                canvas.drawText(translatedText, left + 4f, baseline, paint)
            }

            overlayImage?.setImageBitmap(bitmap)
            overlayImage?.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setButtonColor(hex: String) {
        buttonBg?.setColor(Color.parseColor(hex))
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getKeys(): List<String> {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return listOf(
            prefs.getString("key1", "") ?: "",
            prefs.getString("key2", "") ?: "",
            prefs.getString("key3", "") ?: "",
            prefs.getString("key4", "") ?: "",
            prefs.getString("key5", "") ?: ""
        ).filter { it.isNotBlank() }
    }

    private fun getModels(): List<String> {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val models = mutableListOf<String>()
        if (prefs.getBoolean("model1", true)) models.add("gemini-3.5-flash-lite")
        if (prefs.getBoolean("model2", true)) models.add("gemini-3.1-flash-lite")
        if (prefs.getBoolean("model3", true)) models.add("gemini-3-flash-preview")
        return models
    }

    private fun saveCurrentCombo(key: String, model: String) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit().apply {
            putString("currentKey", key.take(8) + "...")
            putString("currentModel", model)
            apply()
        }
    }

    private fun moveToNextCombo(keyCount: Int, modelCount: Int) {
        currentModelIndex++
        if (currentModelIndex >= modelCount) {
            currentModelIndex = 0
            currentKeyIndex = (currentKeyIndex + 1) % keyCount
        }
    }

    private fun stopAll() {
        serviceOn = false
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        try {
            buttonView?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        try {
            overlayImage?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        buttonView = null
        overlayImage = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Translator Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (serviceOn) stopAll()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ScreenTranslatorChannel"
        private const val NOTIFICATION_ID = 1
    }
}
