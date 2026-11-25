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
| `-database` | `GEOCREW_DATABASE_URL` | 空 | PostgreSQL 连接地址（`postgres://…`）；留空用数据目录里的 SQLite，见下文 |
| `-max-upload-mb` | `GEOCREW_MAX_UPLOAD_MB` | `32` | 单个附件上限（MiB） |
| `-log-level` | `GEOCREW_LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `-log-format` | `GEOCREW_LOG_FORMAT` | `text` | `text` 或 `json` |
| `-disable-create` | `GEOCREW_DISABLE_CREATE` | `false` | 不再接受新建工作组（自己的组建好后可打开） |
| `-trust-proxy` | `GEOCREW_TRUST_PROXY` | `false` | 在反向代理后面时打开，按 `X-Forwarded-For` 识别客户端 |
| `-join-per-minute` | `GEOCREW_JOIN_PER_MINUTE` | `10` | 每个客户端地址每分钟可尝试新建 / 加入的次数，防止猜测邀请码 |
| `-push` | `GEOCREW_PUSH` | `true` | 向组员登记的推送地址发通知（UnifiedPush） |
| `-push-allow-private` | `GEOCREW_PUSH_ALLOW_PRIVATE` | `false` | 允许推送地址在内网、本机或用 http（推送分发器和服务端在同一局域网时打开） |

### 使用 PostgreSQL

默认的 SQLite 单文件对一般野外小组足够，不需要改。已经有 PostgreSQL（12 及以上），或者想在几台机器上跑多个服务端进程共用一个库时，可以改用它：

```bash
createdb geocrew
geocrew-server -data /var/lib/geocrew -database "postgres://geocrew:密码@localhost/geocrew"
```

首次启动自动建表；多个进程同时启动时用咨询锁保证只迁移一次。附件仍放在 `-data` 目录的 `files/` 下，多台机器时需要共享这个目录（NFS 等）。两种数据库存的内容完全相同（时间和 GeoJSON 都按原文保存），不需要 PostGIS。已有的 SQLite 数据可以原样搬过去（成员令牌、邀请码、记录序号、讨论和修改历史都保留，组员手机不用重新加入）：先停掉服务，建好空库，再运行

```bash
geocrew-server -data /var/lib/geocrew admin copy "postgres://geocrew:密码@localhost/geocrew"
```

然后加上 `-database` 启动。目标库里已有工作组时会拒绝复制；复制在一个事务里完成，失败不会留下半套数据。反方向也可以：`admin copy /path/new.db` 把 PostgreSQL 的数据复制成一个 SQLite 文件。备份改用 `pg_dump`，`admin backup` 只用于 SQLite。

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

### 清理附件

```bash
geocrew-server -data /var/lib/geocrew admin files 3f9a          # 只统计
geocrew-server -data /var/lib/geocrew admin files 3f9a prune    # 删除没有记录引用的附件
```

组员删掉照片或删掉记录后，原图仍留在服务器上。`files` 列出工作组附件占用的空间、被记录引用的数量、没有任何记录引用的数量，以及记录引用了但服务器上还没有的数量（通常是组员还没联网上传）。`prune` 只删除没有引用、而且上传超过 1 天的附件：记录任何一个历史版本里出现过的照片和录音都算有引用，因为「修改历史」可以把它恢复回来；应用总是先传照片再传记录，所以新传的附件会保留。建议先备份再清理。

## 导出数据

```bash
geocrew-server -data /var/lib/geocrew admin export 3f9a /tmp/北山填图.geojson
```

把工作组当前的全部记录（不含已删除的）导出成一个 GeoJSON，QGIS、ArcGIS 可以直接打开，属性原样保留。组员也可以带着自己的令牌请求 `GET /api/v0/workspaces/{id}/export.geojson` 下载同样的文件。

目标文件以 `.zip` 结尾时导出为**工程包**（和安卓端「导出工程包」同一种格式）：

```bash
geocrew-server -data /var/lib/geocrew admin export 3f9a /tmp/北山填图.zip
```

包里有全部记录、记录引用的照片和录音（`audio/` 目录）以及工作组的记录表单，安卓端在工程列表右上角「导入」即可打开为一个新工程（不绑定工作组），适合季末归档或交给不在组里的人。组员同样可以请求 `GET /api/v0/workspaces/{id}/export.geocrew.zip` 下载。

目标文件以 `.csv` 结尾时导出**样品清单**：每件样品一行（编号、类型、所在记录、坐标、岩性、采样时间、备注），按编号自然排序，和安卓端「样品清单」同样的列，可直接改成送样单；样品带化验结果（协议 §6.4 `results`）时，每个分析项在后面多一列。组员请求 `GET /api/v0/workspaces/{id}/export.samples.csv?tz_offset=480` 也能下载（`tz_offset` 是采样时间所用时区，单位分钟，默认 UTC）；以 `=`、`+`、`@` 开头的单元格会加上 `'`，防止表格软件当成公式执行。

