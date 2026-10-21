# 番茄小说下载器 Android

Android 版番茄小说（Fanqie Novel）下载器，无需 root，无需反编译加密库。

## 原理

与 PC 版方案不同，本项目**直接调用番茄小说 App 中的原生 SO 库** `libmetasec_ml.so` 进行签名生成，而非使用 unidbg 等 ARM 模拟器方案。SO 通过 `dlopen` 加载并直接调用其签名函数，签名生成性能远优于模拟方案。

## 功能

- 搜索小说并浏览搜索结果
- 按「搜索结果 → 章节目录 → 章节预览」逐级查看内容；预览从所选章节开始批量显示最多 5 章，并可前后翻阅
- 在章节预览中按选定倍速连续听书；每次批量获取最多 5 章的音频地址，减少接口请求并连续播放，正文滚动随音频进度同步，支持切到后台/锁屏播放
- 自动记录最近阅读的书籍和章节，可从主界面直接继续阅读
- 按 Book ID 下载全部/指定章节
- 输出 EPUB 格式（带封面、目录）或 TXT 格式
- 下载完成后保存到公共 Download 目录
- 支持 Android 8.0+ (API 26) 的 arm64 设备

## 构建要求

- JDK 17+
- Android SDK (compileSdk 34)
- Android NDK r25c
- Gradle 8.7+ (由 wrapper 自动管理)

## 快速开始

```bash
# 设置环境变量
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/25.2.9519653
export JAVA_HOME=/path/to/jdk-21

# 构建
./gradlew assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. 打开 App，输入书名或搜索关键词
2. 在搜索结果中点击一本书，打开章节目录
3. 在章节目录中点击某一章，预览页从该章开始批量加载最多 5 章
4. 在章节范围上方选择倍速（默认 1.5 倍），点击「听书」从当前章节组开始连续播放；每次批量请求最多 5 章的音频地址，章节播放结束后自动续播，章节组播放完成后自动切换下一组，再次点击停止
5. 使用「前5章 / 后5章」切换章节范围，选择下载当前范围或全书，再点击「EPUB」或「TXT」
6. 等待下载完成，文件将保存到 Download 目录
7. 下次打开 App 时，可点击搜索按钮下方显示的最近阅读书名，直接继续预览上次阅读章节

## 技术细节

- 核心签名 SO：`libmetasec_ml.so`（来自番茄小说 App v6.8.1.32）
- JNI 桥接：`fqsigner.cpp` 通过 `dlopen` + `/proc/self/maps` 解析 SO 基址，调用偏移 `0x168c80` 的签名函数
- API 兼容：仅使用 Android API 26 可用接口，无 Java 9+ API 依赖

## 免责声明

本项目仅供学习研究使用，不得用于商业用途。下载的内容请于 24 小时内删除。
