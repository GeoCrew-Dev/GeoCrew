# 分析插件接口 v1

> 状态：v1（2026-11-24），同勘安卓端已支持。

别的安卓应用可以给同勘提供空间分析工具。插件和同勘只交换 GeoJSON 文件，不需要引入同勘的代码，用任何语言、任何框架写的应用都可以做插件（比如把 R、Python 或 GDAL 包进一个应用）。

用户在「空间分析」里看到的插件工具和内置工具一样：同勘按插件的说明生成参数表单，把当前工程里符合条件的记录交给插件，插件算完后把结果写回，结果作为新记录保存，可同步、导出。

完整的示例插件见 geocrew-android 仓库的 `samples/plugin`：一个没有界面的 Activity，为线和面求中心点或外接矩形，只用系统自带的 `org.json`。

如果工具能写成纯 Kotlin，也可以直接实现 `core:gis` 的 `GisTool` 接口，编进同勘或自己的分支，不必做成插件。

## 1. 声明

插件在清单里声明一个 Activity，接收动作 `org.geocrew.plugin.action.RUN_TOOL`，并用 meta-data `org.geocrew.plugin.TOOL` 指向一个 raw 资源里的说明文件：

```xml
<activity
    android:name=".SlopeActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="org.geocrew.plugin.action.RUN_TOOL" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data
        android:name="org.geocrew.plugin.TOOL"
        android:resource="@raw/geocrew_tool" />
</activity>
```

一个应用可以声明多个这样的 Activity，每个是一个工具。同勘每次打开「空间分析」时重新查找插件，装好插件后不用重启同勘。

## 2. 说明文件

UTF-8 JSON，不超过 64 KB：

```json
{
  "api": 1,
  "id": "slope",
  "name": "坡度分析",
  "description": "按 DEM 计算每个点的坡度和坡向。",
  "accepts": ["point", "polygon"],
  "min_features": 1,
  "params": [
    {"type": "field", "key": "elev", "label": "高程属性", "numeric": true, "required": true},
    {"type": "number", "key": "cell", "label": "网格大小", "default": 10, "min": 1, "max": 1000, "unit": "m", "help": "留空自动选择"},
    {"type": "choice", "key": "unit", "label": "坡度单位", "options": [{"value": "deg", "label": "度"}, {"value": "pct", "label": "百分比"}], "default": "deg"},
    {"type": "feature", "key": "area", "label": "计算范围", "types": ["polygon"]}
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `api` | 需要的接口版本，现在是 1；比同勘支持的版本高时，工具不显示 |
| `id` | 工具在本应用内的标识，小写字母、数字和 `_ . -`，最长 40 个字符。同勘内的工具 id 是 `<包名>/<id>`，记在结果记录的 `tool` 属性里 |
| `name` | 工具名称，最长 40 个字符 |
| `description` | 一两句话，最长 300 个字符 |
| `accepts` | 读取的几何类型：`point`、`line`、`polygon`；不写表示全部 |
| `min_features` | 至少需要几条记录，默认 1 |
| `params` | 参数，最多 20 个，按顺序显示 |

参数的公共字段是 `type`、`key`（规则同 `id`，不能重复）、`label`、`help`、`required`（必填，默认否）。各类型另有：

| `type` | 控件 | 其他字段 |
| --- | --- | --- |
| `number` | 数字输入框 | `default`（不写表示「自动」，留空不传）、`min`、`max`、`unit` |
| `field` | 选记录属性 | `numeric`：只列数值属性（默认是），否则列文字属性（岩性、地层等） |
| `choice` | 单选 | `options`：`[{"value", "label"}]`，`default` |
| `feature` | 选一条记录 | `types`：可选的几何类型，值是记录 id |

## 3. 运行

用户点「运行」后，同勘用显式 Intent 启动插件的 Activity（等待结果）：

| 内容 | 说明 |
| --- | --- |
| `data` | 输入文件的 `content://` 地址，类型 `application/geo+json`，授予读权限 |
| extra `org.geocrew.plugin.extra.OUTPUT` | 结果文件的 `content://` 地址（`Uri`），授予写权限 |
| extra `org.geocrew.plugin.extra.API` | 同勘支持的接口版本（`int`） |

两个地址也放在 Intent 的 `ClipData` 里，权限随 Intent 一起授予，插件结束后失效。插件可以有自己的界面（进度、选文件、登录等），也可以不显示界面直接算完结束。

### 3.1 输入

一个 FeatureCollection，只含符合 `accepts` 的未删除记录，格式和同勘导出的 GeoJSON 相同（坐标为 WGS84/CGCS2000 经纬度，记录类型在 `properties.kind`）。顶层的 `geocrew` 成员写明这次运行：

```json
{
  "type": "FeatureCollection",
  "features": [ ... ],
  "geocrew": {
    "api": 1,
    "tool": "slope",
    "params": {"elev": "高程", "unit": "deg"}
  }
}
```

`params` 只含用户填了的参数，值一律是字符串；数字由插件自己解析（用户可能填超出范围的值，同勘会在表单里提示，但插件也应检查）。

### 3.2 结果

插件把结果写进 OUTPUT 地址，`setResult(RESULT_OK)` 后结束：

```json
{
  "type": "FeatureCollection",
  "features": [
    {"type": "Feature", "geometry": {"type": "Point", "coordinates": [116.39, 39.91]},
     "properties": {"kind": "geo_point", "title": "坡度 12.5°", "slope": 12.5, "fill": "#E65100"}}
  ],
  "geocrew": {
    "summary": "计算了 24 个点，平均坡度 12.5°。",
    "legend": [{"color": "#FFE0B2", "label": "0–10°"}, {"color": "#E65100", "label": "> 30°"}]
  }
}
```

- 结果最多 2 万条，文件不超过 50 MB；没有几何或几何无法识别的结果会略过并告诉用户。
- `properties.kind` 只能是和几何相符的 `point`、`geo_point`、`line`、`polygon`，否则按几何推断。
- 样式属性和内置工具相同：`fill`、`fill-opacity`、`stroke`、`stroke-width`（颜色为 `#RRGGBB`）；`title` 是记录名称。
- `summary`（最长 2000 字）在结果页显示；`legend` 最多 20 项。
- 出错时写 `{"type": "FeatureCollection", "features": [], "geocrew": {"error": "找不到 DEM 文件"}}`，用 `RESULT_OK` 或 `RESULT_CANCELED` 结束，同勘把错误原文显示给用户。
- 用户在插件里取消、什么也不写就以 `RESULT_CANCELED` 结束，同勘视为取消。

结果先显示给用户，用户点「保存」才写进工程。

## 4. 安全与隐私

- 记录只在用户选择并运行这个工具时交给插件，插件只能读这一次的输入文件、写这一次的结果文件。
- 插件拿到的是记录本身（含属性、组员写的内容），能否上传由插件自己决定，同勘在工具说明里注明它来自哪个应用。用户应只安装信任的插件。
- 同勘不会执行插件结果里的任何指令，只把它当作数据。

## 5. 版本

新增字段不升版本；不兼容的改动升 `api`。插件应忽略不认识的字段。
