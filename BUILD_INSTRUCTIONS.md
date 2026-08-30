# 在 Termux 中构建 Android 应用

本指南介绍如何在 Termux 环境中构建小米语音合成 Android 应用。

## 前提条件

### 1. 安装必要的包

```bash
# 更新包管理器
pkg update

# 安装 JDK 17
pkg install openjdk-17

# 安装其他必要工具
pkg install git wget unzip
```

### 2. 配置 Android SDK

#### 方法一：使用现有 Android SDK

如果你的设备上已经安装了 Android SDK（例如通过 Android Studio），可以创建符号链接：

```bash
# 创建 Android SDK 目录
mkdir -p ~/android-sdk

# 创建符号链接（假设 SDK 在 /opt/android-sdk）
ln -s /opt/android-sdk ~/android-sdk

# 或者如果 SDK 在其他位置，修改路径
# ln -s /path/to/your/android-sdk ~/android-sdk
```

#### 方法二：下载 Android SDK 命令行工具

```bash
# 创建 SDK 目录
mkdir -p ~/android-sdk/cmdline-tools

# 下载命令行工具
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

# 解压
unzip commandlinetools-linux-11076708_latest.zip

# 重命名目录
mv cmdline-tools latest

# 设置环境变量
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

### 3. 接受许可协议

```bash
sdkmanager --licenses
```

### 4. 安装必要的 SDK 组件

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

## 构建应用

### 方法一：使用构建脚本

```bash
cd ~/xiaomi-tts-app
./build.sh
```

### 方法二：手动构建

```bash
cd ~/xiaomi-tts-app

# 给 gradlew 执行权限
chmod +x gradlew

# 构建 Debug APK
./gradlew assembleDebug
```

### 方法三：使用 Android Studio

1. 在电脑上打开 Android Studio
2. 选择 `File` → `Open`
3. 选择 `xiaomi-tts-app` 目录
4. 等待 Gradle 同步完成
5. 点击 `Run` 按钮

## 输出文件

构建成功后，APK 文件位于：

```
app/build/outputs/apk/debug/app-debug.apk
```

## 安装到设备

### 方法一：使用 ADB

```bash
# 连接设备（确保已启用 USB 调试）
adb devices

# 安装 APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 方法二：传输文件

1. 将 APK 文件传输到 Android 设备
2. 在设备上打开文件管理器
3. 找到并点击 APK 文件进行安装

## 环境变量配置

为了方便使用，可以将以下内容添加到 `~/.bashrc` 或 `~/.zshrc`：

```bash
# Android SDK
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/build-tools/34.0.0

# Java
export JAVA_HOME=/data/data/com.termux/files/usr
```

然后重新加载配置：

```bash
source ~/.bashrc
# 或
source ~/.zshrc
```

## 常见问题

### 1. 找不到 Java

**错误信息**: `command not found: java`

**解决方案**:
```bash
pkg install openjdk-17
```

### 2. 找不到 Android SDK

**错误信息**: `Android SDK not found`

**解决方案**:
```bash
# 检查 ANDROID_HOME 是否设置
echo $ANDROID_HOME

# 如果为空，设置它
export ANDROID_HOME=$HOME/android-sdk
```

### 3. Gradle 下载失败

**错误信息**: `Could not resolve dependencies`

**解决方案**:
- 检查网络连接
- 尝试使用代理
- 或者使用国内镜像源

修改 `settings.gradle.kts` 中的仓库配置：

```kotlin
pluginManagement {
    repositories {
        // 使用阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

### 4. 内存不足

**错误信息**: `OutOfMemoryError`

**解决方案**:
修改 `gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

### 5. 权限错误

**错误信息**: `Permission denied`

**解决方案**:
```bash
chmod +x gradlew
chmod +x build.sh
```

## 性能优化

### 1. 启用 Gradle 守护进程

在 `gradle.properties` 中添加：

```properties
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
```

### 2. 使用本地仓库

如果网络速度慢，可以配置本地 Maven 仓库：

```bash
# 创建本地仓库目录
mkdir -p ~/.m2/repository
```

## 调试应用

### 查看日志

```bash
# 连接设备后查看应用日志
adb logcat | grep "XiaomiTts"
```

### 清除应用数据

```bash
adb shell pm clear com.xiaomi.tts.app
```

### 卸载应用

```bash
adb uninstall com.xiaomi.tts.app
```

## 发布应用

### 签名 APK

```bash
# 生成签名密钥
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias xiaomi-tts

# 构建 Release APK
./gradlew assembleRelease

# 使用 jarsigner 签名
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore release-key.jks app/build/outputs/apk/release/app-release-unsigned.apk xiaomi-tts

# 使用 zipalign 优化
$ANDROID_HOME/build-tools/34.0.0/zipalign -v 4 app-release-unsigned.apk xiaomi-tts-release.apk
```

### 上传到应用商店

1. 签名 APK
2. 准备应用截图和描述
3. 上传到 Google Play 或其他应用商店

## 相关资源

- [Android 开发者文档](https://developer.android.com/docs)
- [Gradle 文档](https://gradle.org/docs/)
- [Termux 文档](https://wiki.termux.com/)
- [小米 MiMo 文档](https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5)