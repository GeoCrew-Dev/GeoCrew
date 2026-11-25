# android/：工作规则

先读根目录的 [CLAUDE.md](../CLAUDE.md)。

## 这个目录是冻结的旧版

- 这里是旧版安卓客户端，**不再加新功能**，只在 CI 需要时修构建问题。
- 新客户端按[章程](../docs/charter.md)和 [ADR](../docs/adr/README.md) 重写，从 M2 开始。新代码放在哪、怎么组织，待定，M2 开始时定下来，并写进本文件。

## 要搬到新客户端的东西

纯 Kotlin 的计算代码和它们的单元测试，搬过去可以省掉重写和查错，主要是：

- **`core:model`**：
  - 产状与线理：`Attitude`、`Plunge`；
  - 地层厚度：`Thickness`；
  - 实测剖面：`MeasuredSection`；
  - 钻孔：`Borehole`；
  - 七参数与四参数：`DatumShift`；
  - 高斯投影：`GaussKruger`；
  - 测地计算：`Geodesy`；
  - 日出日落：`SunTimes`；
  - 方位交会：`BearingFix`；
  - 定点平均：`PositionAverage`；
  - 国标图幅：`MapSheet`；
  - 徒步用时：`Hiking`；
  - 轨迹统计：`TrackStats`；
  - 坐标解析：`CoordinateParser`；
  - 编号：`Numbering`；
  - 语音纠错：`SpeechText`；
  - 常用词与岩性：`FieldTerms`、`Lithology`。
- **`core:gis`**：
  - 赤平投影：`Stereonet`、`Fabric`；
  - 玫瑰图：`Rose`；
  - 克里金：`Kriging`；
  - 补点：`Sampling`；
  - 泰森多边形：`Thiessen`；
  - 最省时路线：`LeastCost`；
  - 三点法：`ThreePoint`；
  - 剖面投影与图切剖面：`Section`、`MapSection`。
- **`core:formats`**：
  - 柱状图排版：`StratColumn`；
  - 样品标签：`SampleLabels`；
  - 化验结果回填：`AssayCsv`。

这些格式读写和坐标转换会改用 GDAL 和 PROJ，旧代码不搬，但它们的测试可以拿来核对新结果：`ShapefileReader`、`GeoPackage`、`Kml`、`Gpx`、`Dxf`，以及手写的投影。

## 已知情况

- 旧版的应用内更新还指向已经删除的 GeoCrew-Android 仓库。请求会返回 404，被当作"没有新版本"，所以没有危害。
- 新客户端必须按 `protocol/docs/releases.md` 的规则，从发布列表里只取 `android-v*` 前缀的版本。
- 根目录的 `android.yml` 仍会编译这份旧代码、跑单元测试，并连服务端做联调测试。
