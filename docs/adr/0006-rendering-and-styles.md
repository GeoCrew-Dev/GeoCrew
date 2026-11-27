# 0006 渲染与样式模型

## 背景

实时地图要流畅，出图又要能用任意投影、输出矢量 PDF，还要画得准地质符号。MapLibre 是网络地图引擎：只能显示 Web 墨卡托，符号按像素计算，导出时只能截图。它满足不了出图的要求，但做实时地图很流畅。

## 决定

- **实时地图用 MapLibre Native**：
  - 工程图层通过 CustomGeometrySource 交给 MapLibre：按瓦片范围查空间索引，重投影，再用 SQL 算好样式属性，见 [ADR 0007](0007-expressions.md)。
  - 如果不够快，再改成自己编码 MVT 瓦片。
- **非 Web 墨卡托的栅格和 WMTS**（如天地图经纬度瓦片、高斯投影的 GeoTIFF）：先用 GDAL 重投影成瓦片，再交给 MapLibre 显示。
- **另做一个制图渲染器**：
  - 直接画到 Android 的 Canvas 和 PdfDocument 上，用 PROJ 支持任意投影，输出矢量 PDF；
  - 符号按毫米或地图单位计算，花纹随地图比例尺缩放；
  - 能画沿线符号，比如逆断层的齿；
  - 标注能避让，能加引线。
- **两个渲染器共用一套样式模型**。v1 只收两边都能实现的部分：
  - 着色方式：单一、分类、分级、按规则（规则用 SQL 条件）；
  - 几何类型：点、线、面，外加标注；
  - 另配一个地质符号库。

  只有出图才用得上的属性单独标出来。
- **用截图对比测试把两边的差异控制住。**
- **三维以后做**：用 WebView 加 MapLibre GL JS 做只读视图。MapLibre Native 到 2026 年仍然没有正式的三维地形。
- **实时等高线**：用 GDAL 从 DEM 生成。

## 理由

实时操作和精确出图对引擎的要求不一样，各用合适的引擎；靠同一套样式模型，两边画出来保持一致。

## 代价与后果

- 要维护两个渲染器。两边的标注引擎不同，画出来不会完全一样，靠截图对比测试约束差异。
- 制图渲染器的标注引擎是我们自己写的，起步时质量不会太高。

## 状态

已采纳（2026-11-25）。M1 要验证两项：

- 用 CustomGeometrySource 显示 10 万要素和编辑后的刷新：在模拟器上验证可行，低缩放级别要抽稀或聚合，见[验证报告](../spikes/m1-map-customgeometry.md)；
- 非 Web 墨卡托栅格的显示：在模拟器上验证可行，GDAL 按请求现场重投影成瓦片，经 OkHttp 拦截器交给 MapLibre，见[验证报告](../spikes/m1-raster-reprojection.md)。

2026-11-26 在 S24 Ultra 真机上复测，两项都通过：10 万点缩放约 100 至 110 帧 / 秒，栅格缩放 120 帧 / 秒（屏幕上限）。渲染后端用 MapLibre 的 OpenGL ES 版（`android-sdk-opengl`）：默认的 Vulkan 版在真机上同样流畅，但在模拟器上会崩溃，而开发和 CI 都靠模拟器。以后 Vulkan 版在模拟器上修好了再考虑换。

2026-11-26 样式模型的第一步：单一符号。每个图层一种颜色、点的半径、线宽和面的填充透明度，存在工程的 `geocrew_layer_style` 表里，一个图层一行 JSON（`{"kind": "single", "color": "#rrggbb", "size", "width", "fill"}`），以后的分类、分级、规则着色用别的 `kind`。没设样式的图层按建立的先后取一种颜色。动作 `layer.style`，改了马上存、不进撤销，和图层显示一样。

2026-11-27 栅格图层按 M1 验证的做法落地：MapLibre 的每个栅格源指向 `https://geocrew.tiles/raster/<栅格 ID>/<文件版本>/{z}/{x}/{y}.png`，应用给 MapLibre 装了自己的 OkHttp 客户端，拦截器拦下这个地址，调 GeoCrew-Native 的 `renderTile` 现场出瓦片，其他请求照常出去。地址里带文件的大小和修改时间，文件换了 MapLibre 的缓存就不会用旧瓦片；出图失败回空瓦片，不抛异常。8 位的灰度、RGB、RGBA 直接出图；调色板图先用 `gdal raster color-map` 转成 RGB 的 VRT，16 位、浮点的先用 `gdal raster scale` 拉伸成 8 位的 VRT（单波段 DEM 现在是灰度，山体阴影以后做），VRT 存在应用私有目录，文件变了重做。栅格源带范围和缩放级别（从栅格只有几个像素大到瓦片像素和原像元一样细，再往上由 MapLibre 放大），画在所有矢量图层下面、底图上面，后加的在上面。OkHttp 原本就随 MapLibre 打包，现在应用直接引用同一版本（4.12.0）。

## 相关

- [ADR 0003](0003-gis-engine.md)
- [ADR 0004](0004-project-storage.md)
- [ADR 0007](0007-expressions.md)
- [ADR 0015](0015-map-sources.md)
