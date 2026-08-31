package com.xiaomi.tts.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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

// ── 分组标签选择对话框（风格多选 / 音频标签插入） ──
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

// ── 列表选择下拉框 ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownSelector(
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit
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
            modifier = Modifier
                .fillMaxWidth()
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

    val prefs = remember { context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var showKeyDialog by remember { mutableStateOf(false) }
    fun saveApiKey(key: String) {
        apiKey = key
        prefs.edit().putString("api_key", key).apply()
    }

    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 模式
    var mode by remember { mutableIntStateOf(0) }
    val modes = listOf("预置音色", "音色设计", "音色克隆")
    val modelIds = listOf("mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone")

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

    // 对话框开关
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
    var history by remember { mutableStateOf(emptyList<HistoryItem>()) }

    fun refreshHistory() {
        history = HistoryStore.list()
    }
    LaunchedEffect(Unit) { refreshHistory() }

    fun playFile(file: File) {
        try {
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

    fun doExport() {
        val data = audioData ?: return
        scope.launch {
            isExporting = true
            try {
                val path = withContext(Dispatchers.IO) { exportAudioToMusic(context, data) }
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

    // 选择克隆音频：mp3/wav 直接用，其他自动转码
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

    DisposableEffect(Unit) {
        onDispose { mediaPlayer?.release() }
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
            TabRow(selectedTabIndex = mode) {
                modes.forEachIndexed { i, title ->
                    Tab(selected = mode == i, onClick = { mode = i }, text = { Text(title) })
                }
            }

            // ── 模式配置（紧凑，下拉/单行）──
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
                }
                1 -> {
                    OutlinedTextField(
                        value = voiceDesignPrompt,
                        onValueChange = { voiceDesignPrompt = it },
                        label = { Text("音色描述（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = { Text("如：温柔的女声，语速慢，像深夜电台主播") }
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

            // ── 文本输入（首屏核心区）──
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

            // ── 工具行：风格 / 音频标签 / 模板 / 自然语言 ──
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
                OutlinedButton(
                    onClick = { showNaturalStyle = !showNaturalStyle },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (showNaturalStyle) "▾ 自然语言风格" else "▸ 自然语言风格")
                }
            }

            // 已选风格标签（可单个移除）
            if (selectedTags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    selectedTags.forEach { tag ->
                        InputChip(
                            selected = true,
                            onClick = { selectedTags = selectedTags - tag },
                            label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close, "移除",
                                    Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                }
            }

            // 自然语言风格（折叠）
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

            // ── 合成按钮 ──
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

                    scope.launch {
                        isLoading = true
                        try {
                            val result = withContext(Dispatchers.IO) {
                                XiaomiTtsClient(apiKey).synthesize(
                                    TtsRequest(
                                        model = modelIds[mode],
                                        text = finalText,
                                        style = style,
                                        voice = voice,
                                        optimizeTextPreview = optimizePreview && mode == 1
                                    )
                                )
                            }
                            audioData = result
                            withContext(Dispatchers.IO) {
                                val item = HistoryStore.add(modelIds[mode], modes[mode], finalText, result)
                                currentFile = item.file
                            }
                            refreshHistory()
                            currentFile?.let { playFile(it) }
                        } catch (e: Exception) {
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
                    Text("合成中…（长文本可能 1-2 分钟）")
                } else {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始合成")
                }
            }

            // ── 结果操作 ──
            if (audioData != null) {
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

            // ── 历史（默认折叠）──
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

    // ── 风格标签对话框（多选）──
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

    // ── 音频标签对话框（点击插入光标处）──
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

    // ── 模板对话框（点击填入）──
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
                    templates.forEachIndexed { i, t ->
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

    // ── API Key 对话框 ──
    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("设置 API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { saveApiKey(it) },
                        label = { Text("API Key（tp- 开头）") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("保存于本机。从小米 MiMo 开放平台获取。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("完成") }
            }
        )
    }

    // ── 错误详情对话框 ──
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
