package com.dsh.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.dsh.mobile.MainActivity
import com.rementia.openwakeword.lib.DetectionMode
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 本地离线语音唤醒服务（前台常驻，锁屏/后台可用）。
 * 唤醒引擎：openwakeword（ONNX Runtime），完全本地推理，不消耗服务器算力。
 * 模型：assets 下的 melspectrogram.onnx + embedding_model.onnx + alexa_v0.1.onnx。
 * 注意：openWakeWord 官方模型为英文唤醒词，当前用 alexa 模型（说 "Alexa" 唤醒）；
 * 如需中文"小鲸"，需用 openWakeWord 训练流程生成中文模型后替换。
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: WakeWordEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        startWakeWord()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startAsForeground() {
        val chId = "wakeword_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(chId, "语音唤醒", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, chId)
            .setContentTitle("语音唤醒已开启")
            .setContentText("说唤醒词即可唤醒 AI（离线识别，不占服务器）")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        startForeground(100, n)
    }

    private fun startWakeWord() {
        scope.launch {
            try {
                val models = listOf(
                    WakeWordModel("小鲸", "alexa_v0.1.onnx", threshold = 0.5f)
                )
                val e = WakeWordEngine(this@WakeWordService, models, DetectionMode.SINGLE_BEST)
                engine = e
                e.start()
                e.detections.collect { detection ->
                    onWakeWord(detection.model.name)
                }
            } catch (t: Throwable) {
                // 引擎启动失败：更新通知提示
                updateForegroundText("语音唤醒启动失败：" + (t.message ?: "unknown"))
            }
        }
    }

    private fun updateForegroundText(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val chId = "wakeword_channel"
        val n = Notification.Builder(this, chId)
            .setContentTitle("语音唤醒")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        nm.notify(100, n)
    }

    private fun onWakeWord(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val chId = "wakeword_channel"
        val n = Notification.Builder(this, chId)
            .setContentTitle("已唤醒：" + name)
            .setContentText("点击进入语音对话，AI 已准备就绪")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 1,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        nm.notify(101, n)
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        scope.cancel()
        super.onDestroy()
    }
}
