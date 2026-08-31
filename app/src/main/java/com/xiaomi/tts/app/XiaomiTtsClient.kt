package com.xiaomi.tts.app

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
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

        // ── 智能角色分析（LLM）──

        private const val LLM_MODEL = "mimo-v2.5-pro"
        private const val LLM_MAX_CHARS = 10000
        private val llmGson = Gson()

        /**
         * 调 MiMo 文本模型推断每段说话者（Token Plan 套餐内用量）
         * 任意长度：超过单块上限自动分块，块间传递已知角色名单保持命名一致
         */
        /**
         * 智能角色分析（优化版）：本地规则切分（秒出），LLM 只标注每段说话人。
         * 输出仅为角色名数组，速度快数十倍。
         */
        fun llmAnalyze(
            apiKey: String,
            text: String,
            onProgress: (chunkIndex: Int, chunkTotal: Int, receivedChars: Int) -> Unit = { _, _, _ -> }
        ): List<NovelSegment> {
            val segments = NovelParser.parse(text)
            if (segments.isEmpty()) return segments

            // 按累计字数分块（每块约 1 万字）
            val chunks = mutableListOf<MutableList<Int>>()
            var cur = mutableListOf<Int>()
            var acc = 0
            segments.forEachIndexed { i, seg ->
                if (acc + seg.text.length > LLM_MAX_CHARS && cur.isNotEmpty()) {
                    chunks += cur
                    cur = mutableListOf()
                    acc = 0
                }
                cur += i
                acc += seg.text.length
            }
            if (cur.isNotEmpty()) chunks += cur

            val speakers = arrayOfNulls<String>(segments.size)
            val known = linkedSetOf<String>()

            chunks.forEachIndexed { ci, idxs ->
                onProgress(ci + 1, chunks.size, 0)
                val labels = labelWithStrategies(apiKey, segments, idxs, known) { chars ->
                    onProgress(ci + 1, chunks.size, chars)
                }
                idxs.forEachIndexed { j, segIdx ->
                    speakers[segIdx] = labels.getOrNull(j) ?: "未标注"
                }
                known += labels.filter { it != "旁白" && it != "未标注" && it.isNotBlank() }
            }

            return segments.mapIndexed { i, seg ->
                seg.copy(speaker = speakers[i] ?: "未标注")
            }
        }

        /**
         * 请求 LLM 标注一块分段的说话人，返回与 idxs 等长的角色名列表
         */
        private fun labelChunk(
            apiKey: String,
            segments: List<NovelSegment>,
            idxs: List<Int>,
            knownSpeakers: Set<String>,
            strategyIdx: Int,
            onDelta: (Int) -> Unit = {}
        ): List<String> {
            val strategy = llmStrategies[strategyIdx]
            val system = "任务：为小说分段标注说话人。这是模式匹配任务，直接给出答案，不需要推理过程、不要解释。\n" +
                    "\n" +
                    "输入：分段数组，i=序号，d=0旁白/1对白，t=文本（可能截断）。\n" +
                    "\n" +
                    "规则（按优先级）：\n" +
                    "1. d=0 → \"旁白\"\n" +
                    "2. 对白前紧邻\"XX说道/问/喊/笑道/低声道…\"提示语 → XX 即说话人\n" +
                    "3. 无提示语 → 依上下文推断：连续对白通常两人交替；结合自称（我/朕/俺）、称呼、语气判断\n" +
                    "4. 推断不出 → \"未标注\"\n" +
                    "5. 角色名优先沿用已知角色表，同一人物前后同名\n" +
                    (if (knownSpeakers.isNotEmpty())
                        "\n已知角色：${knownSpeakers.joinToString("、")}\n"
                    else "") +
                    "\n输出：纯 JSON 字符串数组，长度等于输入段数，按 i 顺序。\n" +
                    "例：\n" +
                    "输入 [{\"i\":0,\"d\":0,\"t\":\"夜色渐深\"},{\"i\":1,\"d\":1,\"t\":\"你来做什么？\"},{\"i\":2,\"d\":1,\"t\":\"我…我路过。\"}]\n" +
                    "已知角色：林清雪、陈默\n" +
                    "→ [\"旁白\",\"林清雪\",\"陈默\"]"

            // 输入压缩：旁白截 20 字（输出固定），对白截 120 字（推断足够）
            val input = idxs.map { i ->
                mapOf(
                    "i" to i,
                    "d" to if (segments[i].isDialogue) 1 else 0,
                    "t" to segments[i].text.take(if (segments[i].isDialogue) 120 else 20)
                )
            }

            val requestBody = JsonObject().apply {
                addProperty("model", strategy.model)
                add("messages", llmGson.toJsonTree(listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to llmGson.toJson(input))
                )))
                addProperty("temperature", 0.1)
                addProperty("stream", true)
                // 思考型模型：禁用推理链，标注任务无需思考
                if (strategy.disableThinking) {
                    addProperty("enable_thinking", false)
                }
            }

            val request = Request.Builder()
                .url("$API_BASE_URL/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                        throw IOException("智能分析 API ${response.code}: ${err.take(300)}")
                    }

                    val sb = StringBuilder()
                    var lastCb = 0
                    response.body?.source()?.use { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val data = line.substring(6)
                            if (data.trim() == "[DONE]") break
                            try {
                                val obj = JsonParser.parseString(data).asJsonObject
                                val choices = obj.getAsJsonArray("choices") ?: continue
                                if (choices.size() == 0) continue
                                val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: continue
                                if (delta.has("content") && !delta.get("content").isJsonNull) {
                                    sb.append(delta.get("content").asString)
                                    if (sb.length - lastCb >= 30) {
                                        lastCb = sb.length
                                        onDelta(sb.length)
                                    }
                                }
                            } catch (_: Exception) {
                                continue
                            }
                        }
                    }

                    val content = sb.toString()
                    val start = content.indexOf('[')
                    val end = content.lastIndexOf(']')
                    if (start < 0 || end <= start) {
                        throw IOException("模型未返回有效标注：${content.take(200)}")
                    }
                    val arr = JsonParser.parseString(content.substring(start, end + 1)).asJsonArray
                    val labels = mutableListOf<String>()
                    for (e in arr) {
                        val s = if (e.isJsonNull) "" else e.asString.trim()
                        labels.add(s.ifBlank { "未标注" })
                    }
                    if (labels.size != idxs.size) {
                        throw IOException("标注数量不符（${labels.size}/${idxs.size}），请重试")
                    }
                    return labels
                }
        }

        /**
         * 策略选择：缓存上次成功的策略，参数/模型不支持时自动降级（400/404/422）
         */
        private fun labelWithStrategies(
            apiKey: String,
            segments: List<NovelSegment>,
            idxs: List<Int>,
            knownSpeakers: Set<String>,
            onDelta: (Int) -> Unit = {}
        ): List<String> {
            val cached = llmStrategyIdx
            val order = if (cached != null)
                listOf(cached) + llmStrategies.indices.filter { it != cached }
            else llmStrategies.indices.toList()

            var lastErr: IOException? = null
            for (si in order) {
                try {
                    val r = labelChunkWithRetry(apiKey, segments, idxs, knownSpeakers, si, onDelta)
                    llmStrategyIdx = si
                    return r
                } catch (e: IOException) {
                    val msg = e.message ?: ""
                    if ("API 400" in msg || "API 404" in msg || "API 422" in msg) {
                        lastErr = e
                        continue
                    }
                    throw e
                }
            }
            throw lastErr ?: IOException("无可用模型策略")
        }

        /** 带重试的标注（网络错误退避重试；参数错误不重试直接上拋） */
        private fun labelChunkWithRetry(
            apiKey: String,
            segments: List<NovelSegment>,
            idxs: List<Int>,
            knownSpeakers: Set<String>,
            strategyIdx: Int,
            onDelta: (Int) -> Unit = {}
        ): List<String> {
            var lastError: IOException? = null
            for (attempt in 0..3) {
                try {
                    return labelChunk(apiKey, segments, idxs, knownSpeakers, strategyIdx, onDelta)
                } catch (e: IOException) {
                    val msg = e.message ?: ""
                    if ("API 400" in msg || "API 404" in msg || "API 422" in msg) throw e
                    lastError = e
                    if (attempt < 3) {
                        try {
                            Thread.sleep((attempt + 1) * 4000L)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            }
            throw lastError ?: IOException("未知错误")
        }

        fun llmMaxChars(): Int = LLM_MAX_CHARS

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
    private fun buildMessages(req: TtsRequest): MutableList<JsonObject> {
        val messages = mutableListOf<JsonObject>()
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
        messages.add(JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", req.text)
        })
        return messages
    }

    fun synthesize(req: TtsRequest): ByteArray {
        val requestBody = JsonObject().apply {
            addProperty("model", req.model)
            add("messages", gson.toJsonTree(buildMessages(req)))
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

    /**
     * 带重试的合成（网络抖动自动退避重试，仅重试 IO 错误）
     */
    fun synthesizeWithRetry(req: TtsRequest, retries: Int = 3): ByteArray {
        var lastError: IOException? = null
        for (attempt in 0..retries) {
            try {
                return synthesize(req)
            } catch (e: IOException) {
                lastError = e
                if (attempt < retries) {
                    try {
                        Thread.sleep((attempt + 1) * 3000L)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        throw lastError ?: IOException("未知错误")
    }

    /**
     * 流式文本转语音：逐块回调 PCM16（24kHz mono），边收边播
     * 返回完整 PCM 数据（供保存为 WAV）
     * 注意：仅 mimo-v2.5-tts 支持低延迟流式；其余模型为兼容模式（末尾一次返回）
     */
    fun synthesizeStream(req: TtsRequest, onChunk: (ByteArray) -> Unit): ByteArray {
        val requestBody = JsonObject().apply {
            addProperty("model", req.model)
            add("messages", gson.toJsonTree(buildMessages(req)))
            addProperty("stream", true)
            add("modalities", gson.toJsonTree(listOf("text", "audio")))
            add("audio", JsonObject().apply {
                addProperty("format", "pcm16")
                if (req.voice != null) addProperty("voice", req.voice)
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

        val chunks = mutableListOf<ByteArray>()
        response.body?.source()?.use { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val dataStr = line.substring(6)
                if (dataStr.trim() == "[DONE]") break
                try {
                    val chunk = JsonParser.parseString(dataStr).asJsonObject
                    val choices = chunk.getAsJsonArray("choices") ?: continue
                    if (choices.size() == 0) continue
                    val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: continue
                    if (!delta.has("audio") || delta.get("audio").isJsonNull) continue
                    val audio = delta.getAsJsonObject("audio")
                    if (!audio.has("data") || audio.get("data").isJsonNull) continue
                    val decoded = Base64.decode(audio.get("data").asString, Base64.DEFAULT)
                    if (decoded.isNotEmpty()) {
                        chunks.add(decoded)
                        onChunk(decoded)
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }

        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, result, offset, c.size)
            offset += c.size
        }
        return result
    }
}
