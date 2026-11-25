# protocol/：工作规则

先读根目录的 [CLAUDE.md](../CLAUDE.md)。

- **协议先行**：服务端和客户端的接口要改，先改这里的文档（`docs/`）和 `docs/openapi.yaml`，再改实现。
- **协议 v1 不兼容 v0**：v1 按 [ADR 0008](../docs/adr/0008-sync-and-merge.md) 和 [ADR 0009](../docs/adr/0009-roles-and-permissions.md) 重新设计，不需要照顾 v0 的客户端。v0 的文档在 v1 写好之前留作参考。
- **OpenAPI**：文档和 `docs/openapi.yaml` 必须一致。改完在本目录运行 `npx --yes @redocly/cli@1.34.5 lint docs/openapi.yaml`，检查要通过。
- **一致性检查**（`conformance/`）：协议里每条可以从外部验证的规则，都要在这里有对应的检查。新增接口时同时加检查，CI 会拿它测 `server/`。
- **共用测试样例**：合并、权限、版本比较这些规则，要有一份 Go 和 Kotlin 两端都读的测试样例（JSON），放在 `protocol/testdata/<规则>/` 下，比如合并规则的 `testdata/merge/cases.json`。改规则时先改样例，两端都必须通过。
- **版本与发布**：发布规则写在 `docs/releases.md`，标签带组件前缀（`server-v*`、`android-v*`）。
- **Go 模块路径**：`github.com/GeoCrew-Dev/GeoCrew/protocol/conformance`。
