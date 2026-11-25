# 同勘 GeoCrew 协议

同勘 GeoCrew 是一款开源的野外地质移动工具，把地图工具、轻量 GIS 和小组实时协同放在同一个 Android 应用里。

本仓库存放：

- **协同协议**：客户端与自部署服务端之间的数据格式、接口和鉴权约定（`docs/protocol-v0.md`），以及机器可读的 OpenAPI 描述（`docs/openapi.yaml`）。
- **分析插件接口**：其他安卓应用如何给同勘提供空间分析工具（`docs/plugins.md`），只交换 GeoJSON 文件。
- **一致性检查**：从外部跑一遍、确认某个服务端按协议工作的命令行工具（`conformance/`），本仓库 CI 用它检查官方服务端。
- **版本与发布**：版本号规则、发布附带的文件和 `SHA256SUMS`、客户端与服务端怎样检查和安装更新（`docs/releases.md`）。
- **项目规划**：功能分期、架构与技术选型（`docs/roadmap.md`）。

任何人都可以按本协议实现自己的服务端或客户端。官方参考实现：

| 仓库 | 内容 |
| --- | --- |
| [geocrew-android](https://github.com/GeoCrew-Dev/GeoCrew-Android) | Android 客户端 |
| [geocrew-server](https://github.com/GeoCrew-Dev/GeoCrew-Server) | Linux 自部署服务端（Docker） |

## 设计原则

1. **离线优先**：所有记录先存本地，有网再同步，不依赖服务端也能完整使用单机功能。
2. **数据自主**：服务端由使用者自行部署，项目方不托管任何用户数据。
3. **开放格式**：几何与属性统一使用 GeoJSON（RFC 7946），可直接导入 QGIS / ArcGIS。
4. **协议先行**：接口变更先改本仓库文档，再改实现。

## 许可

文档采用 CC BY 4.0；`conformance/` 下的检查工具代码采用 Apache-2.0；参考实现代码见各仓库的许可文件。
