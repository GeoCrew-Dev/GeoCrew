# 同勘 GeoCrew Android 客户端

开源的野外地质移动工具：多图源底图、实时定位、野外记录与小组协同。

> 状态：早期开发。规划见 [geocrew-protocol](https://github.com/GeoCrew-Dev/GeoCrew-Protocol/blob/main/docs/roadmap.md)。

## 底图与天地图 Key

应用**不内置任何地图 Key**。天地图的个人 Key 有每日调用额度，多人共用一个 Key 很快就会被限流，所以每位用户使用自己的 Key：

1. 在[天地图官网](https://www.tianditu.gov.cn/)注册开发者账号，进入控制台创建应用，应用类型选「服务端」。
2. 打开同勘 → 设置 → 天地图 Key，粘贴后点「保存并校验」。

没有 Key 也能用：图层里可以选 OpenStreetMap 或空白底图，也可以在设置里添加任意 XYZ / WMTS（KVP）图源（地址模板支持 `{z}` `{x}` `{y}` `{-y}` `{s}`）。如果天地图拒绝请求（Key 无效、类型不对或当日额度用完），地图上会给出提示并建议切换图源。

## 编译

需要 JDK 17 和 Android SDK（compileSdk 35）。

```bash
./gradlew assembleDebug test
```

开发时可以在 `local.properties` 里写 `tianditu.key=你的Key`，它只会进入 debug 包作为默认 Key；release 包永远不带 Key。

每次推送到 `main`，GitHub Actions 会编译调试版 APK 并跑单元测试，APK 可在运行记录的 Artifacts 中下载。

## 代码结构

| 模块 | 内容 |
| --- | --- |
| `app` | 应用入口、导航、依赖注入装配 |
| `core:model` | 纯 Kotlin 领域模型：几何、测地计算、要素、产状、图源定义 |
| `core:formats` | GeoJSON / GPX / KML 读写，可注册新格式 |
| `core:network` | 纯 Kotlin 协议客户端：REST、实时通道、同步引擎 |
| `core:gis` | 纯 Kotlin 空间分析工具与插件接口（`GisTool`） |
| `core:data` | Room 数据库、设置（DataStore）、要素/轨迹/照片仓库、工作组会话 |
| `core:sensors` | 定位（不依赖 Google 服务）、方向传感器、轨迹前台服务 |
| `core:map` | MapLibre 地图组件、图源样式、叠加图层、瓦片网络监测 |
| `core:ui` | 主题与通用组件 |
| `feature:map` | 地图主界面：图层切换、定位、定点、轨迹、画线面 |
| `feature:settings` | 设置：Key、自定义图源、坐标格式、磁偏角、轨迹参数 |
| `feature:record` | 记录列表与详情：名称、岩性、产状、自定义属性、照片 |
| `feature:compass` | 罗盘与产状测量 |
| `feature:transfer` | GeoJSON / KML / GPX 导入导出 |
| `feature:team` | 工作组：新建、加入、成员与邀请码管理、位置共享开关 |
| `feature:analysis` | 空间分析：反距离加权插值、统计、凸包范围、点缓冲区 |

### 添加分析工具

实现 `core:gis` 里的 `GisTool` 接口（声明参数、读取要素、返回新要素和说明），再在任意模块用 Hilt 注册：

```kotlin
@Provides @IntoSet fun myTool(): GisTool = MyTool()
```

分析界面会按参数自动生成表单，结果作为普通记录保存，可同步、可导出。输出要素可用 GeoJSON simplestyle 属性（`fill`、`stroke` 等）指定颜色。

### 小组协同

在「工作组」页面填写自部署服务端地址：组长新建工作组后，可以把邀请分享到聊天软件，或出示二维码；组员用相机扫码、点开 `geocrew://join` 链接或粘贴收到的邀请，加入表单就会自动填好。当前工程的记录、照片会在后台同步，组员位置显示在地图上（位置共享默认关闭，可随时开关）。成员凭据只保存在本机且不参与系统备份。

构建约定集中在 `build-logic`（`geocrew.android.library`、`geocrew.android.feature` 等插件），新增模块只需几行配置。

## 系统要求

Android 10（API 29）及以上。

## 许可

Apache-2.0
