# 0003 GIS 引擎：GDAL、PROJ、GEOS

- 状态：已采纳（2026-11-25），构建方式已由 M1 技术验证确认
- 相关：[0004](0004-project-storage.md)、[0010](0010-action-registry.md)、[0013](0013-server-gdal-helper.md)、[许可证清单](../licenses.md)

## 背景

目标是"QGIS 有的功能，同勘都有类似的功能"，按能力衡量，不要求和 QGIS 兼容。负责人定了原则：用成熟的开源组件，不自己造轮子。旧版的几何运算、坐标转换和格式读写都是手写的。

## 决定

- 用 GDAL ≥ 3.12 读写数据、做处理算法，PROJ 做坐标系与坐标转换，GEOS 做几何运算。之所以锁定 3.12，是因为算法注册表的 C 接口从 3.12 起才有。
- 这些库由 GeoCrew-Native 仓库统一编译：用 vcpkg 清单固定版本，源码要改时用 overlay port 里的 diff 补丁，仓库里不放第三方源码。
  - 安卓端：发布成 AAR。
  - 服务端：给 GDAL 辅助程序提供静态库，见 [ADR 0013](0013-server-gdal-helper.md)。
- 排除 GDAL 里属于 GPL 的可选依赖（如 poppler）。在安卓上，GEOS 编成单独的 .so。
- 安卓自带的 iconv 读不了 GBK，所以带上 GNU libiconv，同样编成单独的 .so（M1 验证补充）。
- GDAL 算法注册表里的每个算法，都自动登记成动作：有自动生成的表单，AI 也能调用，见 [ADR 0010](0010-action-registry.md)。克里金、泰森多边形、最省时路线等自有算法，作为内置动作保留。
- QGIS 的处理工具箱、标注、符号、版面都是 QGIS 自己写的代码，我们要自己写出类似功能，按[章程](../charter.md)里的分档清单逐步做。

## 理由

- QGIS 的数据读写、坐标和几何也是建在这三个库上，用同一批库，这几层的能力就和 QGIS 一个水平。
- 不直接嵌入 QGIS 内核，原因有四个：
  - QGIS 是 GPL，嵌进来整个应用都要改成 GPL；
  - 要带上 Qt 运行时；
  - 要用它的渲染引擎替掉 MapLibre；
  - 等于重做一个 QField。

## 代价与后果

- 安装包变大。M1 实测 arm64：原生库剥离符号后 44 MB，proj.db 10 MB、GDAL 数据文件 3 MB；APK 在原生库不压缩存放时 46.5 MB，压缩存放时 18 MB。见[验证报告](../spikes/m1-gdal-android.md)。负责人定了安装包体积不设上限，能省则省，但不为体积砍功能，所以 GDAL 的驱动全部保留。
- 原生代码碰到坏文件可能崩溃，所以导入和试读放在独立进程里，见 [ADR 0002](0002-android-baseline.md)。
- GDAL 的算法数量远少于 QGIS 工具箱，第二档的工具里有一部分要自己写。
- 旧版手写的格式读写和坐标转换不再保留，但它们的单元测试可以用来核对 GDAL、PROJ 的结果。

## 状态

已采纳。构建方式已由 M1 验证确认，见[验证报告](../spikes/m1-gdal-android.md)。

2026-11-26 补充：选坐标系时直接用 Android 自带的 SQLite 只读打开 PROJ 的 proj.db，按名称、代码和适用范围查（EPSG、ESRI、IGNF 等约 1.4 万个，去掉作废的和其他天体的），一次查询二十毫秒以内，不用改原生库。proj.db 的表结构不是 PROJ 承诺的接口，用的是 PROJ 6 起就有的 `crs_view`、`usage`、`extent`；升级 PROJ 时 `crsSearchFindsByCodeNameChineseAndPlace` 测试会发现变化。

2026-11-27 补充：试读放进了独立进程。`storage` 模块的 `ProbeService` 声明在进程 `:probe` 里，应用通过 Messenger 把路径发过去；它跑 GeoCrew-Native 带的 `gdal info --format json`，在那边就把结果压成简短的 JSON 再传回来：栅格的格式、像素、波段、调色板、金字塔层数、坐标系、像元大小和 WGS84 范围，矢量的各图层、几何类型、要素数和坐标系（同勘工程自己的 `geocrew_` 表不算）。读文件的进程死了（文件让 GDAL 崩溃）、读不了或 30 秒没回应，都当"读不了这个文件"，应用本身不受影响。对外是动作 `file.probe`。之后把文件加到地图、导入工程，都先经过它。
