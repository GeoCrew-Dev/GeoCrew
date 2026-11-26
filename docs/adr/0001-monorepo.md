# 0001 单仓库与相关仓库

## 背景

原来协议、服务端、安卓各占一个仓库。三者经常要一起改，因为要协议先行。仓库分开时：

- 没法在一个 PR 里把三处一起改完；
- CI 要跨仓库检出代码；
- AI 代理在几个仓库之间协作，成本很高。

## 决定

- **主仓库** GeoCrew-Dev/GeoCrew，包含：
  - `protocol/`：协同协议、OpenAPI、插件规范、一致性检查
  - `server/`：Go 服务端
  - `android/`：安卓客户端
  - `manual/`：用户手册
  - `docs/`：章程、架构决策、待办、评审
- **单独的仓库**：
  - GeoCrew-Native：原生库构建，编译时间长；
  - GeoCrew-Plugins：插件目录，要接收外部 PR；
  - GeoCrew-Dev.github.io：官网，仓库名是固定的。
- **历史**：原来三个仓库的提交历史，用 `git filter-branch` 改写进对应子目录后合并，内容与原仓库逐字节一致。原仓库已在 2026-11-25 删除，本地和 GitHub 都删了。
- **发布标签带组件前缀**，例如 `server-v0.3.0`、`android-v0.3.0`。更新检查读 `GET /repos/GeoCrew-Dev/GeoCrew/releases?per_page=100`，每个组件只取带自己前缀、不是草稿也不是预发布的版本里最高的一个。为了照顾镜像和分叉项目，旧的单个版本格式（`/releases/latest`）仍然可以读。
- **CI 放在根目录，按改动的路径触发**：`protocol.yml`（OpenAPI 检查，并用一致性检查测 `server/`）、`server.yml`、`android.yml`（安卓客户端；旧版删除时一并删掉，随重写重新加）、`server-release.yml`（`server-v*` 标签）。安卓的发布工作流随重写一起做。
- **Go 模块路径**：`github.com/GeoCrew-Dev/GeoCrew/server`、`github.com/GeoCrew-Dev/GeoCrew/protocol/conformance`。

## 理由

- 协议和实现能在同一个 PR 里原子地修改。
- 两端共用的测试样例放在一处。
- 文档版本和应用版本天然对齐。
- AI 只需要在一个仓库里工作。

Native、Plugins 和官网的节奏或要求不同，所以单独成仓库。

## 代价与后果

- 提交哈希都变了。原仓库已删除，也没有外部引用，所以没有影响。
- 发布要靠标签前缀来区分，服务端和安卓的更新检查都必须按前缀筛选。
- 仓库变大了，而且各目录的许可证不同，见[许可证清单](../licenses.md)。

## 状态

已采纳（2026-11-25）。

2026-11-26：旧版安卓代码和它的 `android.yml` 删除，留在标签 `android-legacy`；新客户端直接写在 `android/` 里。

## 相关

- [ADR 0013](0013-server-gdal-helper.md)
- `protocol/docs/releases.md`
- [待办](../backlog.md)
