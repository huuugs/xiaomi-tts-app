package com.xiaomi.tts.app

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var text by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var selectedModel by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    
    val models = listOf(
        "mimo-v2.5-tts" to "预置音色",
        "mimo-v2.5-tts-voicedesign" to "音色设计",
        "mimo-v2.5-tts-voiceclone" to "音色克隆"
    )
    
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
                if (text.isBlank()) {
                    Toast.makeText(context, "请输入要合成的文本", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                
                scope.launch {
                    isLoading = true
                    try {
                        val audioData = withContext(Dispatchers.IO) {
                            val client = XiaomiTtsClient()
                            client.synthesize(
                                text = text,
                                model = models[selectedModel].first,
                                style = style.ifBlank { null }
                            )
                        }
                        
                        // 保存到文件
                        val fileName = "tts_output_${System.currentTimeMillis()}.wav"
                        val file = File(context.cacheDir, fileName)
                        FileOutputStream(file).use { it.write(audioData) }
                        
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
            enabled = !isLoading && text.isNotBlank()
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
        
        // 播放控制
        if (isPlaying) {
            Button(
                onClick = {
                    mediaPlayer?.stop()
                    mediaPlayer?.prepare()
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
                    text = "1. 输入要合成的文本",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "2. 可选：输入风格描述，如 (开心)、(悲伤)、(东北话) 等",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "3. 选择要使用的模型",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "4. 点击「开始合成」按钮",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}