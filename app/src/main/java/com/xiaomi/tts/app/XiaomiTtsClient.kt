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

class XiaomiTtsClient(private val apiKey: String) {

    companion object {
        private const val API_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private fun buildMessages(text: String, style: String?): MutableList<JsonObject> {
        val messages = mutableListOf<JsonObject>()
        if (!style.isNullOrBlank()) {
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", style)
            })
        }
        messages.add(JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text)
        })
        return messages
    }

    private fun headers(): Headers = Headers.Builder()
        .add("Authorization", "Bearer $apiKey")
        .add("Content-Type", "application/json")
        .build()

    private fun parseError(response: Response): IOException {
        val errBody = try {
            response.body?.string() ?: ""
        } catch (e: Exception) {
            ""
        }
        return IOException("API 调用失败: ${response.code} - ${errBody.take(300)}")
    }

    /**
     * 文本转语音（非流式，返回 WAV 音频）
     */
    fun synthesize(
        text: String,
        model: String = "mimo-v2.5-tts",
        style: String? = null
    ): ByteArray {
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(buildMessages(text, style)))
            addProperty("stream", false)
            // 必须声明音频模态，否则服务端报错
            add("modalities", gson.toJsonTree(listOf("text", "audio")))
            add("audio", JsonObject().apply {
                addProperty("format", "wav")
            })
        }

        val request = Request.Builder()
            .url("$API_BASE_URL/chat/completions")
            .headers(headers())
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

        val choice = choices[0].asJsonObject
        val message = choice.getAsJsonObject("message")

        if (message == null || !message.has("audio")) {
            throw IOException("响应中没有音频数据")
        }

        val audio = message.getAsJsonObject("audio")
        val audioData = audio.get("data").asString

        return Base64.decode(audioData, Base64.DEFAULT)
    }

    /**
     * 流式文本转语音（返回 PCM16 数据）
     */
    fun synthesizeStream(
        text: String,
        model: String = "mimo-v2.5-tts",
        style: String? = null,
        onProgress: ((ByteArray) -> Unit)? = null
    ): ByteArray {
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(buildMessages(text, style)))
            addProperty("stream", true)
            add("modalities", gson.toJsonTree(listOf("text", "audio")))
            add("audio", JsonObject().apply {
                addProperty("format", "pcm16")
            })
        }

        val request = Request.Builder()
            .url("$API_BASE_URL/chat/completions")
            .headers(headers())
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw parseError(response)
        }

        val audioChunks = mutableListOf<ByteArray>()

        response.body?.source()?.use { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break

                if (line.startsWith("data: ")) {
                    val dataStr = line.substring(6)
                    if (dataStr.trim() == "[DONE]") {
                        break
                    }

                    try {
                        val chunk = JsonParser.parseString(dataStr).asJsonObject
                        val choices = chunk.getAsJsonArray("choices")

                        if (choices != null && choices.size() > 0) {
                            val choice = choices[0].asJsonObject
                            val delta = choice.getAsJsonObject("delta")

                            if (delta != null && delta.has("audio") && !delta.get("audio").isJsonNull) {
                                val audio = delta.getAsJsonObject("audio")
                                if (audio != null && audio.has("data") && !audio.get("data").isJsonNull) {
                                    val audioData = audio.get("data").asString
                                    val decodedData = Base64.decode(audioData, Base64.DEFAULT)
                                    audioChunks.add(decodedData)
                                    onProgress?.invoke(decodedData)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }

        val totalSize = audioChunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0

        for (chunk in audioChunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }

        return result
    }
}
