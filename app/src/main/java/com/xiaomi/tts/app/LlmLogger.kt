package com.xiaomi.tts.app

import java.util.Collections

/**
 * LLM 交互日志：记录分析过程中的完整交互（请求、思考、输出、重试、策略切换）
 * 线程安全，UI 轮询 snapshot 渲染
 */
object LlmLogger {

    const val TYPE_SYSTEM = 0    // 系统事件（开始/请求/完成/切换/重试）
    const val TYPE_THINKING = 1  // 思考原文
    const val TYPE_OUTPUT = 2    // 输出原文
    const val TYPE_ERROR = 3     // 错误

    data class Entry(val type: Int, val text: String, val ts: Long)

    private val entries = Collections.synchronizedList(mutableListOf<Entry>())

    /** 添加一条事件 */
    fun event(type: Int, text: String) {
        entries.add(Entry(type, text, System.currentTimeMillis()))
        trim()
    }

    /** 开始一段流式内容（思考/输出） */
    fun begin(type: Int) {
        entries.add(Entry(type, "", System.currentTimeMillis()))
        trim()
    }

    /** 更新当前流式内容（全量替换最后一行） */
    fun update(text: String) {
        synchronized(entries) {
            if (entries.isNotEmpty()) {
                val last = entries[entries.size - 1]
                entries[entries.size - 1] = Entry(last.type, text, System.currentTimeMillis())
            }
        }
    }

    fun snapshot(): List<Entry> = synchronized(entries) {
        entries.toList()
    }

    fun clear() {
        entries.clear()
    }

    private fun trim() {
        while (entries.size > 120) {
            entries.removeAt(0)
        }
    }
}
