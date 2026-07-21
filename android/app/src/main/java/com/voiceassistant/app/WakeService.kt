package com.voiceassistant.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * 离线语音唤醒服务：用 Vosk 在本地识别唤醒词，完全不依赖系统语音识别，
 * 因此在系统识别不可用的机型（如部分 vivo）上也能"喊一声就来"。
 *
 * 避麦策略：主界面在前台时释放麦克风（给对话录音用），退到后台才监听唤醒词。
 * 模型（约 42MB）由界面在开启唤醒时下载好，这里只负责加载与监听。
 */
class WakeService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_WORD = "word"
        private const val CHANNEL_ID = "wake"
        private const val NOTIF_ID = 1001
        private const val DEFAULT_WORD = "你好助手"
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    @Volatile private var modelLoading = false
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var wakeWord = DEFAULT_WORD
    private var running = false
    private var lastFireAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_WORD)?.takeIf { it.isNotBlank() }?.let { wakeWord = normalize(it) }
        running = true
        ensureModelThenPump()
        return START_STICKY
    }

    // 确保模型已加载；未加载则后台加载，加载好后进入监听轮询
    private fun ensureModelThenPump() {
        if (!running) return
        if (model != null) { pump(); return }
        if (modelLoading) return
        if (!WakeModel.isReady(this)) {
            // 模型还没下好（界面负责下载），空转等待
            main.postDelayed({ ensureModelThenPump() }, 3000)
            return
        }
        modelLoading = true
        Thread {
            val m = try { Model(WakeModel.modelPath(this).absolutePath) } catch (_: Exception) { null }
            main.post {
                modelLoading = false
                model = m
                if (m == null) {
                    if (running) main.postDelayed({ ensureModelThenPump() }, 5000)
                } else if (running) {
                    pump()
                }
            }
        }.start()
    }

    // 前台时释放麦克风；后台时监听唤醒词
    private fun pump() {
        if (!running) return
        val m = model ?: run { ensureModelThenPump(); return }
        val wantListen = !MainActivity.appInForeground
        if (wantListen && speechService == null) {
            try {
                val rec = Recognizer(m, 16000.0f)
                val svc = SpeechService(rec, 16000.0f)
                svc.startListening(listener)
                recognizer = rec
                speechService = svc
            } catch (_: Exception) {
                stopListening()
            }
        } else if (!wantListen && speechService != null) {
            stopListening()
        }
        main.postDelayed({ pump() }, 1200)
    }

    private fun stopListening() {
        try { speechService?.stop() } catch (_: Exception) {}
        try { speechService?.shutdown() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        speechService = null
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) { if (hit(hypothesis, "partial")) fire() }
        override fun onResult(hypothesis: String?) { if (hit(hypothesis, "text")) fire() }
        override fun onFinalResult(hypothesis: String?) { if (hit(hypothesis, "text")) fire() }
        override fun onError(e: Exception?) {}
        override fun onTimeout() {}
    }

    private fun hit(json: String?, key: String): Boolean {
        if (json == null) return false
        val t = try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
        return t.isNotEmpty() && normalize(t).contains(wakeWord)
    }

    // 只保留中文字与字母数字，去掉空格标点，便于宽松匹配
    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) if (c.isLetterOrDigit() || c.code in 0x4E00..0x9FFF) sb.append(c)
        return sb.toString()
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 3000) return
        lastFireAt = now
        stopListening()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("wake", true)
        try { startActivity(i) } catch (_: Exception) {}
        main.postDelayed({ pump() }, 2500)
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "在后台监听唤醒词" }
            nm.createNotificationChannel(ch)
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("语音助手待命中")
            .setContentText("说出唤醒词即可唤起（持续监听会更耗电）")
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
    }

    override fun onDestroy() {
        running = false
        stopListening()
        try { model?.close() } catch (_: Exception) {}
        model = null
        super.onDestroy()
    }
}