目标文件以 `.sections.csv` 结尾时导出**实测剖面分层表**：每个剖面的每一层一行（剖面、层号、起止斜距、岩性、产状及来源、真厚度、备注），厚度按协议第 6.5 节由导线和产状算出，没有实测产状的层借用最近一层并注明「借用」。组员请求 `GET /api/v0/workspaces/{id}/export.sections.csv` 也能下载。

目标文件以 `.routes.csv` 结尾时导出**地质路线记录表**：每条路线一个目的行、按走过的顺序每个点一行（点号、类型、坐标、海拔、岩性、产状、点上描述、至下一点描述）和一个小结行，路线按日期和路线号排序，格式与安卓端「地质路线记录表」相同（协议第 6.6 节）。组员请求 `GET /api/v0/workspaces/{id}/export.routes.csv` 也能下载（能力名 `routes`）。

**记录核对**：`GET /api/v0/workspaces/{id}/checks` 列出值得再看一眼的记录：编号重复、样品编号重复、没有位置、定位精度差（默认超过 ±30 米）、位置离群（离其他记录 50 千米以上）、路线点已删除，规则与安卓端「待核对」相同（能力名 `checks`）。网页查看页有「待核对」按钮，把这些记录和原因列出来。

目标文件以 `.kml` 结尾时导出 **KML**：每条有位置的记录一个地标，名称、备注、其余属性（ExtendedData）都带上，颜色按记录自带的 simplestyle 或组长设的共享图例（协议 §2.2 `legend`），在奥维互动地图、Google Earth 里打开即是同一套配色。组员请求 `GET /api/v0/workspaces/{id}/export.kml` 也能下载（能力名 `kml`）。

目标文件以 `.gpkg` 结尾时导出 **GeoPackage**：QGIS、ArcGIS 等桌面 GIS 直接打开的单文件数据库，点、线、面各一张表（「点记录」「线记录」「面记录」，经纬度 EPSG:4326），每条记录一行：记录 id、类型、名称、备注、岩性、产状、线型、集合时间、颜色（自带的或共享图例的）、最后修改人和时间，以及全部属性的 JSON。组员请求 `GET /api/v0/workspaces/{id}/export.gpkg` 也能下载（能力名 `gpkg`），网页查看页有「下载 GeoPackage」按钮。

## 电脑端查看

组员可以在手机上生成一个**查看链接**（`POST /api/v0/workspaces/{id}/view-links`，有效期 1 到 168 小时，默认 24 小时），在电脑浏览器打开服务端的 `/view/` 页面，就能看到工作组的全部记录、照片、录音、组员的实时位置和最近半小时的足迹、组长设的岩性 / 地层图例配色、每人今天的记录 / 样品 / 轨迹、组内消息和记录下的讨论（最近 6 小时的紧急消息、最近一次点名的回应情况、在外的人（超时标红）和下一次集合置顶，谁求助、谁还没回应一眼可见）、产状符号（断层等线上的产状画在线的中点）和地质界线 / 断层线型（右上角可开山体阴影，看地形起伏；列表上方可按组员和日期筛选，地图同步只显示筛出的记录），并下载 GeoJSON、KML、工程包、样品清单或剖面分层表。适合组长在驻地或办公室里看全组进度。

