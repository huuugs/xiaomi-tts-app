#!/bin/bash

# 小米语音合成 Android 应用构建脚本

set -e

echo "=== 小米语音合成 Android 应用构建脚本 ==="
echo ""

# 检查是否安装了 Java
if ! command -v java &> /dev/null; then
    echo "错误: 未找到 Java，请先安装 JDK"
    echo "在 Termux 中运行: pkg install openjdk-17"
    exit 1
fi

# 检查 JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    echo "警告: JAVA_HOME 未设置，尝试自动检测..."
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java))))
fi

echo "JAVA_HOME: $JAVA_HOME"
echo "Java 版本: $(java -version 2>&1 | head -1)"
echo ""

# 进入项目目录
cd "$(dirname "$0")"

# 检查 gradlew 是否存在
if [ ! -f "./gradlew" ]; then
    echo "生成 Gradle Wrapper..."
    # 如果没有 gradlew，需要先生成
    # 这里假设用户已经配置好 Android SDK
    echo "请确保已安装 Android SDK 并配置好环境变量"
    echo "或者使用 Android Studio 打开项目进行构建"
    exit 1
fi

# 给 gradlew 执行权限
chmod +x ./gradlew

echo "开始构建 Debug APK..."
echo ""

# 构建 Debug APK
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ 构建成功！"
    echo ""
    echo "APK 文件位置:"
    echo "  app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "安装到设备:"
    echo "  adb install app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "或者将 APK 文件传输到 Android 设备进行安装"
else
    echo ""
    echo "❌ 构建失败，请检查错误信息"
    exit 1
fi