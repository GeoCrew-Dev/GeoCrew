# 扩展指南

这份文档写给想给同勘加功能的人：代码怎么分层、常见的扩展点在哪里、加一样东西要动哪些文件。协议层面的约定（同步、冲突、字段含义）见 [geocrew-protocol](https://github.com/GeoCrew-Dev/geocrew-protocol) 的 `docs/protocol-v0.md`。

## 1. 模块分层

```
app ──▶ feature:* ──▶ core:data ──▶ core:model
            │            │
            ├──▶ core:map, core:ui, core:sensors
            └──▶ core:gis, core:formats, core:network （纯 JVM）
```

| 层 | 模块 | 规则 |
| --- | --- | --- |
| 纯逻辑 | `core:model`、`core:gis`、`core:formats`、`core:network` | 不依赖 Android，可以在电脑上直接跑单元测试，也能搬到服务端或桌面端用。算法、文件格式、坐标换算、几何计算都放这里。 |
| 平台 | `core:data`（Room、仓库、同步）、`core:map`（MapLibre）、`core:sensors`（定位、罗盘）、`core:ui`（公共组件） | 只做平台相关的事，业务规则尽量放回纯逻辑层。 |
| 功能 | `feature:*` | 一个功能一个模块，界面加 ViewModel。功能之间不互相依赖，跳转由 `app` 的导航完成。 |
| 应用 | `app` | 导航、依赖注入的汇总、应用级设置。 |

构建约定在 `build-logic`：纯 JVM 模块用 `geocrew.jvm.library`，功能模块用 `geocrew.android.feature`（已带上 `core:model`、`core:ui`、`core:data`、Compose、Hilt、导航）。新模块在 `settings.gradle.kts` 里 `include` 后套用对应插件即可。

## 2. 记录的数据模型

所有记录（点、线、面、地质点、轨迹、照片点……）都是 `GeoFeature`：一个几何加一个 JSON 属性对象，类型由 `kind` 区分。数据库只有一张要素表，**加一种新属性不需要改表结构，也不需要数据库迁移**，同步和导出会原样带上。

加一个由应用管理的属性（例如前面加的「审核」`review`）：

1. 在 `core:model` 的 `Props` 里加键名常量，并放进 `Props.MANAGED`，这样它不会出现在自由属性表和表单列里。
2. 在 `core:model` 写一个小的数据类和读写扩展（参照 `Review.kt`、`Task.kt`）：`GeoFeature.xxx` 读，`PropertiesBuilder.xxx(...)` 写。读的时候对缺字段、错类型要宽容，老版本和别的客户端写的数据都可能不全。
3. 界面里用 `FeatureRepository.edit` 改：它更新本地版本并标记为待同步，下次同步时上传，服务端为工作组保留修改历史。
4. 如果别的客户端或服务端也要认它，在协议文档的 §6 里写明字段格式。服务端默认原样存取属性，只有需要校验或权限控制的字段才要改服务端（例如只有组长能改 `review`）。

用户自定义的字段走工程的记录表单（`FieldTemplate`），不需要写代码。

真正要加表的情况（例如新的本地缓存）才动 `GeoCrewDatabase`：版本号加一，写 `Migration`，`exportSchema` 已打开，生成的结构文件要一起提交。

## 3. 加一个分析工具

分析工具是最容易扩展的地方：实现 `core:gis` 的 `GisTool` 接口，界面、表单、结果保存都自动有。

```kotlin
class MyTool : GisTool {
    override val id = "my-tool"                 // 稳定、小写，会写进结果的 tool 属性
    override val name = "我的工具"
    override val description = "一句话说明做什么、适合什么场景"
    override val accepts = setOf(GeometryType.POINT)
    private val radius = ToolParam.Number("radius", "半径", default = 100.0, unit = "m")
    override val params = listOf(radius)

    override fun check(input: ToolInput) = if (input.of(accepts).isEmpty()) "至少需要 1 个点" else null

    override fun run(input: ToolInput, progress: (Float) -> Unit): ToolOutput {
        // 读 input.features / input.number(radius)，定期检查 input.cancelled()
        return ToolOutput(features = emptyList(), summary = "结果说明")
    }
}
```

- 参数类型有数字、属性字段、选一条记录、选项四种（`ToolParam`），分析页会据此生成表单。
- 需要地形时用 `input.elevation`（安卓端接的是公开 DEM，带本地缓存）；没有时在 `check` 里给出原因。
- 结果是普通要素加一段说明和可选图例；颜色、线宽用 `fill`、`stroke`、`stroke-width` 等属性，地图会照着画。
- 注册：内置工具加到 `ToolRegistry.builtIn()`；放在别的模块的工具用 Hilt 加进 `Set<GisTool>`（见 `AnalysisModule`）。
- 不想改同勘代码的，可以写成独立应用作为插件提供工具，用 GeoJSON 交换数据，见协议仓库的 `docs/plugins.md` 和本仓库的 `samples/plugin`。

## 4. 加一种文件格式

实现 `core:formats` 的 `FeatureFormat`（`read` 和/或 `write`），加进 `FeatureFormats` 的内置列表，导入导出页就会列出它。需要 Android 平台能力的格式（例如用系统 SQLite 的 GeoPackage）在应用启动时调用 `FeatureFormats.register(...)`。

- 导入时用 `ImportContext.fields` 把列名对到工程表单的字段；转换不了的内容写进 `warnings`，不要静默丢掉。
- 导出时 `ExportContext` 里有工程表单和图例，表格类格式按表单顺序出列，带样式的格式按图例着色。
- 报表类格式（样品清单、分层表）用 `unit`、`fileTag` 和 `nothingToWrite` 说清导出的是什么。

## 5. 加一个内置底图源

在 `core:model` 的 `BuiltInSources` 里加一个 `TileSource` 并放进 `all`。需要密钥的源设 `keyId`，密钥由用户在设置里填写，**不要把任何密钥写进仓库**。地址里可用 `{z}`、`{x}`、`{y}`、`{-y}`、`{s}`、`{key}` 占位符（`TileTemplates.validate` 会检查）。用户也可以在设置里随时加自定义 XYZ 源，所以只有大多数人都会用到的源才值得内置。

## 6. 加一个功能页面

1. 新建 `feature:xxx` 模块（或放进现有的相关模块），写 `XxxScreen` 和 `@HiltViewModel` 的 ViewModel。
2. 在 `app` 的 `Navigation.kt` 里加路由常量和 `composable(...)`。
3. 需要入口的，在工具页（`ToolsScreen`）或相关页面加一项。

ViewModel 只通过 `core:data` 的仓库读写数据，不直接碰数据库；计算放进纯逻辑层，方便测试。

## 7. 测试与持续集成

- 纯逻辑层的改动都配单元测试（`src/test`），在电脑上几秒就能跑完。
- CI 运行 `./gradlew assembleDebug test --continue`，每次推送都会编译全部模块、跑全部单元测试，并产出调试版 APK。
- 提交信息用 Conventional Commits：`feat(scope): ...`、`fix(scope): ...`、`docs: ...` 等，scope 一般写模块名。
