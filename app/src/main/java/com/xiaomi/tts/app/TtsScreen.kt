package com.xiaomi.tts.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 导出音频到系统音乐目录（Music/XiaomiTTS）
 * API 29+ 使用 MediaStore，无需权限；旧版本写公共目录（需要存储权限）
 */
fun exportAudioToMusic(context: Context, data: ByteArray): String {
    val fileName = "xiaomi_tts_${System.currentTimeMillis()}.wav"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/XiaomiTTS")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建媒体文件")
        resolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw IOException("无法写入文件")
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "Music/XiaomiTTS/$fileName"
    } else {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "XiaomiTTS"
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(data) }
        file.absolutePath
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }

    var text by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var selectedModel by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var audioData by remember { mutableStateOf<ByteArray?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    val models = listOf(
        "mimo-v2.5-tts" to "预置音色",
        "mimo-v2.5-tts-voicedesign" to "音色设计",
        "mimo-v2.5-tts-voiceclone" to "音色克隆"
    )

    // 保存 API Key
    fun saveApiKey(key: String) {
        apiKey = key
        prefs.edit().putString("api_key", key).apply()
    }

    // 导出音频
    fun doExport() {
        val data = audioData ?: return
        scope.launch {
            isExporting = true
            try {
                val path = withContext(Dispatchers.IO) { exportAudioToMusic(context, data) }
                Toast.makeText(context, "已导出: $path", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isExporting = false
            }
        }
    }

    // 旧版本（API < 29）导出需要存储权限
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            doExport()
        } else {
            Toast.makeText(context, "未授予存储权限，无法导出", Toast.LENGTH_SHORT).show()
        }
    }

    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        Text(
            text = "小米语音合成",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // API Key 设置
        OutlinedTextField(
            value = apiKey,
            onValueChange = { saveApiKey(it) },
            label = { Text("API Key（tp- 开头）") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("从小米 MiMo 平台获取") },
            visualTransformation = PasswordVisualTransformation()
        )

        // 文本输入
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("要合成的文本") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6
        )

        // 风格输入
        OutlinedTextField(
            value = style,
            onValueChange = { style = it },
            label = { Text("风格描述（可选）") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：(开心) 或 用温柔的语气说") }
        )

        // 模型选择
        Text(
            text = "选择模型",
            style = MaterialTheme.typography.titleMedium
        )

        models.forEachIndexed { index, (modelId, modelName) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedModel == index,
                    onClick = { selectedModel = index }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = modelId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 合成按钮
        Button(
            onClick = {
                if (apiKey.isBlank()) {
                    Toast.makeText(context, "请先设置 API Key", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (text.isBlank()) {
                    Toast.makeText(context, "请输入要合成的文本", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                scope.launch {
                    isLoading = true
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val client = XiaomiTtsClient(apiKey)
                            client.synthesize(
                                text = text,
                                model = models[selectedModel].first,
                                style = style.ifBlank { null }
                            )
                        }
                        audioData = result

                        // 保存到缓存用于播放
                        val fileName = "tts_output.wav"
                        val file = File(context.cacheDir, fileName)
                        FileOutputStream(file).use { it.write(result) }

                        // 播放音频
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .build()
                            )
                            setDataSource(file.absolutePath)
                            prepare()
                            start()
                            setOnCompletionListener {
                                isPlaying = false
                            }
                        }
                        isPlaying = true

                        Toast.makeText(context, "语音合成成功！", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "合成失败: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && text.isNotBlank() && apiKey.isNotBlank()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("合成中...")
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "合成"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始合成")
            }
        }

        // 导出按钮
        if (audioData != null) {
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        doExport()
                    } else {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            doExport()
                        } else {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isExporting
            ) {
                if (isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出中...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "导出"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出到音乐文件夹 (Music/XiaomiTTS)")
                }
            }
        }

        // 播放控制
        if (isPlaying) {
            Button(
                onClick = {
                    mediaPlayer?.stop()
                    isPlaying = false
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "停止"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("停止播放")
            }
        }

        // 使用说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "1. 设置 API Key（首次使用需要，保存在本机）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "2. 输入要合成的文本",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "3. 可选：输入风格描述，如 (开心)、(悲伤)、(东北话) 等",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "4. 选择模型后点击「开始合成」",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "5. 点击「导出到音乐文件夹」保存 WAV 文件",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