链接只能读：不能改记录、不能留言或发紧急消息、不能上报位置，打开网页也不会让这名组员显示为「在线」。令牌放在网址的 `#` 后面，浏览器不会把它发给服务端，所以不会出现在访问日志里。`DELETE /api/v0/workspaces/{id}/view-links` 撤销自己生成的全部链接，组员被移出时链接一并失效。

页面用 MapLibre GL（从 unpkg 加载，带完整性校验）和 OpenStreetMap 底图，在服务端没有其他依赖。如果部署在内网、电脑上不了外网，这个页面打不开地图，其余接口不受影响。

## 推送通知

组员可以登记一个推送地址（`PUT /api/v0/workspaces/{id}/push`），之后有新的组内消息、有人在记录下留言、或者有记录被指派给自己时，服务端会向这个地址 POST 一条很短的 JSON。组员发出的**紧急消息**（求助或集合，`urgent: true`）会发给其他所有登记了地址的组员，不受订阅类型限制；只读成员不能发普通消息，但可以发紧急消息。组长可以发起**点名**（`roll_call: true`，例如收工前让大家报平安），通知发给全组，组员的回应（`answer_to` 指向点名）只通知发起的组长，同样不受订阅类型限制，只读成员也能回应。组员单独出去前可以**报外出**（`back_by` 写预计返回时间，正文写去向），回来报**已返回**（`back_from` 指向那条外出，本人或组长都能报）；过了时间还没报返回，服务端每分钟检查一次，给全组发一条 `overdue` 推送，每条外出只发一次。这和 [UnifiedPush](https://unifiedpush.org/) 的做法一致：手机上装一个分发器（如 ntfy），由它把通知唤醒到同勘，不依赖谷歌服务，分发器也可以自己部署。

通知里只有类型、工作组、记录 ID、发送者 ID 和序号，不含消息正文或记录内容，因为分发器常常是第三方运营的；应用被唤醒后再通过有身份验证的接口取详情。登记时带上设备的 Web Push 密钥（`keys: {p256dh, auth}`）后，通知还会按 RFC 8291（aes128gcm）加密，分发器只能看到密文；实现用 RFC 附录的示例数据做了校验。为防止服务端被用来探测自己所在的内网，默认只向公网 https 地址投递，连接时还会检查解析出的 IP；分发器在局域网内时打开 `-push-allow-private`。分发器回复 404 / 410 时登记会被自动删除，组员被移出时登记一并删除。

## 代码结构

| 目录 | 内容 |
| --- | --- |
| `cmd/geocrew-server` | 入口、优雅退出、健康检查 |
| `internal/api` | HTTP 与 WebSocket 接口 |
| `internal/store` | 存储接口（与具体数据库无关） |
| `internal/store/sqldb` | 基于 `database/sql` 的通用实现：全部查询写一次，引擎差异（占位符、行锁、迁移记录、备份）放在 `Dialect` 里 |
| `internal/store/sqlite` | 默认 SQLite 方言（纯 Go，无 CGO）与迁移 |
| `internal/store/postgres` | PostgreSQL 方言与迁移（pgx 驱动） |
| `internal/store/storetest` | 每种存储都要通过的契约测试（含并发推送） |
| `internal/blob` | 附件按 SHA-256 存储在本地磁盘 |
| `internal/realtime` | 实时消息分发（位置、在线状态、变更通知） |
| `internal/push` | 推送通知投递（UnifiedPush 风格，内容最少、防内网探测） |
| `internal/export` | 导出为 GeoJSON 和工程包 |
| `internal/config` | 参数与环境变量 |
| `internal/admin` | 运维命令（列出、恢复组长、备份、导出） |

再加一种 SQL 数据库只需写一个 `Dialect` 和它的建表语句，并让测试调用 `storetest.Run`；非 SQL 的存储直接实现 `store.Store` 接口。改表结构时，在 `sqlite` 和 `postgres` 两个包的迁移列表末尾各追加一条。

## 本地开发

```bash
go test -race ./...
go run ./cmd/geocrew-server
# PostgreSQL 的契约测试需要一个可用的库（不设置时跳过）：
GEOCREW_TEST_POSTGRES="postgres://postgres:postgres@localhost:5432/postgres?sslmode=disable" go test ./internal/store/...
```

## 许可

Apache-2.0
