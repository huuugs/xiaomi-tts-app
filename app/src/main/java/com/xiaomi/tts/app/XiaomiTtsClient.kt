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

class XiaomiTtsClient {
    
    companion object {
        private const val API_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"
        private const val API_KEY = "tp-c9lxyryw7gaxvzy7hiqoetywgma22n75plh81xjw7dayznxq"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * 文本转语音
     */
    fun synthesize(
        text: String,
        model: String = "mimo-v2.5-tts",
        style: String? = null
    ): ByteArray {
        // 构建 messages
        val messages = mutableListOf<JsonObject>()
        
        // 如果有风格描述，放在 user 消息中
        if (!style.isNullOrBlank()) {
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", style)
            })
        }
        
        // 目标文本放在 assistant 消息中
        messages.add(JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text)
        })
        
        // 构建请求数据
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(messages))
            addProperty("stream", false)
        }
        
        val request = Request.Builder()
            .url("$API_BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("API 调用失败: ${response.code} - ${response.message}")
        }
        
        val responseBody = response.body?.string() ?: throw IOException("响应体为空")
        
        // 解析响应
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
     * 流式文本转语音
     */
    fun synthesizeStream(
        text: String,
        model: String = "mimo-v2.5-tts",
        style: String? = null,
        onProgress: ((ByteArray) -> Unit)? = null
    ): ByteArray {
        // 构建 messages
        val messages = mutableListOf<JsonObject>()
        
        // 如果有风格描述，放在 user 消息中
        if (!style.isNullOrBlank()) {
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", style)
            })
        }
        
        // 目标文本放在 assistant 消息中
        messages.add(JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text)
        })
        
        // 构建请求数据
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(messages))
            addProperty("stream", true)
            add("audio", JsonObject().apply {
                addProperty("format", "pcm16")
            })
        }
        
        val request = Request.Builder()
            .url("$API_BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("API 调用失败: ${response.code} - ${response.message}")
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
                        // 解析错误，继续处理下一行
                        continue
                    }
                }
            }
        }
        
        // 合并所有音频块
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