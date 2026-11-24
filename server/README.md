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
| `-push` | `GEOCREW_PUSH` | `true` | 向组员登记的推送地址发通知（UnifiedPush） |
| `-push-allow-private` | `GEOCREW_PUSH_ALLOW_PRIVATE` | `false` | 允许推送地址在内网、本机或用 http（推送分发器和服务端在同一局域网时打开） |

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

目标文件以 `.zip` 结尾时导出为**工程包**（和安卓端「导出工程包」同一种格式）：

```bash
geocrew-server -data /var/lib/geocrew admin export 3f9a /tmp/北山填图.zip
```

包里有全部记录、记录引用的照片和工作组的记录表单，安卓端在工程列表右上角「导入」即可打开为一个新工程（不绑定工作组），适合季末归档或交给不在组里的人。组员同样可以请求 `GET /api/v0/workspaces/{id}/export.geocrew.zip` 下载。

## 推送通知

组员可以登记一个推送地址（`PUT /api/v0/workspaces/{id}/push`），之后有新的组内消息、有人在记录下留言、或者有记录被指派给自己时，服务端会向这个地址 POST 一条很短的 JSON。这和 [UnifiedPush](https://unifiedpush.org/) 的做法一致：手机上装一个分发器（如 ntfy），由它把通知唤醒到同勘，不依赖谷歌服务，分发器也可以自己部署。

通知里只有类型、工作组、记录 ID、发送者 ID 和序号，不含消息正文或记录内容，因为分发器常常是第三方运营的；应用被唤醒后再通过有身份验证的接口取详情。登记时带上设备的 Web Push 密钥（`keys: {p256dh, auth}`）后，通知还会按 RFC 8291（aes128gcm）加密，分发器只能看到密文；实现用 RFC 附录的示例数据做了校验。为防止服务端被用来探测自己所在的内网，默认只向公网 https 地址投递，连接时还会检查解析出的 IP；分发器在局域网内时打开 `-push-allow-private`。分发器回复 404 / 410 时登记会被自动删除，组员被移出时登记一并删除。

## 代码结构

| 目录 | 内容 |
| --- | --- |
| `cmd/geocrew-server` | 入口、优雅退出、健康检查 |
| `internal/api` | HTTP 与 WebSocket 接口 |
| `internal/store` | 存储接口（与具体数据库无关） |
| `internal/store/sqlite` | 默认 SQLite 实现（纯 Go，无 CGO），含迁移 |
| `internal/blob` | 附件按 SHA-256 存储在本地磁盘 |
| `internal/realtime` | 实时消息分发（位置、在线状态、变更通知） |
| `internal/push` | 推送通知投递（UnifiedPush 风格，内容最少、防内网探测） |
| `internal/export` | 导出为 GeoJSON 和工程包 |
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
