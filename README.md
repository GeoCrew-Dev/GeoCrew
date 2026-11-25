# 同勘 GeoCrew

开源的野外地质与 GIS 工具，围绕四个目标展开：

- 完整的移动端 GIS；
- 强大的野外地图；
- 多人实时工作区；
- 能直接操作应用的内置 AI，即同勘灵宝（GeoCrew Nugget）。

> 状态：安卓客户端正在按新架构重写，`android/` 下是旧代码，留作参考。计划、待办和评审都在 `docs/`。

## 目录

| 目录 | 内容 |
| --- | --- |
| `protocol/` | 协同协议、OpenAPI 描述、插件规范与一致性检查工具（原 GeoCrew-Protocol） |
| `server/` | Go 自部署服务端（原 GeoCrew-Server） |
| `android/` | 安卓客户端（原 GeoCrew-Android，旧代码，待重写） |
| `manual/` | 用户手册与教程，用来生成网站文档和应用内的离线帮助 |
| `docs/` | 项目文档：[待办](docs/backlog.md)、[计划评审](docs/reviews/2026-11-25-plan-review.md)，以后还会放章程和架构决策 |

三个原仓库的完整提交历史已经并入对应目录，可以用 `git log -- server/` 这样的命令查看。各子目录里原来的 CI 配置暂时不会运行，迁移到根目录的工作记在[待办](docs/backlog.md)里。

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
