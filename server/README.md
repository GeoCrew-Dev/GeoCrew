# 同勘 GeoCrew 服务端

同勘 GeoCrew 小组协同功能的自部署服务端参考实现，按 [geocrew-protocol](https://github.com/GeoCrew-Dev/GeoCrew-Protocol) 中的协议 v0 实现。单个可执行文件，无外部依赖，数据存在一个 SQLite 文件和一个附件目录里。

> 状态：早期版本。已实现工作区与邀请码、角色（组长 / 成员 / 只读）、要素增量同步与冲突处理、修改历史、附件上传下载、WebSocket 实时位置与变更通知。

## 快速部署

需要一台装有 Docker 的 Linux 服务器：

```bash
git clone https://github.com/GeoCrew-Dev/GeoCrew-Server.git
cd geocrew-server
docker compose up -d
curl http://localhost:8080/api/v0/info
```

数据保存在 Docker 卷 `geocrew-data`（容器内 `/data`）。生产环境请在前面加一层 HTTPS 反向代理，例如 Caddy：

```
geo.example.org {
    reverse_proxy localhost:8080
}
```

Caddy 会自动申请证书，并原样转发 WebSocket。

## 不用 Docker

```bash
go build -o geocrew-server ./cmd/geocrew-server
./geocrew-server -addr :8080 -data /var/lib/geocrew
```

## 配置

命令行参数优先于环境变量。

| 参数 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `-addr` | `GEOCREW_ADDR` | `:8080` | 监听地址 |
| `-name` | `GEOCREW_NAME` | `GeoCrew Server` | 服务器显示名称 |
| `-data` | `GEOCREW_DATA_DIR` | `./data`（镜像内 `/data`） | 数据库与附件目录 |
| `-max-upload-mb` | `GEOCREW_MAX_UPLOAD_MB` | `32` | 单个附件上限（MiB） |
| `-log-level` | `GEOCREW_LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `-log-format` | `GEOCREW_LOG_FORMAT` | `text` | `text` 或 `json` |

## 备份

停服务后复制数据目录即可；不停服务时可用 `sqlite3 geocrew.db ".backup backup.db"` 备份数据库，再复制 `files/` 目录。

## 代码结构

| 目录 | 内容 |
| --- | --- |
| `cmd/geocrew-server` | 入口、优雅退出、健康检查 |
| `internal/api` | HTTP 与 WebSocket 接口 |
| `internal/store` | 存储接口（与具体数据库无关） |
| `internal/store/sqlite` | 默认 SQLite 实现（纯 Go，无 CGO），含迁移 |
| `internal/blob` | 附件按 SHA-256 存储在本地磁盘 |
| `internal/realtime` | 实时消息分发（位置、在线状态、变更通知） |
| `internal/config` | 参数与环境变量 |

换用 PostgreSQL/PostGIS 时只需新增一个实现 `store.Store` 接口的包。

## 本地开发

```bash
go test -race ./...
go run ./cmd/geocrew-server
```

## 许可

Apache-2.0
