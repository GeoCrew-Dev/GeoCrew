# 同勘 GeoCrew Android 客户端

开源的野外地质移动工具：天地图底图、实时定位、野外记录与小组协同。

> 状态：早期骨架。目前可加载天地图矢量底图；定位、定点、轨迹、产状等功能正在开发。规划见 [geocrew-protocol](https://github.com/GeoCrew-Dev/geocrew-protocol/blob/main/docs/roadmap.md)。

## 编译

需要 JDK 17 和 Android SDK（compileSdk 35）。

1. 到[天地图开放平台](https://console.tianditu.gov.cn/)申请浏览器端或 Android 端 Key。
2. 在项目根目录的 `local.properties` 中加入：
   ```properties
   tianditu.key=你的Key
   ```
3. 编译调试版：
   ```bash
   ./gradlew assembleDebug
   ```

每次推送到 `main`，GitHub Actions 会自动编译调试版 APK，可在对应运行记录的 Artifacts 中下载。CI 中的 Key 来自仓库 Secrets 的 `TIANDITU_KEY`，未配置时 APK 会提示填写 Key。

## 系统要求

Android 10（API 29）及以上。

## 许可

Apache-2.0
