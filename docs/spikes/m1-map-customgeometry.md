# M1 技术验证：用 CustomGeometrySource 显示 10 万要素

- 日期：2026-11-25
- 对应待办：[M1](../backlog.md) 第三项"用 CustomGeometrySource 出图：测 10 万要素，以及编辑后的局部刷新"
- 代码：[GeoCrew-Native#3](https://github.com/GeoCrew-Dev/GeoCrew-Native/pull/3) 的 `android/mapspike` 和 JNI 里的 `queryPoints`、`movePoint`
- 相关：[ADR 0006](../adr/0006-rendering-and-styles.md)、[ADR 0007](../adr/0007-expressions.md)

## 结论

可行。在模拟器上，10 万个点存在 GeoPackage 里，经 CustomGeometrySource 交给 MapLibre 显示；按瓦片范围走空间索引查询，颜色分类在 SQL 里算好。缩放动画约 45 fps；改一个点后只刷新它周围的区域，大约 0.1 秒内重新查完，下一帧就画出来。暂时不需要改走自己编码 MVT 瓦片的退路。

有两个问题要在正式实现时处理：

- **低缩放级别**：一个瓦片装着全部 10 万个点，首屏要 2.6 到 4.7 秒。正式实现要在低级别抽稀或聚合。
- **渲染后端**：MapLibre 13 默认的 Vulkan 后端在模拟器上崩溃，现在用 OpenGL ES 版。

**真机（2026-11-26 补测）**：S24 Ultra 上首屏 1.5 至 1.8 秒，缩放约 100 至 110 帧 / 秒，改一个点到重新查完中位数约 50 ms。Vulkan 版在真机上不崩溃，速度和 OpenGL ES 版一样。

## 做法

- 验证用的应用 `mapspike`：生成 10 万个随机点，范围 1° × 1°，用 `gdal vector convert` 写成 GeoPackage；底图只有纯色背景，不需要网络和 Key。
- 数据源用 CustomGeometrySource，每个瓦片调一次 JNI 的 `queryPoints`。查询用 SQL 连接空间索引表 `rtree_points_geom`，同时算出颜色分类：`CASE WHEN elev > 500 THEN 1 ELSE 0 END`。JNI 一次性返回打包好的坐标和分类数组，Kotlin 再构建 MapLibre 的要素。
- 圆点图层按分类取色。
- 编辑：用 OGR 接口改一个点（`movePoint`），GeoPackage 的索引触发器跟着更新，再对新旧位置周围的小范围调 `invalidateRegion`。
- 用的是 MapLibre Android 13.7.1 的 `android-sdk-opengl`。

## 结果（模拟器，x86_64，安卓 16）

| 项目 | 结果 |
|---|---|
| 生成 10 万点的 GeoPackage | 1.8 秒 |
| 首屏：8 级下 10 万个点全部画完 | 2.6 至 4.7 秒（4 次：2.9、2.6、2.7、4.7 秒） |
| 首屏的瓦片 | 7 个，共查出 20 万行；最大的一个装了全部 10 万点，查询加构建约 0.3 至 1.1 秒 |
| 8 → 12 → 8 → 15 → 8 级缩放动画，共 8 秒 | 337 至 369 帧，42 至 46 fps |
| 14 级改一个点：写进 GeoPackage | 中位数 16 ms |
| 14 级改一个点：到它的瓦片重新查完 | 中位数 64 ms，最多 113 ms |

每个瓦片的开销主要在查询：10 万行要 0.5 秒左右，构建 MapLibre 要素大约同样多。高缩放级别时一个瓦片只有几千到一万行，十几毫秒就查完。

## 结果（真机，三星 Galaxy S24 Ultra，安卓 16，120 Hz 屏幕，2026-11-26）

| 项目 | OpenGL ES 版（3 次） | Vulkan 版（3 次） |
|---|---|---|
| 生成 10 万点的 GeoPackage | 1.0 秒 | — |
| 首屏：8 级下 10 万个点全部画完 | 1.5、1.8、1.5 秒 | 1.8、1.6、1.8 秒 |
| 8 → 12 → 8 → 15 → 8 级缩放动画，8 秒 | 110、107、100 fps | 107、110、110 fps |
| 14 级改一个点：写进 GeoPackage | 中位数 11 至 12 ms | 中位数 8 至 14 ms |
| 14 级改一个点：到它的瓦片重新查完 | 中位数 46 至 51 ms，最多 76 ms | 中位数 36 至 53 ms，最多 66 ms |

首屏仍然主要花在 8 级那个装着全部 10 万点的瓦片上（查询约 0.3 秒），低缩放级别抽稀的结论不变。Vulkan 版用 `-PmapBackend=vulkan` 构建，日志里确认走的是 Adreno 的 Vulkan 驱动。

## 发现的问题

1. **低缩放级别的瓦片太重**：8 级时一个瓦片覆盖整个范围，要查出并构建全部 10 万个点。工程大了以后，首屏和缩小地图会越来越慢。正式实现要在低缩放级别抽稀（按网格每格只取几个点）或做聚合；ADR 0006 写的退路（自己编码 MVT 瓦片）可以省掉在 Java 里构建要素的那一半时间，但查询的开销还在。
2. **MapLibre 13 默认用 Vulkan**：默认的 `android-sdk` 包在模拟器上一移动相机就崩溃（渲染线程里的空指针）；`android-sdk-opengl` 正常。真机上两种都正常、一样快，所以定用 OpenGL ES 版，已写进 ADR 0006。
3. **局部刷新不会触发"地图全部画完"的事件**：`invalidateRegion` 之后 MapLibre 只重新请求受影响的瓦片，不发这个事件，所以编辑的耗时按瓦片重新查完来算。正式实现里不要依赖这个事件判断刷新完成。

## 退路

这一项验证通过。低缩放级别如果抽稀之后仍然不够快，再按 ADR 0006 的退路改成在原生代码里编码 MVT 瓦片。

## 后续

已记入[待办](../backlog.md)：

- ~~真机上跑 `mapspike`，量帧率，并试 MapLibre 的 Vulkan 后端~~：2026-11-26 已做，结果见上。
- M2 实现图层显示时：低缩放级别抽稀或聚合；查询只取坐标和样式字段。
