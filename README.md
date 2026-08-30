# 小米语音合成 Android 应用

使用小米 MiMo-V2.5-TTS 系列模型进行语音合成的 Android 应用。

## 功能特性

- ✅ 支持三种语音合成模型
  - `mimo-v2.5-tts` - 预置音色语音合成
  - `mimo-v2.5-tts-voicedesign` - 音色设计
  - `mimo-v2.5-tts-voiceclone` - 音色克隆

- ✅ 风格控制
  - 自然语言控制：用自然语言描述想要的语音风格
  - 标签控制：使用预定义的风格标签，如 `(开心)`、`(悲伤)`、`(东北话)` 等

- ✅ 实时播放
  - 合成完成后自动播放
  - 支持停止播放

- ✅ 现代化 UI
  - 使用 Jetpack Compose 构建
  - Material 3 设计
  - 小米品牌色主题

## 系统要求

- Android 7.0 (API 24) 及以上
- 网络连接

## 构建步骤

### 使用 Android Studio

1. 打开 Android Studio
2. 选择 `File` → `Open`
3. 选择 `xiaomi-tts-app` 目录
4. 等待 Gradle 同步完成
5. 点击 `Run` 按钮或按 `Shift+F10`

### 使用命令行

```bash
cd xiaomi-tts-app

# 构建 Debug APK
./gradlew assembleDebug

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. **输入文本**：在文本框中输入要合成的文本
2. **设置风格**（可选）：输入风格描述，如：
   - `(开心)` - 使用开心的语气
   - `(悲伤)` - 使用悲伤的语气
   - `(东北话)` - 使用东北方言
   - `用温柔的语气说` - 自然语言描述
3. **选择模型**：根据需求选择合适的模型
4. **点击合成**：点击「开始合成」按钮
5. **播放音频**：合成完成后自动播放，可点击「停止播放」中断

## 风格示例

### 基础情绪
- `(开心)今天天气真好！`
- `(悲伤)我很难过。`
- `(愤怒)你怎么能这样做！`

### 复合情绪
- `(怅然)这么多年过去了，再走过那条街，心里一下子空了一块。`
- `(无奈)唉，这个项目又延期了。`

### 方言
- `(东北话)哎呀妈呀，这天儿也忒冷了吧！`
- `(粤语)呢个真係好正啊！`
- `(四川话)哎呀，这个火锅巴适得很嘛！`

### 角色扮演
- `(孙悟空)俺老孙来也！`
- `(林黛玉)花谢花飞花满天，红消香断有谁怜？`

### 唱歌
- `(唱歌)原谅我这一生不羁放纵爱自由`

## 项目结构

```
xiaomi-tts-app/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/xiaomi/tts/app/
│           │   ├── MainActivity.kt
│           │   ├── TtsScreen.kt
│           │   ├── XiaomiTtsClient.kt
│           │   └── ui/theme/
│           │       ├── Color.kt
│           │       ├── Theme.kt
│           │       └── Type.kt
│           └── res/
│               ├── values/
│               │   ├── colors.xml
│               │   ├── strings.xml
│               │   └── themes.xml
│               └── ...
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── README.md
```

## 技术栈

- **语言**: Kotlin
- **UI框架**: Jetpack Compose + Material 3
- **网络请求**: OkHttp
- **JSON解析**: Gson
- **异步处理**: Kotlin Coroutines
- **音频播放**: MediaPlayer

## API 信息

- **API 端点**: `https://token-plan-cn.xiaomimimo.com/v1/chat/completions`
- **认证方式**: Bearer Token
- **请求格式**: OpenAI 兼容格式

## 注意事项

1. 需要网络连接才能使用
2. API 密钥已内置，请勿泄露
3. 合成的音频文件会保存在应用缓存目录
4. 首次使用可能需要较长的响应时间

## 故障排除

### 网络错误
- 检查设备的网络连接
- 确认可以访问 `token-plan-cn.xiaomimimo.com`

### 合成失败
- 检查文本是否为空
- 确认 API 密钥有效
- 查看错误提示信息

### 无法播放音频
- 检查设备音量
- 确认音频文件已正确生成

## 许可证

本应用仅供学习和研究使用。

## 相关链接

- [小米 MiMo 文档](https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5)
- [Jetpack Compose 文档](https://developer.android.com/jetpack/compose)
- [Material 3 文档](https://m3.material.io/)