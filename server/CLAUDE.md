# server/：工作规则

先读根目录的 [CLAUDE.md](../CLAUDE.md)。

- **模块路径**：`github.com/GeoCrew-Dev/GeoCrew/server`。
- **同步核心保持纯 Go**：SQLite 用 modernc，不引入 CGO。GDAL 只在单独的辅助程序里用，见 [ADR 0013](../docs/adr/0013-server-gdal-helper.md)。
- **存储契约测试**：SQLite 和 PostgreSQL 都必须通过 `internal/store/storetest`。新增存储行为时，先在契约测试里加用例。PostgreSQL 的测试要设置 `GEOCREW_TEST_POSTGRES`。
- **数据库迁移**：
  - 1.0 之前，按计划可以重建表结构，因为协议 v1 不兼容 v0，见 [待办](../docs/backlog.md)。
  - 1.0 之后，迁移只能在 `sqlite` 和 `postgres` 两个包的列表末尾追加，而且每条都要有测试。
- **提交前**：`gofmt -l .` 输出为空，`go vet ./...` 通过，`go test -race ./...` 通过。
- **协议先行**：接口改动先改 `../protocol/`，并补上一致性检查。
- **发布**：标签用 `server-v*`，由根目录的 `.github/workflows/server-release.yml` 发布。发布平台是 linux amd64 / arm64、darwin amd64 / arm64、windows amd64。更新检查只取 `server-v*` 前缀的正式版。
