# 同勘 GeoCrew

开源的野外地质与 GIS 工具，围绕四个目标展开：

- 完整的移动端 GIS；
- 强大的野外地图；
- 多人实时工作区；
- 能直接操作应用的内置 AI，即同勘灵宝（GeoCrew Nugget）。

> 状态：安卓客户端正在 `android/` 里按新架构重写；旧版的代码在标签 [`android-legacy`](https://github.com/GeoCrew-Dev/GeoCrew/tree/android-legacy/android)。计划、待办和评审都在 `docs/`。

## 目录

| 目录 | 内容 |
| --- | --- |
| `protocol/` | 协同协议、OpenAPI 描述、插件规范与一致性检查工具（原 GeoCrew-Protocol） |
| `server/` | Go 自部署服务端（原 GeoCrew-Server） |
| `android/` | 安卓客户端（重写中；旧版见标签 `android-legacy`） |
| `manual/` | 用户手册与教程，用来生成网站文档和应用内的离线帮助 |
| `docs/` | 项目文档：[章程](docs/charter.md)、[架构决策](docs/adr/README.md)、[待办](docs/backlog.md)、合规清单、威胁模型、许可证清单、性能预算、[计划评审](docs/reviews/2026-11-25-plan-review.md) |

三个原仓库的完整提交历史已经并入对应目录，可以用 `git log -- server/` 这样的命令查看。CI 放在根目录的 `.github/workflows/`，按改动的目录触发。发布标签带组件前缀，比如 `server-v0.3.0`，规则见 `protocol/docs/releases.md`。给 AI 代理的工作规则见 [CLAUDE.md](CLAUDE.md)。

## 相关仓库

| 仓库 | 内容 |
| --- | --- |
| GeoCrew-Native | 第三方原生库（GDAL、PROJ、GEOS 等）的多平台构建 |
| GeoCrew-Plugins | 插件目录、开发工具包、官方插件 |
| GeoCrew-Dev.github.io | 官网 |

## 许可

各目录的许可：

- `protocol/` 下的文档：CC BY 4.0；`protocol/conformance/`：Apache-2.0
- `server/`、`android/`：Apache-2.0
- `manual/`：CC BY 4.0
- 其余代码：Apache-2.0（见 `LICENSE`）
