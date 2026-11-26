# M1 技术验证：用 vcpkg 把 GDAL 编成安卓 AAR

- 日期：2026-11-25
- 对应待办：[M1](../backlog.md) 第一项"用 vcpkg 把 GDAL ≥ 3.12 编成 AAR（arm64、x86_64），量出 APK 体积"
- 代码：[GeoCrew-Native#1](https://github.com/GeoCrew-Dev/GeoCrew-Native/pull/1)
- 相关：[ADR 0003](../adr/0003-gis-engine.md)、[许可证清单](../licenses.md)、[性能预算](../budgets.md)

## 结论

可行。GDAL 3.12.4、PROJ 9.9.0、GEOS 3.15.1 用 vcpkg 编成了 arm64-v8a 和 x86_64 两种 AAR，在模拟器和一台真机上跑通了全部检查，应用里通过 JNI 调用也正常。

- **体积**：arm64 的原生库剥离符号后共 44.1 MB，比 ADR 0003 估的"二三十 MB"大。示例 APK 在原生库不压缩存放时（AGP 默认）是 46.5 MB，压缩存放时是 18.1 MB。负责人定了安装包体积不设上限、不为体积砍功能，所以 GDAL 的驱动全部保留；压缩存放原生库这类不影响功能的节省办法，在正式应用里再选。
- **构建**：本机（24 核）从零编两种架构共 27 分钟，之后走缓存。
- **真机**：在三星 Galaxy S24 Ultra（骁龙 8 Gen 3，安卓 16）上全部检查通过，速度和模拟器在同一个量级。这是旗舰机，也是唯一的测试机，不另找中端机再测（负责人 2026-11-26）。

## 做法

- **版本**：vcpkg 清单模式，`builtin-baseline` 固定为 116475f（2026-11-25）。这个基线下是 GDAL 3.12.4、PROJ 9.9.0、GEOS 3.15.1、SQLite 3.54.1。
- **GDAL 的特性**：关掉默认特性，只开 `expat geos iconv jpeg png sqlite3 webp zstd`。PROJ 只开 `tiff`（不开联网）。libtiff 加开 `webp`、`zstd`（GeoTIFF 的压缩方式）。SQLite 加开 `math`（SQL 表达式要用数学函数）和 `rtree`（GeoPackage 空间索引）。没有任何 GPL 依赖。
- **三元组**（`triplets/`，覆盖 vcpkg 自带的）：
  - API 31（安卓 12），与应用的最低版本一致；
  - 只编 Release；
  - 用 `libc++_shared.so`，因为 GEOS 单独成 .so 之后不止一个库用 C++；
  - 链接时按 16 KB 页对齐；
  - GEOS、GNU libiconv 两个 LGPL 库编成单独的 .so，其余静态链接进 `libgeocrew_native.so`，并隐藏它们的符号。
- **打包**：CMake 编出 `libgeocrew_native.so`（JNI 封装）和测速程序 `geocrew_bench`；脚本剥离符号后交给 Gradle 打 AAR；另有一个最小的示例应用，用来量 APK 体积。
- **CI**：GitHub Actions 在 ubuntu 上按 arm64、x86_64 两路编译，vcpkg 的编译结果用 actions/cache 缓存；检查许可证和 16 KB 对齐，产出 AAR 和两个 APK，体积表写进运行摘要。

## 结果

### 体积

剥离符号后的大小（KB）：

| | arm64-v8a | x86_64 |
|---|---:|---:|
| libgeocrew_native.so（GDAL、PROJ 及其静态依赖） | 38 535 | 41 407 |
| libgeos.so + libgeos_c.so | 4 310 | 4 546 |
| libiconv.so + libcharset.so | 1 085 | 1 080 |
| libc++_shared.so | 1 262 | 1 222 |
| **原生库合计** | **45 195** | **48 257** |

两种架构共用的资源：proj.db 10 480 KB（gzip 后 1 899 KB），GDAL 数据文件 154 个共 2 722 KB（gzip 后约 324 KB）。

示例应用（不含其他代码，空壳约 17 KB）的 APK：

| | arm64-v8a | x86_64 |
|---|---:|---:|
| 原生库不压缩存放（AGP 默认，安装时不解压） | 46.5 MB | 49.5 MB |
| 原生库压缩存放（`useLegacyPackaging`，安装时解压） | 18.1 MB | 19.1 MB |

AAR（两种架构）34.8 MB。按静态库估，GDAL 约占 libgeocrew_native.so 的四分之三，其次是 PROJ。所有 .so 都按 16 KB 对齐（CI 检查）。

### 检查与测速

测速程序 `geocrew_bench` 在两台设备上全部检查通过。模拟器跑在电脑的 CPU 上，只能看相对快慢；真机是三星 Galaxy S24 Ultra（骁龙 8 Gen 3，安卓 16，4 KB 页），属于旗舰机。每项只跑一次，数字是量级参考。

| 项目 | 模拟器（x86_64） | S24 Ultra（arm64） |
|---|---:|---:|
| GDALAllRegister（164 个驱动） | 1.8 ms | 3.4 ms |
| GeoPackage 写 10 万点（一个事务） | 263 ms | 234 ms |
| GeoPackage 读 10 万点 | 32 ms | 50 ms |
| GeoPackage 按范围查询（1% 面积，走空间索引） | 1.8 ms | 1.9 ms |
| PROJ：10 万点 EPSG:4490 → EPSG:4547 | 12 ms | 14 ms |
| GEOS：1 万个点做缓冲区 | 67 ms | 90 ms |
| GEOS：合并 2000 个相互重叠的缓冲区 | 72 ms | 61 ms |
| GeoTIFF 写 4096×4096（ZSTD） | 124 ms | 29 ms |
| 4096×4096 重投影到 Web 墨卡托 | 463 ms | 323 ms |
| `gdal vector info`（算法接口） | 1.7 ms | 1.7 ms |
| 峰值内存 | 约 100 MB | 约 105 MB |

其他检查：预期的 22 个驱动都在；SQLite 数学函数可用；114°E 30°N 转出 500000.000 / 3320113.398，与理论值一致；GBK 写出、读回都正确。

### 应用内调用

AAR 的仪器测试在模拟器和 S24 Ultra（都是安卓 16）上 4 项全部通过：加载库、通过算法流水线把 GeoJSON 重投影写成 GeoPackage（中文属性保留）、BMP 以外的汉字（𠮷）原样往返、失败时异常里带 GDAL 的错误信息。

## 发现的问题

1. **安卓自带的 iconv 读不了 GBK。** bionic 的 iconv 从 API 28 起才有，只支持 UTF-8 和 UTF-16，GBK、GB18030、Big5 连 ISO-8859-1 都不支持（在模拟器上逐个试过）。国内的 Shapefile 大多是 GBK 编码，不处理的话属性会乱码。解决：带上 GNU libiconv（LGPL-2.1+），和 GEOS 一样编成单独的 .so，已写进[许可证清单](../licenses.md)。
2. **CMake 会挑错 iconv。** API 28 起 libc 里有 iconv，CMake 的 FindIconv 会认定"iconv 是 C 库自带的"，不去链接 GNU libiconv，结果 GDAL 用的还是系统的那个。解决：编 GDAL 和我们自己的工程时都设 `Iconv_IS_BUILT_IN=FALSE`。测速程序里专门检查写出的 .dbf 字节确实是 GBK，防止以后退化。
3. **vcpkg 清单里不要写 `version>=`。** 它会让 vcpkg 去取旧版本的 port；本机的 vcpkg 是部分克隆，取不到就失败。版本只靠 `builtin-baseline` 固定，规则写进了 GeoCrew-Native 的 CLAUDE.md。
4. **GeoTIFF 的 ZSTD、WebP 压缩要在 libtiff 上开。** GDAL 的 `zstd` 特性管不到 GeoTIFF，GeoTIFF 的压缩方式由 libtiff 决定。已在清单里给 libtiff 加上。
5. **GDAL 的数据文件要随包带。** GDAL 3.12 在静态编译时能把数据文件编进库里，但要求编译器支持 C23 的 `#embed`，NDK r27 的 clang 18 不支持。现在和 proj.db 放在同一个资源目录里，首次启动解压到应用目录（2.7 MB）。换 NDK r28 及以上可以省掉这一步。
6. **JNI 的字符串要按 UTF-16 转。** JNI 自带的"修改版 UTF-8"处理不了 BMP 以外的字符，生僻字地名会乱码甚至崩溃。已改成自己在 UTF-16 和 UTF-8 之间转换，并有测试。

## 退路

这一项验证通过，vcpkg 这条路不需要退路。剩下的风险及对应办法：

- **体积**：不设上限，不为体积砍功能。不影响功能的节省办法（压缩存放原生库、LTO、`-Os`）在正式应用里再选。
- **中端机速度不够**：耗时的处理本来就放在后台任务里（[ADR 0002](../adr/0002-android-baseline.md)），可以显示进度；个别慢的操作再单独优化。
- **vcpkg 某个库在安卓上编不过**：用 overlay port 打补丁，或者把那个特性关掉。

## 后续

已记入[待办](../backlog.md)：

- 体积：不砍功能，只在正式应用里选不影响功能的节省办法，比如压缩存放原生库（代价是安装时解压、占用的存储变多）、LTO。
- 评估换 NDK r28 及以上：GDAL 数据文件可以编进库里，16 KB 对齐也成为默认。
- CI 里用 x86_64 模拟器跑测速程序和仪器测试。
- 定下是否需要 SpatiaLite（SQL 里的空间函数）、联网驱动（curl）、XLS（freexl）等暂未开的特性；每加一个都要先查许可证。

代码在 [GeoCrew-Native#1](https://github.com/GeoCrew-Dev/GeoCrew-Native/pull/1)。
