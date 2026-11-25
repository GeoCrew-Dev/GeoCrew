# 同勘 GeoCrew：给 AI 代理的工作规则

同勘 GeoCrew 是开源的野外地质与 GIS 工具，由安卓客户端、自部署 Go 服务端和协同协议组成。

## 目录

| 目录 | 内容 |
|---|---|
| `protocol/` | 协同协议、OpenAPI、插件规范、一致性检查 |
| `server/` | Go 服务端 |
| `android/` | 旧版安卓客户端，已冻结，待重写 |
| `manual/` | 用户手册 |
| `docs/` | 章程、架构决策（`docs/adr/`）、待办、合规、威胁模型、许可证、性能预算、评审、技术验证（`docs/spikes/`） |
| `spikes/` | 技术验证用的一次性代码，正式实现时重写 |

相关仓库：GeoCrew-Native（原生库构建）、GeoCrew-Plugins（插件目录）、GeoCrew-Dev.github.io（官网）。

## 开工前

先读 [docs/charter.md](docs/charter.md)，再读和本次改动相关的 ADR（[docs/adr/](docs/adr/README.md)）。改动如果和某份 ADR 冲突，先停下来问，不要自己改设计。

## 规则

- **协议先行**：接口要改，先改 `protocol/` 的文档和 OpenAPI，再改实现。
- **一个 PR 一个纵向切片**：改动尽量控制在约 500 行以内，不算生成文件和测试数据。
- **引入新依赖之前先写 ADR**，同时更新 [docs/licenses.md](docs/licenses.md)。
- **所有界面操作都走动作注册表**，不直接改数据，见 [ADR 0010](docs/adr/0010-action-registry.md)。
- **README 只写真机验证过的功能**，没验证的标"实验"。
- **往后推的事写进 [docs/backlog.md](docs/backlog.md)**，不要只在对话里说。
- **不要直接推 main**：开 PR，PR 由 AI 评审，CI 必须通过。
- **语言**：提交信息用英文 Conventional Commits（`feat(server): ...`），文档用中文。
- **不提交**任何密钥、令牌，以及本机特有的设置（代理地址、`local.properties` 等）。

## 怎么跑测试

- **服务端**：
  - `cd server && go test ./...`
  - PostgreSQL 的契约测试需要先设 `GEOCREW_TEST_POSTGRES`（如 `postgres://postgres:postgres@localhost:5432/postgres?sslmode=disable`），不设时跳过。
  - 提交前确认 `gofmt -l .` 输出为空，`go vet ./...` 通过。
- **协议**：
  - OpenAPI 检查：在 `protocol/` 下运行 `npx --yes @redocly/cli@1.34.5 lint docs/openapi.yaml`。
  - 一致性检查：先启动服务端（`cd server && go run ./cmd/geocrew-server -data /tmp/geocrew-check -push=false`），再到 `protocol/conformance` 下运行 `go run . -server http://127.0.0.1:8080 -v`。
- **安卓（旧版）**：`cd android && ./gradlew test`，需要 JDK 17 和 Android SDK。

## 发布

- 标签带组件前缀：`server-v0.3.0`、`android-v0.3.0`。
- 更新检查读 GitHub 的发布列表，每个组件只取带自己前缀的版本，见 `protocol/docs/releases.md`。
