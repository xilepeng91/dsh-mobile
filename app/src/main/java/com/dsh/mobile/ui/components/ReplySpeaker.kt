package com.dsh.mobile.ui.components

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 系统 TTS 自动朗读（T8）。
 *
 * 会话页在「一条 assistant 回复从流式转完成」时调用 [speak]，用手机自带 TTS 把回复读出来；
 * 开关关闭或离开会话时调用 [stop] 立即停止。
 *
 * 单例持有 [TextToSpeech]（applicationContext，不泄漏 Activity），初始化失败（缺中文语音包）
 * 则静默降级——不播报，绝不崩溃、绝不阻塞 UI。
 */
object ReplySpeaker {

    /** 朗读文本上限：超长回复只读前 N 字，避免一口气读几分钟 */
    private const val MAX_SPEAK_CHARS = 1200

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: String? = null

    /** 首次调用时懒初始化（系统 TTS 初始化是异步回调，初始化完成前先暂存待读文本） */
    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val res = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                t.setSpeechRate(0.92f)
                t.setPitch(1.08f)
                pending?.let {
                    pending = null
                    speakNow(it)
                }
            } else {
                // 初始化失败：清空，下次 speak 会重试
                tts = null
            }
        }
    }

    /** 朗读一段纯文本；会自动清洗 Markdown/链接/表情；空文本忽略 */
    fun speak(context: Context, raw: String) {
        if (raw.isBlank()) return
        val text = cleanForTts(raw)
        if (text.isBlank()) return
        init(context)
        if (tts != null && ready) speakNow(text) else pending = text
    }

    /** 立即停止当前朗读并丢弃未读的暂存文本 */
    fun stop() {
        pending = null
        runCatching { tts?.stop() }
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        val id = "reply_${System.currentTimeMillis()}"
        runCatching { t.stop() }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            } else {
                @Suppress("DEPRECATION")
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }

    /**
     * 把回复转成适合朗读的纯文本：
     * 去掉代码块/行内代码、URL、markdown 标题与强调符号、emoji、多余空白；超长截断。
     */
    private fun cleanForTts(text: String): String {
        var s = text
            .replace(Regex("```[\\s\\S]*?```"), " ")              // 代码块
            .replace(Regex("`[^`]*`"), " ")                        // 行内代码
            .replace(Regex("https?://\\S+"), " ")                  // URL
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")    // markdown 链接
            .replace(Regex("#{1,6}\\s*"), " ")                     // 标题#
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), " ") // emoji（代理对）
            .replace(Regex("[\\u2600-\\u27BF\\uFE0F]+"), " ")      // 符号/变体选择符
            .replace(Regex("[*_~>|]"), " ")                        // 加粗/斜体/引用/表格分隔
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > MAX_SPEAK_CHARS) s = s.take(MAX_SPEAK_CHARS).trimEnd() + "。"
        return s
    }
}
