package com.xiaomi.tts.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录条目
 */
data class HistoryItem(
    val id: Long,               // 时间戳 ID
    val time: Long,             // 创建时间
    val model: String,          // 模型
    val modeName: String,       // 模式显示名
    val text: String,           // 合成文本（含标签）
    val fileName: String        // recordings/ 下的文件名
) {
    val file: File get() = File(RECORDINGS_DIR, fileName)

    fun timeText(): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))

    fun preview(): String =
        if (text.length > 40) text.take(40) + "…" else text

    companion object {
        val RECORDINGS_DIR: File get() = File(
            // 由 init() 在 App 启动时赋值
            appFilesDir ?: File("."), "recordings"
        )

        @Volatile
        var appFilesDir: File? = null

        fun init(context: Context) {
            appFilesDir = context.filesDir
            RECORDINGS_DIR.mkdirs()
        }
    }
}

/**
 * 历史记录存储：JSON 文件 + 音频文件，最多保留 50 条
 */
object HistoryStore {

    private const val MAX_ITEMS = 50
    private lateinit var jsonFile: File

    fun init(context: Context) {
        HistoryItem.init(context)
        jsonFile = File(context.filesDir, "history.json")
    }

    private fun load(): MutableList<HistoryItem> {
        if (!this::jsonFile.isInitialized) return mutableListOf()
        return try {
            if (!jsonFile.exists()) return mutableListOf()
            val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
            Gson().fromJson(jsonFile.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(list: List<HistoryItem>) {
        if (!this::jsonFile.isInitialized) return
        try {
            jsonFile.writeText(Gson().toJson(list))
        } catch (_: Exception) {
        }
    }

    fun list(): List<HistoryItem> = load().sortedByDescending { it.time }

    /**
     * 添加记录，返回条目
     */
    fun add(model: String, modeName: String, text: String, audio: ByteArray): HistoryItem {
        val now = System.currentTimeMillis()
        val fileName = "tts_$now.wav"
        File(HistoryItem.RECORDINGS_DIR, fileName).writeBytes(audio)
        val item = HistoryItem(
            id = now,
            time = now,
            model = model,
            modeName = modeName,
            text = text,
            fileName = fileName
        )
        val list = load()
        list.add(item)
        // 超出上限时删除最旧的（同时删除音频文件）
        while (list.size > MAX_ITEMS) {
            val oldest = list.minByOrNull { it.time } ?: break
            oldest.file.delete()
            list.remove(oldest)
        }
        save(list)
        return item
    }

    /**
     * 添加记录（音频已在文件中，流式拷贝入库，适合大文件）
     */
    fun addFile(model: String, modeName: String, text: String, wavFile: File): HistoryItem {
        val now = System.currentTimeMillis()
        val fileName = "tts_$now.wav"
        val dest = File(HistoryItem.RECORDINGS_DIR, fileName)
        wavFile.inputStream().use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        wavFile.delete()
        val item = HistoryItem(
            id = now,
            time = now,
            model = model,
            modeName = modeName,
            text = text,
            fileName = fileName
        )
        val list = load()
        list.add(item)
        while (list.size > MAX_ITEMS) {
            val oldest = list.minByOrNull { it.time } ?: break
            oldest.file.delete()
            list.remove(oldest)
        }
        save(list)
        return item
    }

    /**
     * 删除单条记录（含音频文件）
     */
    fun delete(id: Long) {
        val list = load()
        val item = list.find { it.id == id } ?: return
        item.file.delete()
        list.remove(item)
        save(list)
    }
}
