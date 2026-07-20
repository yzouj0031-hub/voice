package com.voiceassistant.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 语音唤醒后台服务：常驻前台通知，持续用系统语音识别监听“唤醒词”，
 * 听到就把主界面拉到最前面并自动开始对话。
 *
 * 避麦策略：主界面在前台时（MainActivity.appInForeground=true），
 * 服务不去抢麦克风，只做低频轮询等待；主界面退到后台后才真正监听。
 * 未内置额外唤醒词模型，直接复用系统识别，安装包基本不变大（建议装离线中文语音包）。
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
    private var recognizer: SpeechRecognizer? = null
    private var wakeWord = DEFAULT_WORD
    private var running = false
    private var listening = false
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
        // ACTION_START 或系统重启服务
        intent?.getStringExtra(EXTRA_WORD)?.takeIf { it.isNotBlank() }?.let {
            wakeWord = normalize(it)
        }
        running = true
        pump()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "在后台监听唤醒词" }
            nm.createNotificationChannel(ch)
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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

    // 监听主循环：前台时空转等待，后台时才真正听
    private fun pump() {
        if (!running || listening) return
        if (MainActivity.appInForeground) {
            main.postDelayed({ pump() }, 1500)   // App 正在用麦，稍后再看
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            main.postDelayed({ pump() }, 3000)
            return
        }
        listening = true
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            listening = false
            schedulePump(1000)
        }
    }

    private fun stopListening() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun schedulePump(delay: Long) {
        listening = false
        if (running) main.postDelayed({ pump() }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partial: Bundle?) {
            if (matched(partial)) fire()
        }

        override fun onResults(results: Bundle?) {
            if (matched(results)) fire() else schedulePump(400)
        }

        override fun onError(error: Int) {
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 500L
            schedulePump(delay)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun matched(bundle: Bundle?): Boolean {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        return list.any { normalize(it).contains(wakeWord) }
    }

    // 只保留中文字与字母数字，去掉空格标点，便于宽松匹配
    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit() || c.code in 0x4E00..0x9FFF) sb.append(c)
        }
        return sb.toString()
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 3000) { schedulePump(400); return } // 防抖
        lastFireAt = now
        stopListening()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("wake", true)
        try {
            startActivity(i)
        } catch (_: Exception) {
            // 没有“显示在其他应用上层”权限时可能被系统拦截
        }
        schedulePump(2000) // 继续轮询（前台时会自动空转）
    }

    override fun onDestroy() {
        running = false
        stopListening()
        super.onDestroy()
    }
}
