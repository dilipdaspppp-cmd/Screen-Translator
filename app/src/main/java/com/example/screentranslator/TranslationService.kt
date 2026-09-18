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
import android.widget.FrameLayout
import android.widget.ImageView
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
    private var overlayView: FrameLayout? = null
    private var overlayImage: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentKeyIndex = 0
    private var currentModelIndex = 0
    private var screenWidth = 1
    private var screenHeight = 1

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Translator")
            .setContentText("অনুবাদ চলছে...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_TRANSLATION" -> {
                val resultCode = intent.getIntExtra("resultCode", -1)
                val data = intent.getParcelableExtra<Intent>("data")
                if (resultCode != -1 && data != null) {
                    startCapture(resultCode, data)
                }
            }
            "STOP_TRANSLATION" -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Toast.makeText(this, "MediaProjection শুরু করা যায়নি", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenTranslator",
            screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        setupOverlay()
        isRunning = true
        handler.postDelayed(captureRunnable, 2500)
    }

    private fun setupOverlay() {
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
        params.x = 0
        params.y = 0

        overlayView = FrameLayout(this)
        overlayImage = ImageView(this)
        overlayImage?.scaleType = ImageView.ScaleType.FIT_XY
        overlayView?.addView(overlayImage)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        windowManager.addView(overlayView, params)
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val w = image.width
                val h = image.height
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * w

                val bitmap = Bitmap.createBitmap(
                    w + rowPadding / pixelStride,
                    h,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h)
                translateImage(croppedBitmap)
            } else {
                handler.postDelayed(this, CAPTURE_INTERVAL)
            }
        }
    }

    private fun translateImage(bitmap: Bitmap) {
        Thread {
            val keys = getKeys()
            val models = getModels()

            if (keys.isEmpty() || models.isEmpty()) {
                handler.post {
                    Toast.makeText(this, "সেটিংসে key বা model নেই", Toast.LENGTH_SHORT).show()
                    stopCapture()
                    stopSelf()
                }
                return@Thread
            }

            val totalCombos = keys.size * models.size
            var attempts = 0
            var success = false
            var resultText: String? = null

            while (attempts < totalCombos && !success) {
                val key = keys[currentKeyIndex]
                val model = models[currentModelIndex]

                saveCurrentCombo(key, model)

                val result = callGeminiAPI(key, model, bitmap)
                if (result != null) {
                    success = true
                    resultText = result
                } else {
                    moveToNextCombo(keys.size, models.size)
                    attempts++
                    Thread.sleep(400)
                }
            }

            if (success && resultText != null) {
                val finalText = resultText
                handler.post {
                    updateOverlay(finalText)
                    handler.postDelayed(captureRunnable, CAPTURE_INTERVAL)
                }
            } else {
                handler.post {
                    Toast.makeText(this, "সব লিমিট শেষ, ১ মিনিট অপেক্ষা করছি...", Toast.LENGTH_LONG).show()
                }
                Thread.sleep(60000)
                currentKeyIndex = 0
                currentModelIndex = 0
                handler.post {
                    handler.postDelayed(captureRunnable, CAPTURE_INTERVAL)
                }
            }
        }.start()
    }

    private fun callGeminiAPI(key: String, model: String, bitmap: Bitmap): String? {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, outputStream)
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

    private fun updateOverlay(responseText: String) {
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

                val bgPaint = Paint().apply {
                    color = Color.argb(220, 20, 20, 20)
                }
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    private fun stopCapture() {
        isRunning = false
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null

        overlayView?.let {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            windowManager.removeView(it)
        }
        overlayView = null
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
        stopCapture()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ScreenTranslatorChannel"
        private const val NOTIFICATION_ID = 1
        private const val CAPTURE_INTERVAL = 4000L
    }
}
