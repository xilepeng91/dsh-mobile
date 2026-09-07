package com.dsh.mobile.ui.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.mutableStateOf

/** mp3 直链：http(s) 开头、以 .mp3 结尾、中间无空白/尖括号/引号 */
private val MP3_URL_RE = Regex("""https?://[^\s<>"'()]+?\.mp3""", RegexOption.IGNORE_CASE)

/** 从消息文本提取第一个 mp3 播放地址；无则 null */
fun firstMp3Url(text: String): String? = MP3_URL_RE.find(text)?.value

/**
 * 全局单曲播放器：同一时刻只允许一首歌在播。
 * 气泡播放/暂停按钮共享此状态；新回复带歌且空闲时自动播放一次。
 */
object MusicPlayer {
    private var player: MediaPlayer? = null

    val currentUrl = mutableStateOf<String?>(null)
    val isPlaying = mutableStateOf(false)
    val isLoading = mutableStateOf(false)

    /** 按钮点击：正在播同一首则停止，否则播放该首（替换正在播的其它曲目） */
    fun toggle(url: String) {
        if (currentUrl.value == url && (isPlaying.value || isLoading.value)) {
            stop()
        } else {
            play(url)
        }
    }

    /** 自动播放专用：仅在完全空闲时播放，绝不打断用户手动操作 */
    fun playIfIdle(url: String) {
        if (isPlaying.value || isLoading.value) return
        play(url)
    }

    fun play(url: String) {
        release()
        currentUrl.value = url
        isLoading.value = true
        isPlaying.value = false
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                if (currentUrl.value != url) {
                    // 就绪前已被 stop()/切歌：立即释放，不发声
                    runCatching { it.release() }
                    return@setOnPreparedListener
                }
                isLoading.value = false
                runCatching {
                    it.start()
                    isPlaying.value = true
                }.onFailure { reset() }
            }
            mp.setOnCompletionListener {
                if (currentUrl.value == url) reset()
            }
            mp.setOnErrorListener { _, _, _ ->
                if (currentUrl.value == url) reset()
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (_: Exception) {
            reset()
        }
    }

    fun stop() {
        release()
        reset()
    }

    private fun reset() {
        isPlaying.value = false
        isLoading.value = false
        currentUrl.value = null
    }

    private fun release() {
        val old = player
        player = null
        old?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
    }
}
