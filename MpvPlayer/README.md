# MPV Player (Android 原生)

基于 **libmpv + FFmpeg + libplacebo + libass + dav1d** 的原生 Android 播放器，
渲染管线与桌面 mpv 的 vo=gpu 一致，因此画质与 mpv 相同（EWA Lanczos 缩放、去色带、
HDR 色调映射、插帧、libass 字幕渲染等）。

## 功能
- 画质预设：快速 / 均衡 / mpv 高质量（ewa_lanczossharp + deband + sigmoid-upscaling + 插帧）
- 硬解 (mediacodec) 优先，失败自动回退软解 (FFmpeg / dav1d)
- 支持 MKV / MP4 / HEVC / AV1 / DTS / TrueHD / ASS / PGS / HLS / DASH / SMB / HTTP(S)
- 手势：单击显隐控制栏、双击左右 ±10 秒、左半屏上下滑动调亮度、右半屏上下滑动调音量、横向滑动快进
- 倍速、音轨 / 字幕轨切换、外挂字幕、截图（screenshot-to-file）
- 通过 Intent 打开其他应用分享的视频 (ACTION_VIEW)

## 构建 APK
需要 JDK 17 + Android SDK (compileSdk 34)。

    # 方式一：Android Studio 直接打开本目录 -> Build -> Build APK(s)
    # 方式二：命令行（本工程不含 gradle wrapper，需本机有 Gradle 8.7+）
    gradle assembleDebug
    # 输出：app/build/outputs/apk/debug/app-debug.apk

    # 方式三：把工程推到 GitHub，.github/workflows/build.yml 会自动构建并上传 APK

## libmpv 依赖说明
本工程使用 Maven Central 上的预编译 AAR：dev.jdtech.mpv:libmpv:0.5.1
（基于 mpv-android，内含 4 个 ABI）。如果你换用其它版本 / 其它 fork，
请核对 MPVLib 的包名和 EventObserver 接口方法签名并相应调整 MpvEngine.kt。
也可以用 mpv-android 的 buildscripts 自行编译 libmpv.so 与 MPVLib.kt。

## 可选：外挂 GLSL 着色器（更强的放大）
把 FSRCNNX / Anime4K / CAS 等 .glsl 放入 app/src/main/assets/shaders/ ，
MpvEngine 会在「mpv 高质量」预设下自动加载。

## 可选：HTTPS 证书
把 cacert.pem 放入 app/src/main/assets/ ，MpvEngine 会自动配置 tls-ca-file。

## 许可
libmpv 与 FFmpeg 二进制整体为 GPL-3.0-or-later，分发 APK 时请遵守 GPL。
