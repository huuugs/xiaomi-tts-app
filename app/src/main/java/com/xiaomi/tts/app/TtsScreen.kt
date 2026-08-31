package com.xiaomi.tts.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
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

/**
 * PCM 流式播放器（API 流式输出为 24kHz mono PCM16）
 */
class AudioStreamPlayer {
    private var track: AudioTrack? = null

    fun start() {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 24000 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    fun write(pcm: ByteArray) {
        track?.write(pcm, 0, pcm.size)
    }

    fun stop() {
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }
}

/**
 * 导出音频文件到系统音乐目录（流式，适合大文件）
 */
fun exportAudioFileToMusic(context: Context, wavFile: File): String {
    val fileName = "xiaomi_tts_${System.currentTimeMillis()}.wav"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/XiaomiTTS")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建媒体文件")
        resolver.openOutputStream(uri)?.use { out ->
            wavFile.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("无法写入文件")
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Music/XiaomiTTS/$fileName"
    } else {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "XiaomiTTS"
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        wavFile.inputStream().use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file.absolutePath
    }
}

// ── 分组标签选择对话框 ──
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TagPickerDialog(
    title: String,
    groups: List<Pair<String, List<String>>>,
    selected: Set<String> = emptySet(),
    multiSelect: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit = {},
    onPick: (String) -> Unit = {}
) {
    var temp by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groups.forEach { (group, tags) ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            group,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .width(40.dp)
                                .padding(top = 10.dp)
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { tag ->
                                if (multiSelect) {
                                    FilterChip(
                                        selected = tag in temp,
                                        onClick = {
                                            temp = if (tag in temp) temp - tag else temp + tag
                                        },
                                        label = { Text(tag, style = MaterialTheme.typography.labelMedium) }
                                    )
                                } else {
                                    AssistChip(
                                        onClick = { onPick(tag); onDismiss() },
                                        label = { Text(tag, style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiSelect) {
                TextButton(onClick = { onConfirm(temp) }) { Text("确定") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 下拉选择器 ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownSelector(
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = itemLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = modifier
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TtsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 设置（持久化）──
    val prefs = remember { context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var autoPlay by remember { mutableStateOf(prefs.getBoolean("auto_play", true)) }
    var streamMode by remember { mutableStateOf(prefs.getBoolean("stream_mode", false)) }
    var showKeyDialog by remember { mutableStateOf(false) }
    fun saveApiKey(key: String) {
        apiKey = key
        prefs.edit().putString("api_key", key).apply()
    }

    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 模式：0 预置音色 / 1 音色设计 / 2 音色克隆 / 3 小说模式
    var mode by remember { mutableIntStateOf(0) }
    val modes = listOf("预置音色", "音色设计", "音色克隆", "小说模式")
    val modelIds = listOf(
        "mimo-v2.5-tts",
        "mimo-v2.5-tts-voicedesign",
        "mimo-v2.5-tts-voiceclone"
    )

    // 模式状态
    var selectedVoice by remember { mutableStateOf("mimo_default") }
    var voiceDesignPrompt by remember { mutableStateOf("") }
    var optimizePreview by remember { mutableStateOf(false) }
    var cloneBytes by remember { mutableStateOf<ByteArray?>(null) }
    var cloneInfo by remember { mutableStateOf("") }
    var cloneMime by remember { mutableStateOf("") }
    var isConverting by remember { mutableStateOf(false) }

    // 文本与标签
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var naturalStyle by remember { mutableStateOf("") }
    var showNaturalStyle by remember { mutableStateOf(false) }

    // 对话框
    var showStyleDialog by remember { mutableStateOf(false) }
    var showAudioTagDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // 结果与历史
    var isLoading by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var audioData by remember { mutableStateOf<ByteArray?>(null) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var streamPlayer by remember { mutableStateOf(AudioStreamPlayer()) }
    var history by remember { mutableStateOf(emptyList<HistoryItem>()) }

    // 小说模式
    var novelName by remember { mutableStateOf<String?>(null) }
    var novelFullText by remember { mutableStateOf("") }
    var novelSegments by remember { mutableStateOf<List<NovelSegment>>(emptyList()) }
    var speakerVoices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isNovelBuilding by remember { mutableStateOf(false) }
    var novelProgress by remember { mutableStateOf("") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzeProgress by remember { mutableStateOf("") }
    var resumeState by remember { mutableStateOf<NovelBuildState?>(null) }

    fun refreshHistory() {
        history = HistoryStore.list()
    }
    LaunchedEffect(Unit) {
        refreshHistory()
        // 恢复未完成的广播剧生成任务
        val st = NovelBuildManager.load(context)
        if (st != null && st.completed < st.totalSegments) {
            resumeState = st
            novelName = st.novelName
            novelFullText = st.segments.joinToString("") { it.text }
            novelSegments = st.segments
            speakerVoices = st.voiceMap
        } else if (st != null) {
            NovelBuildManager.clear(context)
        }
    }

    fun playFile(file: File) {
        try {
            streamPlayer.stop()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { isPlaying = false }
                prepare()
                start()
            }
            isPlaying = true
        } catch (e: Exception) {
            errorMsg = "播放失败: ${e.message}"
        }
    }

    // 合成完成后的统一处理：保存历史 + 按设置决定是否自动播放
    fun finishResult(wav: ByteArray, modelId: String, modeName: String, text: String) {
        audioData = wav
        scope.launch {
            withContext(Dispatchers.IO) {
                val item = HistoryStore.add(modelId, modeName, text, wav)
                currentFile = item.file
            }
            refreshHistory()
            if (autoPlay) {
                currentFile?.let { playFile(it) }
            }
        }
    }

    fun doExport() {
        val file = currentFile ?: return
        scope.launch {
            isExporting = true
            try {
                val path = withContext(Dispatchers.IO) { exportAudioFileToMusic(context, file) }
                Toast.makeText(context, "已导出: $path", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                errorMsg = "导出失败\n\n${e.message}"
            } finally {
                isExporting = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doExport()
        else Toast.makeText(context, "未授予存储权限，无法导出", Toast.LENGTH_SHORT).show()
    }

    // 选择克隆音频
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isConverting = true
                try {
                    val (bytes, mime, fmtName) = withContext(Dispatchers.IO) {
                        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IOException("读取音频失败")
                        if (raw.isEmpty()) throw IOException("文件为空")
                        val m = XiaomiTtsClient.detectAudioMime(raw)
                        if (m != null) {
                            Triple(raw, m, if (m == "audio/wav") "WAV" else "MP3")
                        } else {
                            val wav = AudioConverter.decodeToWav(context, uri)
                            Triple(wav, "audio/wav", "WAV·已转换")
                        }
                    }
                    XiaomiTtsClient.buildCloneVoice(mime, bytes)
                    cloneBytes = bytes
                    cloneMime = mime
                    cloneInfo = "$fmtName · ${bytes.size / 1024} KB"
                    Toast.makeText(context, "音频已就绪（$fmtName ${bytes.size / 1024} KB）", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    cloneBytes = null
                    cloneInfo = ""
                    errorMsg = "音频处理失败\n\n${e.message}\n\n支持：MP3 / WAV 直接使用；M4A / AAC / OGG / AMR / FLAC / 视频音轨自动转为 WAV。"
                } finally {
                    isConverting = false
                }
            }
        }
    }

    // 导入 TXT 小说
    val pickTxt = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } ?: throw IOException("读取失败")
                    val content = NovelParser.decodeText(bytes)
                    novelName = uri.lastPathSegment?.substringAfterLast('/') ?: "novel.txt"
                    novelFullText = content
                    val segs = NovelParser.parse(content)
                    novelSegments = segs
                    speakerVoices = NovelParser.defaultVoices(NovelParser.speakers(segs))
                    val sp = NovelParser.speakers(segs)
                    val dialogueCount = segs.count { it.isDialogue }
                    Toast.makeText(
                        context,
                        "已解析 ${segs.size} 段（对白 $dialogueCount），角色 ${sp.size} 个",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    novelName = null
                    novelFullText = ""
                    novelSegments = emptyList()
                    errorMsg = "小说导入失败\n\n${e.message}"
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            streamPlayer.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小米语音合成") },
                actions = {
                    IconButton(onClick = { showKeyDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── 模式 Tab ──
            ScrollableTabRow(selectedTabIndex = mode, edgePadding = 0.dp) {
                modes.forEachIndexed { i, title ->
                    Tab(
                        selected = mode == i,
                        onClick = { mode = i },
                        text = { Text(title, maxLines = 1) }
                    )
                }
            }

            if (mode == 3) {
                // ════════ 小说模式 ════════
                OutlinedButton(
                    onClick = { pickTxt.launch(arrayOf("text/plain", "application/txt", "text/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (novelName == null) "导入 TXT 小说（自动识别角色）"
                        else "$novelName · 点击更换"
                    )
                }

                if (novelName == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.MenuBook, null)
                            Text("自动生成广播剧", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "1. 导入 TXT（自动识别 UTF-8 / GBK 编码）\n" +
                                        "2. 自动解析对白与旁白，识别「XX说：『…』」中的角色\n" +
                                        "3. 为每个角色分配预置音色\n" +
                                        "4. 逐段合成，拼接为完整音频",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    // 恢复任务提示
                    resumeState?.let { st ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "📌 上次生成中断于第 ${st.completed}/${st.totalSegments} 段",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    NovelBuildManager.clear(context)
                                    resumeState = null
                                }) { Text("放弃") }
                            }
                        }
                    }

                    val sp = NovelParser.speakers(novelSegments)
                    val totalChars = novelSegments.sumOf { it.text.length }
                    Text(
                        "${novelSegments.size} 段 · $totalChars 字 · ${sp.size} 个角色",
                        style = MaterialTheme.typography.bodySmall
                    )

                    // 智能分析（LLM 推断说话人，可选；任意长度自动分块）
                    if (isAnalyzing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (analyzeProgress.isEmpty()) "AI 分析中…（可能需要 30-60 秒）"
                                else "AI 分析中…（$analyzeProgress）",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                if (apiKey.isBlank()) {
                                    showKeyDialog = true
                                    return@OutlinedButton
                                }
                                scope.launch {
                                    isAnalyzing = true
                                    analyzeProgress = ""
                                    try {
                                        val segs = withContext(Dispatchers.IO) {
                                            XiaomiTtsClient.llmAnalyze(apiKey, novelFullText) { cur, total ->
                                                analyzeProgress = "$cur / $total 块"
                                            }
                                        }
                                        if (segs.isEmpty()) throw IOException("AI 未返回有效分段")
                                        novelSegments = segs
                                        speakerVoices = NovelParser.defaultVoices(NovelParser.speakers(segs))
                                        val newSp = NovelParser.speakers(segs)
                                        Toast.makeText(
                                            context,
                                            "智能分析完成：${segs.size} 段，角色 ${newSp.size} 个",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (e: Exception) {
                                        errorMsg = "智能分析失败\n\n${e.message}\n\n已保留规则解析结果"
                                    } finally {
                                        isAnalyzing = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isNovelBuilding
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("智能分析（AI 推断说话人，更准）")
                        }
                    }

                    Text("角色配音", style = MaterialTheme.typography.titleSmall)
                    val voiceItems = XiaomiTtsClient.PRESET_VOICES
                    sp.forEach { (name, cnt) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                if (name == "旁白") "旁白" else name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(72.dp),
                                maxLines = 1
                            )
                            Text(
                                "$cnt 段",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(38.dp)
                            )
                            DropdownSelector(
                                label = "音色",
                                items = voiceItems,
                                itemLabel = { it.second },
                                selected = voiceItems.first { it.first == (speakerVoices[name] ?: "mimo_default") },
                                onSelect = { speakerVoices = speakerVoices + (name to it.first) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (isNovelBuilding) {
                        val progressVal = novelProgress.split("/").let {
                            if (it.size == 2)
                                (it[0].toFloatOrNull() ?: 0f) / (it[1].toFloatOrNull() ?: 1f)
                            else 0f
                        }
                        LinearProgressIndicator(
                            progress = progressVal,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("正在合成第 $novelProgress 段…", style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            if (apiKey.isBlank()) {
                                showKeyDialog = true
                                return@Button
                            }
                            scope.launch {
                                isNovelBuilding = true
                                try {
                                    // 断点续传：从上次中断的段继续，PCM 追加写
                                    val startIdx = resumeState?.completed ?: 0
                                    val name = resumeState?.novelName ?: (novelName ?: "novel")
                                    val pcmFile = NovelBuildManager.pcmFile(context)
                                    val wavFile = File(NovelBuildManager.dir(context), "build.wav")

                                    withContext(Dispatchers.IO) {
                                        FileOutputStream(pcmFile, startIdx > 0).use { out ->
                                            for (i in startIdx until novelSegments.size) {
                                                novelProgress = "${i + 1}/${novelSegments.size}"
                                                val wav = XiaomiTtsClient(apiKey).synthesizeWithRetry(
                                                    TtsRequest(
                                                        model = "mimo-v2.5-tts",
                                                        text = novelSegments[i].text,
                                                        voice = speakerVoices[novelSegments[i].speaker]
                                                            ?: "mimo_default"
                                                    )
                                                )
                                                out.write(AudioConverter.wavToPcm(wav))
                                                // 每段完成即保存进度（断点续传）
                                                NovelBuildManager.save(
                                                    context,
                                                    NovelBuildState(
                                                        novelName = name,
                                                        totalSegments = novelSegments.size,
                                                        completed = i + 1,
                                                        segments = novelSegments,
                                                        voiceMap = speakerVoices
                                                    )
                                                )
                                            }
                                        }
                                        AudioConverter.pcmFileToWav(pcmFile, 24000, 1, wavFile)
                                    }
                                    pcmFile.delete()
                                    withContext(Dispatchers.IO) {
                                        val item = HistoryStore.addFile(
                                            "mimo-v2.5-tts", "小说广播剧", name, wavFile
                                        )
                                        currentFile = item.file
                                    }
                                    NovelBuildManager.clear(context)
                                    resumeState = null
                                    refreshHistory()
                                    if (autoPlay) currentFile?.let { playFile(it) }
                                    Toast.makeText(context, "广播剧生成完成！", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    errorMsg = "广播剧生成失败（第 $novelProgress 段）\n\n${e.message}\n\n已保存进度，可点击「继续生成」从中断处继续"
                                } finally {
                                    isNovelBuilding = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isNovelBuilding && !isAnalyzing && novelSegments.isNotEmpty()
                    ) {
                        Icon(Icons.Default.MenuBook, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isNovelBuilding -> "生成中 $novelProgress"
                                resumeState != null -> "继续生成（从第 ${resumeState!!.completed + 1}/${resumeState!!.totalSegments} 段）"
                                else -> "生成广播剧（${novelSegments.size} 段）"
                            }
                        )
                    }
                }
            } else {
                // ════════ 普通模式 0-2 ════════
                when (mode) {
                    0 -> {
                        val voiceItems = XiaomiTtsClient.PRESET_VOICES
                        DropdownSelector(
                            label = "音色",
                            items = voiceItems,
                            itemLabel = { it.second },
                            selected = voiceItems.first { it.first == selectedVoice },
                            onSelect = { selectedVoice = it.first }
                        )
                        if (streamMode) {
                            Text(
                                "✓ 流式播放已开启：边合成边播放",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    1 -> {
                        OutlinedTextField(
                            value = voiceDesignPrompt,
                            onValueChange = { voiceDesignPrompt = it },
                            label = { Text("音色描述（必填）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            placeholder = { Text("如：温柔的女声，语速慢，像深夜电台主播") },
                            supportingText = { Text("此模式下风格请用文本标签控制，如 (开心)") }
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = { showTemplateDialog = true }) {
                                Icon(Icons.Default.Notes, null, Modifier.size(16.dp))
                                Text(" 描述模板")
                            }
                            Spacer(Modifier.weight(1f))
                            Switch(checked = optimizePreview, onCheckedChange = { optimizePreview = it })
                            Text("润色", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    2 -> {
                        OutlinedButton(
                            onClick = {
                                pickAudio.launch(
                                    arrayOf(
                                        "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
                                        "audio/mp4", "audio/aac", "audio/ogg", "audio/*"
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isConverting
                        ) {
                            if (isConverting) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("转换中…")
                            } else {
                                Icon(Icons.Default.MusicNote, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (cloneBytes == null) "选择音频样本（自动支持常见格式）" else "$cloneInfo · 更换")
                            }
                        }
                    }
                }

                // 文本输入
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    label = { Text("要合成的文本") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    trailingIcon = {
                        if (textFieldValue.text.isNotEmpty()) {
                            IconButton(onClick = { textFieldValue = TextFieldValue("") }) {
                                Icon(Icons.Default.Close, contentDescription = "清空")
                            }
                        }
                    },
                    supportingText = {
                        val n = textFieldValue.text.length
                        Text(
                            buildString {
                                append("$n 字")
                                if (selectedTags.isNotEmpty()) append(" ｜ 风格: ${selectedTags.joinToString(" ")}")
                                if (n > 500) append(" ｜ 长文本耗时较久")
                            }
                        )
                    }
                )

                // 工具行
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showStyleDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Label, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (selectedTags.isEmpty()) "风格" else "风格 ${selectedTags.size}")
                    }
                    OutlinedButton(onClick = { showAudioTagDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.LibraryMusic, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("音频标签")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showTemplateDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Notes, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("文本模板")
                    }
                    if (mode != 1) {
                        OutlinedButton(
                            onClick = { showNaturalStyle = !showNaturalStyle },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (showNaturalStyle) "▾ 自然语言风格" else "▸ 自然语言风格")
                        }
                    }
                }

                // 已选风格标签
                if (selectedTags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        selectedTags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { selectedTags = selectedTags - tag },
                                label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                                trailingIcon = { Icon(Icons.Default.Close, "移除", Modifier.size(14.dp)) }
                            )
                        }
                    }
                }

                // 自然语言风格
                if (showNaturalStyle && mode != 1) {
                    OutlinedTextField(
                        value = naturalStyle,
                        onValueChange = { naturalStyle = it },
                        label = { Text("自然语言风格 / 导演模式") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = { Text("如：用轻快上扬的语调说，语速稍快") }
                    )
                }

                // 合成按钮
                Button(
                    onClick = {
                        if (apiKey.isBlank()) {
                            showKeyDialog = true
                            return@Button
                        }
                        val text = textFieldValue.text
                        when {
                            text.isBlank() -> {
                                Toast.makeText(context, "请输入要合成的文本", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            mode == 1 && voiceDesignPrompt.isBlank() -> {
                                Toast.makeText(context, "音色设计模式需要填写音色描述", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            mode == 2 && cloneBytes == null -> {
                                Toast.makeText(context, "请先选择音频样本", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                        }

                        val finalText = if (selectedTags.isEmpty()) text
                        else "(${selectedTags.joinToString(" ")})$text"
                        val voice = when (mode) {
                            0 -> selectedVoice
                            2 -> XiaomiTtsClient.buildCloneVoice(cloneMime, cloneBytes!!)
                            else -> null
                        }
                        val style = when (mode) {
                            1 -> voiceDesignPrompt
                            else -> naturalStyle.ifBlank { null }
                        }
                        val req = TtsRequest(
                            model = modelIds[mode],
                            text = finalText,
                            style = style,
                            voice = voice,
                            optimizeTextPreview = optimizePreview && mode == 1
                        )

                        scope.launch {
                            isLoading = true
                            try {
                                if (mode == 0 && streamMode) {
                                    // 流式：边收边播
                                    streamPlayer.start()
                                    val pcm = withContext(Dispatchers.IO) {
                                        XiaomiTtsClient(apiKey).synthesizeStream(req) { chunk ->
                                            streamPlayer.write(chunk)
                                        }
                                    }
                                    val wav = AudioConverter.pcmToWav(pcm, 24000, 1)
                                    finishResult(wav, modelIds[0], modes[0], finalText)
                                } else {
                                    val result = withContext(Dispatchers.IO) {
                                        XiaomiTtsClient(apiKey).synthesize(req)
                                    }
                                    finishResult(result, modelIds[mode], modes[mode], finalText)
                                }
                            } catch (e: Exception) {
                                streamPlayer.stop()
                                errorMsg = "合成失败\n\n${e.message}"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && !(mode == 2 && isConverting) && textFieldValue.text.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (streamMode && mode == 0) "流式合成播放中…" else "合成中…")
                    } else {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始合成")
                    }
                }
            }

            // ── 结果操作（共用）──
            if (audioData != null || currentFile != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (isPlaying) {
                                mediaPlayer?.stop()
                                isPlaying = false
                            } else {
                                currentFile?.let { playFile(it) }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Text(if (isPlaying) " 停止" else " 重播")
                    }
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) doExport()
                            else {
                                if (ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) doExport()
                                else permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isExporting
                    ) {
                        Icon(Icons.Default.Save, null)
                        Text(if (isExporting) " 导出中…" else " 导出")
                    }
                }
            }

            // ── 历史 ──
            if (history.isNotEmpty()) {
                TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.History, null, Modifier.size(16.dp))
                    Text(if (showHistory) " 收起历史（${history.size}）" else " 展开历史（${history.size}）")
                }
                if (showHistory) {
                    history.forEach { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            scope.launch {
                                                audioData = withContext(Dispatchers.IO) {
                                                    if (item.file.exists()) item.file.readBytes() else null
                                                }
                                                currentFile = item.file
                                            }
                                            playFile(item.file)
                                        }
                                ) {
                                    Text(
                                        "${item.timeText()} · ${item.modeName}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        item.preview(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2
                                    )
                                }
                                IconButton(onClick = {
                                    HistoryStore.delete(item.id)
                                    if (currentFile == item.file) {
                                        audioData = null
                                        currentFile = null
                                    }
                                    refreshHistory()
                                }) {
                                    Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ── 风格标签对话框 ──
    if (showStyleDialog) {
        TagPickerDialog(
            title = "风格标签（可多选）",
            groups = XiaomiTtsClient.STYLE_TAG_GROUPS,
            selected = selectedTags,
            multiSelect = true,
            onDismiss = { showStyleDialog = false },
            onConfirm = { selectedTags = it; showStyleDialog = false }
        )
    }

    // ── 音频标签对话框 ──
    if (showAudioTagDialog) {
        TagPickerDialog(
            title = "音频标签（插入到光标处）",
            groups = XiaomiTtsClient.AUDIO_TAG_GROUPS,
            multiSelect = false,
            onDismiss = { showAudioTagDialog = false },
            onPick = { tag ->
                val insert = "（$tag）"
                val pos = textFieldValue.selection.start
                val newText = textFieldValue.text.substring(0, pos) +
                        insert + textFieldValue.text.substring(textFieldValue.selection.end)
                textFieldValue = TextFieldValue(newText, TextRange(pos + insert.length))
            }
        )
    }

    // ── 模板对话框 ──
    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text(if (mode == 1) "音色描述模板" else "文本模板") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val templates = if (mode == 1) XiaomiTtsClient.VOICE_DESIGN_TEMPLATES
                    else XiaomiTtsClient.TEXT_TEMPLATES
                    templates.forEach { t ->
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (mode == 1) voiceDesignPrompt = t
                                    else textFieldValue = TextFieldValue(t, TextRange(t.length))
                                    showTemplateDialog = false
                                }
                        ) {
                            Text(
                                t,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text("关闭") }
            }
        )
    }

    // ── 设置对话框（API Key + 偏好）──
    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { saveApiKey(it) },
                        label = { Text("API Key（tp- 开头）") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "保存于本机。从小米 MiMo 开放平台获取。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("合成后自动播放", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = autoPlay,
                            onCheckedChange = {
                                autoPlay = it
                                prefs.edit().putBoolean("auto_play", it).apply()
                            }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("流式播放", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "仅预置音色模式：边合成边播放",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = streamMode,
                            onCheckedChange = {
                                streamMode = it
                                prefs.edit().putBoolean("stream_mode", it).apply()
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("完成") }
            }
        )
    }

    // ── 错误对话框 ──
    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMsg = null },
            title = { Text("出错了") },
            text = {
                SelectionContainer {
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { errorMsg = null }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("error", msg))
                    Toast.makeText(context, "已复制错误信息", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            }
        )
    }
}
