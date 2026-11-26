# 许可证清单

> 状态：引入新依赖时，先更新本表；标"待核实"的，发布前要查清。

## 1. 本项目

| 目录或仓库 | 许可证 |
|---|---|
| `protocol/` 下的文档 | CC BY 4.0 |
| `protocol/conformance/` | Apache-2.0 |
| `server/`、`android/` | Apache-2.0 |
| `manual/`、官网内容 | CC BY 4.0 |
| 其余代码、GeoCrew-Native、GeoCrew-Plugins | Apache-2.0 |

## 2. 计划使用的第三方组件

| 组件 | 许可证 | 备注 |
|---|---|---|
| GDAL | MIT | 排除属于 GPL 的可选依赖（如 poppler），GeoCrew-Native 的 CI 逐个检查许可证 |
| PROJ | MIT | |
| GEOS | LGPL-2.1 | 安卓上编成单独的 .so，保证用户可以替换重新链接。服务端辅助程序是静态链接，本项目完全开源，满足要求；闭源的分叉要自己处理好 LGPL 的义务 |
| GNU libiconv | LGPL-2.1+ | 安卓自带的 iconv 读不了 GBK 编码的 Shapefile，所以要带上它（M1 验证发现）。和 GEOS 一样编成单独的 .so。只带库，不带属于 GPL 的 iconv 命令行程序 |
| GDAL、PROJ 的间接依赖 | 宽松许可 | libtiff（libtiff）、libgeotiff（MIT）、libpng（libpng-2.0）、libjpeg-turbo（BSD-3-Clause 与 IJG）、libwebp（BSD-3-Clause）、zstd（BSD-3-Clause 与 GPL-2.0 双许可，按 BSD-3-Clause 用）、zlib（Zlib）、liblzma（0BSD）、Expat（MIT）、json-c（MIT）、nlohmann-json（MIT），以及编译期用的 fast_float、libdivide |
| libc++（NDK） | Apache-2.0 WITH LLVM-exception | 安卓上以 libc++_shared.so 随应用发布 |
| SQLite | 公有领域 | |
| MapLibre Native | BSD-2-Clause | 只要求在开源许可页里附上版权声明和许可证全文，不要求在地图上显示它的标志 |
| QuickJS | MIT | 插件引擎，待技术验证 |
| llama.cpp | MIT | 手机端模型，以后再用 |
| MNN | Apache-2.0 | 手机端模型，以后再用 |
| sherpa-onnx、Vosk | Apache-2.0 | 语音模型本身的许可待核实 |
| ZXing、CameraX 等 AndroidX 库 | Apache-2.0 | |
| Kotlin 标准库、kotlinx.coroutines、kotlinx.serialization | Apache-2.0 | 安卓客户端的基础库，见 ADR 0016 |
| Jetpack Compose、Material 3 | Apache-2.0 | 安卓客户端的界面，见 ADR 0016 |
| JUnit 4 | EPL-1.0 | 只在测试时用，不随应用发布 |
| AndroidX Test（runner、ext.junit） | Apache-2.0 | 仪器测试用，不随应用发布，见 ADR 0016 |
| UnifiedPush 连接库 | Apache-2.0 | 待核实 |
| coder/websocket | ISC | 服务端 |
| pgx | MIT | 服务端 |
| modernc.org/sqlite | BSD-3-Clause | 服务端 |

服务端的间接依赖（golang.org/x 系列、google/uuid、modernc 系列等）都是 BSD 或 MIT 类许可，发布前用工具生成完整清单。

## 3. 数据

| 数据 | 条款 |
|---|---|
| EPSG 数据集 | EPSG 的使用条款：允许随软件分发，须保留出处和条款说明（细节待核实）。随 PROJ 的 proj.db 打进安卓应用 |
| OpenStreetMap | ODbL，须署名；衍生数据库须以同样条款共享。我们不分发 |
| Copernicus DEM | 免费，须署名，署名原文待核实 |
| AWS Terrain Tiles | 按数据来源署名，待核实 |
| 天地图及各家商业图源 | 按各自的服务条款，由用户自带 Key 使用 |

## 4. 模型

| 内容 | 条款 |
|---|---|
| 通义千问开源模型（Qwen3 等） | 多数尺寸用 Apache-2.0，具体到所选尺寸要核实 |
| DeepSeek 开源模型 | R1 用 MIT，允许蒸馏；用它的接口输出做训练之前，要核对接口条款（待核实） |
| OpenAI、Anthropic 等的输出 | 条款禁止用来训练与其竞争的模型，**不拿来训练** |

## 5. 发布要附带的

- 每次发布都附上第三方声明文件。
- 对 LGPL 组件，写明源码的获取方式。
