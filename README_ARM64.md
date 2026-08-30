# ARM64 (Termux) 环境构建说明

## 问题

Android SDK、Gradle 等工具主要针对 x86_64 架构，在 Termux (ARM64) 上直接构建比较困难。

## 解决方案

### 方案一：使用电脑构建（推荐）

1. 将项目传输到电脑
2. 用 Android Studio 打开构建
3. 将 APK 传回手机安装

```bash
# 在 Termux 中打包项目
cd ~/xiaomi-tts-app
tar -czf ~/xiaomi-tts-app.tar.gz .

# 传输到电脑（多种方式）
# 1. 使用 Termux 的共享存储
cp ~/xiaomi-tts-app.tar.gz /sdcard/Download/

# 2. 使用 SSH
scp ~/xiaomi-tts-app.tar.gz user@电脑IP:~/

# 3. 使用 Termux:Boot 或其他文件传输工具
```

### 方案二：使用 proot-distro 运行 Ubuntu

```bash
# 安装 proot-distro
pkg install proot-distro

# 安装 Ubuntu
proot-distro install ubuntu

# 进入 Ubuntu
proot-distro login ubuntu

# 在 Ubuntu 中安装 JDK 和 Android SDK
apt update
apt install -y openjdk-17-jdk wget unzip

# 下载 Android SDK 命令行工具
cd /opt
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mv cmdline-tools latest

# 设置环境变量
export ANDROID_HOME=/opt
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools

# 接受许可并安装组件
sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0"

# 进入项目目录构建
cd /path/to/xiaomi-tts-app
chmod +x gradlew
./gradlew assembleDebug
```

### 方案三：纯 Python + Web 方案

如果你不需要原生 Android 应用，可以直接用之前创建的 Python 脚本：

```bash
# 安装依赖
cd ~/xiaomi-tts-app
uv venv
source .venv/bin/activate
uv pip install requests

# 使用语音合成
python3 ../xiaomi_tts_app.py --text "你好世界" --output hello.wav

# 或者创建一个简单的 Web 界面
```

### 方案四：Termux + Termux:X11

如果安装了 Termux:X11，可以尝试图形界面方案：

```bash
pkg install termux-x11-nightly

# 启动 X11 服务
termux-x11 :0 &
export DISPLAY=:0

# 然后可以尝试运行图形化工具
```

## 快速开始：使用 Python 版本

既然原生构建困难，建议直接使用 Python 版本：

```bash
cd ~/xiaomi-tts-app

# 如果还没有虚拟环境
uv venv
. .venv/bin/activate
uv pip install requests

# 运行交互模式
python3 ../xiaomi_tts_app.py

# 或命令行模式
python3 ../xiaomi_tts_app.py --text "你好，我是小米语音合成。"
```

## 项目文件说明

```
xiaomi-tts-app/
├── app/                          # Android 应用源码（需要在电脑上构建）
│   ├── build.gradle.kts
│   └── src/main/java/...
├── README.md                     # 完整说明文档
├── README_ARM64.md              # 本文档
└── BUILD_INSTRUCTIONS.md         # 构建指南

../xiaomi_tts_app.py             # Python 版本（可直接在 Termux 运行）
../tts_example.py                # Python API 示例
```

## 总结

| 方案 | 难度 | 推荐度 | 说明 |
|------|------|--------|------|
| 电脑构建 | ⭐⭐ | ⭐⭐⭐⭐⭐ | 最简单可靠 |
| proot Ubuntu | ⭐⭐⭐⭐ | ⭐⭐⭐ | 可行但复杂 |
| Python 版本 | ⭐ | ⭐⭐⭐⭐ | 直接可用 |
| Termux:X11 | ⭐⭐⭐⭐⭐ | ⭐⭐ | 不推荐 |

**推荐**：直接使用 Python 版本，或者把项目传到电脑上用 Android Studio 构建。