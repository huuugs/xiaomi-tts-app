#!/bin/bash

# 打包 Android 项目，方便在电脑上构建

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
PACKAGE_NAME="xiaomi-tts-app"
OUTPUT_DIR="/sdcard/Download"

echo "=== 打包小米语音合成 Android 项目 ==="
echo ""

# 检查项目目录
if [ ! -f "$PROJECT_DIR/app/build.gradle.kts" ]; then
    echo "错误: 找不到项目文件"
    exit 1
fi

# 创建临时目录
TEMP_DIR=$(mktemp -d)
TEMP_PACKAGE="$TEMP_DIR/$PACKAGE_NAME"

echo "1. 复制项目文件..."
cp -r "$PROJECT_DIR" "$TEMP_PACKAGE"

# 清理不需要的文件
echo "2. 清理不需要的文件..."
cd "$TEMP_PACKAGE"
rm -rf .gradle build app/build .idea *.iml
rm -f build.sh package_for_pc.sh
rm -f README_ARM64.md BUILD_INSTRUCTIONS.md

# 创建 .gitignore
cat > .gitignore << 'EOF'
# Gradle
.gradle/
build/
app/build/

# IDE
.idea/
*.iml

# OS
.DS_Store
Thumbs.db

# Local configuration
local.properties
EOF

# 返回上级目录
cd "$TEMP_DIR"

# 打包
echo "3. 打包项目..."
tar -czf "$OUTPUT_DIR/$PACKAGE_NAME.tar.gz" "$PACKAGE_NAME"

# 清理临时目录
rm -rf "$TEMP_DIR"

echo ""
echo "✅ 打包完成！"
echo ""
echo "文件位置: $OUTPUT_DIR/$PACKAGE_NAME.tar.gz"
echo "文件大小: $(ls -lh "$OUTPUT_DIR/$PACKAGE_NAME.tar.gz" | awk '{print $5}')"
echo ""
echo "下一步："
echo "1. 在电脑上获取这个文件"
echo "2. 解压后用 Android Studio 打开"
echo "3. 点击 Run 或 Build APK"
echo ""
echo "传输方式："
echo "  • 文件管理器：直接在 Downloads 文件夹找到"
echo "  • ADB：adb pull $OUTPUT_DIR/$PACKAGE_NAME.tar.gz ./"
echo "  • 共享存储：/sdcard/Download/"
echo ""
echo "提示：也可以同时传输 Python 版本："
echo "  cp $PROJECT_DIR/../xiaomi_tts_app.py $OUTPUT_DIR/"
echo "  cp $PROJECT_DIR/../tts_example.py $OUTPUT_DIR/"