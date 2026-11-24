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

Caddy 会自动申请证书，并原样转发 WebSocket。放在反向代理后面时请设置 `GEOCREW_TRUST_PROXY=true`，否则所有人共用一个限流额度。

安卓客户端只通过 HTTPS 连接服务器（系统默认禁止明文 HTTP），所以正式使用必须配好证书。

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
| `-disable-create` | `GEOCREW_DISABLE_CREATE` | `false` | 不再接受新建工作组（自己的组建好后可打开） |
| `-trust-proxy` | `GEOCREW_TRUST_PROXY` | `false` | 在反向代理后面时打开，按 `X-Forwarded-For` 识别客户端 |
| `-join-per-minute` | `GEOCREW_JOIN_PER_MINUTE` | `10` | 每个客户端地址每分钟可尝试新建 / 加入的次数，防止猜测邀请码 |

## 管理命令

同一个可执行文件带有运维命令，直接读写数据目录，服务运行时也可以用：

```bash
geocrew-server -data /var/lib/geocrew admin workspaces          # 列出工作组
geocrew-server -data /var/lib/geocrew admin members 3f9a        # 成员与当前邀请码
geocrew-server -data /var/lib/geocrew admin invite 3f9a         # 更换邀请码
geocrew-server -data /var/lib/geocrew admin role 3f9a 7c21 leader
geocrew-server -data /var/lib/geocrew admin revoke 3f9a 7c21
# Docker：docker compose exec geocrew geocrew-server admin workspaces
```

工作组和成员可以只写 ID 的开头几位。组长换了手机、旧设备找不回来时：用 `invite` 拿到新邀请码，在新手机上加入，再用 `role` 把新设备设为组长，最后 `revoke` 掉旧设备。

## 备份

```bash
geocrew-server -data /var/lib/geocrew admin backup /backups/geocrew-$(date +%F).db
```

服务运行时也能得到一致的数据库副本（目标文件已存在时不会覆盖）。附件在数据目录的 `files/` 下，按内容命名、只增不改，用 `rsync` 等工具增量复制即可。恢复时把备份文件改名为 `geocrew.db`，和 `files/` 一起放回数据目录。

## 导出数据

```bash
geocrew-server -data /var/lib/geocrew admin export 3f9a /tmp/北山填图.geojson
```

把工作组当前的全部记录（不含已删除的）导出成一个 GeoJSON，QGIS、ArcGIS 可以直接打开，属性原样保留。组员也可以带着自己的令牌请求 `GET /api/v0/workspaces/{id}/export.geojson` 下载同样的文件。

## 代码结构

| 目录 | 内容 |
| --- | --- |
| `cmd/geocrew-server` | 入口、优雅退出、健康检查 |
| `internal/api` | HTTP 与 WebSocket 接口 |
| `internal/store` | 存储接口（与具体数据库无关） |
| `internal/store/sqlite` | 默认 SQLite 实现（纯 Go，无 CGO），含迁移 |
| `internal/blob` | 附件按 SHA-256 存储在本地磁盘 |
| `internal/realtime` | 实时消息分发（位置、在线状态、变更通知） |
| `internal/export` | 导出为 GeoJSON 等标准格式 |
| `internal/config` | 参数与环境变量 |
| `internal/admin` | 运维命令（列出、恢复组长、备份、导出） |

换用 PostgreSQL/PostGIS 时只需新增一个实现 `store.Store` 接口的包。

## 本地开发

```bash
go test -race ./...
go run ./cmd/geocrew-server
```

## 许可

Apache-2.0
