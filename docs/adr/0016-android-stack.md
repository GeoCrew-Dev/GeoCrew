# 0016 安卓客户端的技术栈与模块

## 背景

旧版安卓代码已经删除，留在标签 `android-legacy`；新客户端从 M2 起写在 `android/` 里。动手之前，要定下用什么写界面、怎么分模块、哪些库可以引入，免得各个切片各选各的。

约束：

- 平台基线见 [ADR 0002](0002-android-baseline.md)：最低安卓 12（API 31），只出 arm64-v8a 和 x86_64。
- 协议、合并规则和权限规则不能绑死在安卓上，以后可能有鸿蒙和 iOS 客户端（[章程](../charter.md)）。
- GeoCrew-Native 的 AAR 已经在 Gradle 8.14、AGP 8.13、Kotlin 2.4 上构建通过，本机和 CI 都验证过。

## 决定

- **语言与构建**：
  - 只用 Kotlin，Gradle 用 Kotlin DSL，依赖版本统一写在 `gradle/libs.versions.toml`；
  - Gradle、AGP、Kotlin 的版本和 GeoCrew-Native 保持一致，升级时两边一起升；
  - 字节码目标 JVM 17。
- **界面**：Jetpack Compose 加 Material 3，单个 Activity。界面只读状态、发起动作，不直接改数据（[ADR 0010](0010-action-registry.md)）。
- **基础库**：kotlinx.coroutines 做并发，kotlinx.serialization 做 JSON。暂不引入依赖注入框架，在应用入口手工组装。
- **模块**：
  - `app`：安卓应用，界面和组装；
  - `core:*`：不依赖安卓的纯 Kotlin（JVM）模块，放动作注册表、协议 v1 客户端、合并和权限规则这些。它们在电脑上就能跑单元测试，以后给别的平台用也最方便；
  - 需要安卓 API 或原生库的部分（工程存储、地图）放安卓库模块，随对应的切片加。
- **测试**：
  - `core:*` 用 kotlin.test（JUnit 4）在 JVM 上跑；
  - 合并和权限规则读 `protocol/testdata/` 下和服务端共用的样例；
  - 需要设备的测试放仪器测试，在 CI 的模拟器上跑（和 GeoCrew-Native 一样）。
- **GeoCrew-Native 的 AAR**：做工程存储（GPKG）那个切片时再定怎么取用。倾向于 GeoCrew-Native 在 GitHub Releases 发布 AAR，构建时按固定版本和 SHA-256 下载。发布前要负责人同意。

## 理由

- Compose 是安卓现在的主流写法，状态驱动界面，和"界面只发动作"的架构对得上。
- 纯 Kotlin 的 `core` 模块把规则和平台隔开，测试快，移植时也不用拆。
- 先不用依赖注入框架：模块少的时候手工组装更直白，需要时再加。
- 和 GeoCrew-Native 用同一套版本，少一类兼容问题。

## 代价与后果

- Compose 和 Material 3 会让安装包大几兆，按章程，体积不设上限，可以接受。
- 新引入的库要写进[许可证清单](../licenses.md)：Kotlin 标准库、kotlinx、AndroidX 和 Compose 都是 Apache-2.0；JUnit 4 是 EPL-1.0，只在测试时用，不随应用发布。
- 以后要引入依赖注入、数据库框架之类的，另写 ADR。

## 状态

已采纳（2026-11-26）。

## 相关

- [ADR 0002](0002-android-baseline.md)
- [ADR 0003](0003-gis-engine.md)
- [ADR 0010](0010-action-registry.md)
