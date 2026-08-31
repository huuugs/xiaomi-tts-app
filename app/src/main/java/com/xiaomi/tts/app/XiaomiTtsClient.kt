package com.xiaomi.tts.app

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 合成请求参数
 */
data class TtsRequest(
    val model: String,                          // 模型 ID
    val text: String,                           // assistant 消息（可含风格/音频标签）
    val style: String? = null,                  // user 消息（风格指令 / 音色描述）
    val voice: String? = null,                  // 预置音色名 或 data:...;base64 克隆音频
    val optimizeTextPreview: Boolean = false    // voicedesign 智能润色
)

class XiaomiTtsClient(private val apiKey: String) {

    companion object {
        private const val API_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"

        /** 预置音色列表（文档：预置音色仅 mimo-v2.5-tts 支持） */
        val PRESET_VOICES = listOf(
            "mimo_default" to "默认",
            "冰糖" to "冰糖 · 中文女",
            "茉莉" to "茉莉 · 中文女",
            "苏打" to "苏打 · 中文男",
            "白桦" to "白桦 · 中文男",
            "Mia" to "Mia · 英文女",
            "Chloe" to "Chloe · 英文女",
            "Milo" to "Milo · 英文男",
            "Dean" to "Dean · 英文男"
        )

        /** 风格标签分组（拼接在文本开头，可多选） */
        val STYLE_TAG_GROUPS = listOf(
            "情绪" to listOf("开心", "悲伤", "愤怒", "恐惧", "惊讶", "兴奋", "委屈", "平静", "冷漠", "无奈", "释然", "忐忑"),
            "语调" to listOf("温柔", "高冷", "活泼", "严肃", "慵懒", "俏皮", "深沉", "干练"),
            "音色" to listOf("磁性", "醇厚", "清亮", "空灵", "甜美", "沙哑", "稚嫩", "苍老"),
            "方言" to listOf("东北话", "四川话", "河南话", "粤语"),
            "角色" to listOf("孙悟空", "林黛玉", "御姐音", "大叔音", "正太音", "夹子音", "台湾腔"),
            "唱歌" to listOf("唱歌")
        )

        /** 音频标签分组（插入光标处，细粒度控制） */
        val AUDIO_TAG_GROUPS = listOf(
            "呼吸" to listOf("吸气", "深呼吸", "叹气", "长叹一口气", "喘息", "屏息", "沉默片刻"),
            "笑" to listOf("笑", "轻笑", "大笑", "冷笑"),
            "哭" to listOf("哽咽", "抽泣", "呜咽", "哭", "嚎啕大哭"),
            "声音" to listOf("小声", "大声", "气声", "颤抖", "变调", "破音", "鼻音"),
            "语速" to listOf("语速加快", "语速放慢"),
            "状态" to listOf("紧张", "害怕", "激动", "疲惫", "撒娇", "心虚", "震惊", "不耐烦")
        )

        /** 文本模板 */
        val TEXT_TEMPLATES = listOf(
            "你好，我是小米语音合成模型，很高兴认识你！",
            "各位观众朋友大家好，欢迎收看今天的新闻节目。",
            "很久很久以前，在一个遥远的王国里，住着一位美丽的公主。",
            "（唱歌）原谅我这一生不羁放纵爱自由，也会怕有一天会跌倒。",
            "（紧张，深呼吸）呼……冷静，冷静。不就是一个面试吗……加油，你可以的。",
            "夜已经深了，城市还在呼吸。我是今晚陪你的人，欢迎收听《午夜电台》。"
        )

        /** 音色设计模板 */
        val VOICE_DESIGN_TEMPLATES = listOf(
            "一位年迈的老先生，说带北方口音的普通话，语速缓慢而沉稳，嗓音略带沙哑和沧桑感，仿佛一位饱经风霜的老爷爷在讲故事，充满岁月的智慧。",
            "Young female, gentle and soothing voice, speaks slowly, like a late-night radio host telling bedtime stories.",
            "五十多岁的中年男性，声音丝滑醇厚、带着磁性，像纪录片旁白一样沉稳有力。",
            "Gruff middle-aged male, blunt and matter-of-fact, with a heavy Russian accent."
        )

        /** 克隆音频转成 API 要求的 data URI（base64 后不超过 10MB） */
        fun buildCloneVoice(mime: String, audioBytes: ByteArray): String {
            val encoded = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            val dataUri = "data:$mime;base64,$encoded"
            if (dataUri.length > 10 * 1024 * 1024) {
                throw IOException("音频样本过大（base64 后超 10MB），请裁剪后再试")
            }
            return dataUri
        }

        /**
         * 魔数检测真实音频格式（不信任扩展名/MIME）
         * API 仅支持 mp3 / wav
         */
        fun detectAudioMime(bytes: ByteArray): String? = when {
            bytes.size >= 12 &&
                    String(bytes, 0, 4) == "RIFF" &&
                    String(bytes, 8, 4) == "WAVE" -> "audio/wav"
            bytes.size >= 3 && String(bytes, 0, 3) == "ID3" -> "audio/mpeg"
            // MPEG 帧同步 0xFF Ex（mp3 无 ID3 头时）
            bytes.size >= 2 &&
                    (bytes[0].toInt() and 0xFF) == 0xFF &&
                    (bytes[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            else -> null
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private fun parseError(response: Response): IOException {
        val errBody = try {
            response.body?.string() ?: ""
        } catch (e: Exception) {
            ""
        }
        return IOException("API ${response.code}: ${errBody.take(500)}")
    }

    /**
     * 文本转语音（非流式，返回 WAV 音频）
     */
    fun synthesize(req: TtsRequest): ByteArray {
        val messages = mutableListOf<JsonObject>()

        // user 消息：voicedesign 时为音色描述（必填）；其他模式为可选风格指令；voiceclone 可为空串
        val userContent = when (req.model) {
            "mimo-v2.5-tts-voicedesign" -> req.style ?: throw IOException("音色设计模式需要填写音色描述")
            "mimo-v2.5-tts-voiceclone" -> req.style ?: ""
            else -> req.style.takeUnless { it.isNullOrBlank() }
        }
        if (userContent != null) {
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userContent)
            })
        }

        // assistant 消息：目标文本（TTS 模型必需）
        messages.add(JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", req.text)
        })

        val requestBody = JsonObject().apply {
            addProperty("model", req.model)
            add("messages", gson.toJsonTree(messages))
            addProperty("stream", false)
            add("modalities", gson.toJsonTree(listOf("text", "audio")))
            add("audio", JsonObject().apply {
                addProperty("format", "wav")
                if (req.voice != null) addProperty("voice", req.voice)
                if (req.optimizeTextPreview) addProperty("optimize_text_preview", true)
            })
        }

        val request = Request.Builder()
            .url("$API_BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw parseError(response)
        }

        val responseBody = response.body?.string() ?: throw IOException("响应体为空")

        val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
        val choices = jsonResponse.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            throw IOException("无效的响应格式")
        }

        val message = choices[0].asJsonObject.getAsJsonObject("message")
            ?: throw IOException("响应中没有 message")

        if (!message.has("audio") || message.get("audio").isJsonNull) {
            throw IOException("响应中没有音频数据")
        }

        val audio = message.getAsJsonObject("audio")
        return Base64.decode(audio.get("data").asString, Base64.DEFAULT)
    }
}
