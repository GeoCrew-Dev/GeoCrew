# 同勘 GeoCrew 服务端

同勘 GeoCrew 小组协同功能的自部署服务端参考实现，按 [geocrew-protocol](https://github.com/GeoCrew-Dev/GeoCrew-Protocol) 中的协议 v0 实现。单个可执行文件，无外部依赖，数据存在一个 SQLite 文件和一个附件目录里。

> 状态：早期版本。已实现工作区与邀请码、角色（组长 / 成员 / 只读）、要素增量同步与冲突处理、修改历史、附件上传下载、WebSocket 实时位置与变更通知。

## 快速部署

需要一台装有 Docker 的 Linux 服务器：

```bash
git clone https://github.com/GeoCrew-Dev/GeoCrew-Server.git
cd geocrew-server
docker compose up -d          # 拉取已发布的镜像；还没有发布版本时加 --build 在本机构建
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

从 [Releases](https://github.com/GeoCrew-Dev/GeoCrew-Server/releases) 下载对应平台的程序（`geocrew-server-linux-amd64` 等），先核对 `SHA256SUMS`：

```bash
sha256sum -c SHA256SUMS --ignore-missing
chmod +x geocrew-server-linux-amd64 && mv geocrew-server-linux-amd64 /usr/local/bin/geocrew-server
geocrew-server -addr :8080 -data /var/lib/geocrew
```

或者自己编译：`go build -o geocrew-server ./cmd/geocrew-server`。用 systemd 托管时建议 `Restart=on-failure`，程序文件所在目录要让服务用户可写，才能在管理页一键更新。

## 更新

每次发布都附带各平台程序、`SHA256SUMS` 和更新说明（按提交记录自动生成）。服务端每天查一次有没有新版本，有就在日志里提示一行；不想联网检查可以设 `GEOCREW_UPDATE_CHECK=false`，用自己的镜像源可以设 `GEOCREW_UPDATE_URL`（GitHub 接口格式）。

**管理页**：设 `GEOCREW_ADMIN_ADDR=127.0.0.1:9181` 后，在这个地址另开一个管理页，显示当前版本、数据库、最新版本和更新说明，以及概览：各工作组的组长、成员数、记录数、附件占用和最近活动，数据库大小、剩余磁盘空间和定时备份（`/api/overview` 给出同样内容的 JSON，带令牌访问）。令牌用 `GEOCREW_ADMIN_TOKEN` 指定（至少 16 位），不指定就在数据目录生成 `admin-token` 文件（只有服务用户可读）。管理页能替换程序，请只监听本机，用 `ssh -L 9181:127.0.0.1:9181 服务器` 访问，不要通过反向代理公开。

**一键更新（单文件部署）**：在管理页点「更新到 x.y.z」，或者运行 `geocrew-server -data /var/lib/geocrew admin update apply`。过程是：

1. 下载本平台程序，按 `SHA256SUMS` 校验，校验不符直接丢弃；
2. SQLite 先备份数据库到 `数据目录/update/`（保留最近 3 份），再用新程序在副本上跑一遍数据库升级，失败就不换；PostgreSQL 不会自动备份，请先 `pg_dump`；
3. 旧程序改名为 `geocrew-server.prev`，新程序放到原位置；
4. 管理页触发的更新会自动重启进新版本（Linux、macOS）；命令行更新需要再 `systemctl restart geocrew-server`；
5. 新版本启动后 60 秒内要通过自己的健康检查，否则自动换回旧程序、恢复更新前的数据库并重启（这一分钟里收到的改动会丢失，所以尽量挑没人同步的时候更新）。启动后崩溃退出的，下次启动直接回滚。

`admin update` 只查看、`admin update status` 看上次更新结果、`admin update rollback` 在更新成功之后手动换回旧程序（不动数据库）。开发版本（自己编译、版本号是 dev）不会被更新替换。

**Docker**：容器里的程序随镜像更新，管理页会给出命令：

```bash
docker compose pull && docker compose up -d
```

想全自动的话可以加 [Watchtower](https://containrrr.dev/watchtower/) 这类工具定时拉新镜像。数据在数据卷里，换镜像不影响；建议先 `admin backup` 一份。固定版本用 `GEOCREW_TAG=0.2.0 docker compose up -d`。

**发布新版本**（维护者）：打 `v0.2.0` 这样的标签并推送，`Release` 工作流会跑测试、编译 linux/amd64、linux/arm64、linux/arm（ARMv7）、darwin、windows 程序，生成 `SHA256SUMS` 和更新说明，推送 `ghcr.io/geocrew-dev/geocrew-server` 多架构镜像（`0.2.0` 和 `latest`）。带 `-rc.1` 这类后缀的标签发成预发布，不会推 `latest`。

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
| `-backup-dir` | `GEOCREW_BACKUP_DIR` | 空 | 定时备份放到这个目录（只用于 SQLite），空表示不定时备份 |
| `-backup-every` | `GEOCREW_BACKUP_EVERY` | `24h` | 定时备份间隔，至少 `10m` |
| `-backup-keep` | `GEOCREW_BACKUP_KEEP` | `7` | 保留最近几份数据库快照 |
| `-metrics-addr` | `GEOCREW_METRICS_ADDR` | 空 | 在这个地址另开一个端口提供 `/metrics`（Prometheus 文本格式），如 `127.0.0.1:9180`；空表示不提供 |
| `-admin-addr` | `GEOCREW_ADMIN_ADDR` | 空 | 管理页地址（版本、更新说明、一键更新、工作组与备份概览），如 `127.0.0.1:9181`；空表示不开 |
| `-admin-token` | `GEOCREW_ADMIN_TOKEN` | 空 | 管理页令牌，至少 16 位；空则生成在数据目录的 `admin-token` |
| `-update-check` | `GEOCREW_UPDATE_CHECK` | `true` | 每天检查一次新版本并写日志 |
| `-update-url` | `GEOCREW_UPDATE_URL` | GitHub Releases | 版本检查地址（GitHub 接口格式，自建镜像或分叉时改） |

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
geocrew-server -data /var/lib/geocrew admin archive 3f9a        # 归档（reopen 重新打开）
geocrew-server -data /var/lib/geocrew admin update              # 有没有新版本（apply 安装，见「更新」）
# Docker：docker compose exec geocrew geocrew-server admin workspaces
```

工作组和成员可以只写 ID 的开头几位。组长换了手机、旧设备找不回来时：用 `invite` 拿到新邀请码，在新手机上加入，再用 `role` 把新设备设为组长，最后 `revoke` 掉旧设备。

## 监控

设了 `GEOCREW_METRICS_ADDR` 后，服务端在这个地址另开一个端口，`GET /metrics` 返回 Prometheus 文本格式的指标，Prometheus、VictoriaMetrics、Grafana Agent 都能直接抓取，`curl` 也能看：

- `geocrew_http_requests_total`：按方法、路由模板（如 `GET /api/v0/workspaces/{ws}/features`，不含具体 ID）和状态码计数
- `geocrew_http_request_duration_seconds`：按路由模板的耗时直方图
- `geocrew_realtime_connections`：实时通道连接数，分组员设备和网页查看
- `geocrew_realtime_workspaces`、`geocrew_workspaces`：有人在线的工作组数、服务器上的工作组总数
- `geocrew_uptime_seconds`：运行时长

这个端口不做鉴权，请只监听本机或内网地址，不要通过反向代理公开。

## 备份

```bash
geocrew-server -data /var/lib/geocrew admin backup /backups/geocrew-$(date +%F).db
```

服务运行时也能得到一致的数据库副本（目标文件已存在时不会覆盖）。附件在数据目录的 `files/` 下，按内容命名、只增不改，用 `rsync` 等工具增量复制即可。恢复时把备份文件改名为 `geocrew.db`，和 `files/` 一起放回数据目录。

也可以让服务端自己定时备份：设 `GEOCREW_BACKUP_DIR`（最好在另一块盘或挂载的网络存储上），启动约一分钟后备份一次，之后每隔 `GEOCREW_BACKUP_EVERY` 一次。每次在该目录写一份 `geocrew-年月日-时分秒.db`（UTC 时间，先写临时文件，写完才改名，不会留下半份），只保留最近 `GEOCREW_BACKUP_KEEP` 份；附件增量复制到该目录的 `files/`，服务器上清理掉的附件在这里保留，因为较早的快照可能还引用它们。恢复时挑一份快照改名为 `geocrew.db`，和备份目录的 `files/` 一起放回数据目录。失败只记日志，下一次照常重试。用 PostgreSQL 时这项设置会被忽略，请用 `pg_dump`。

### 清理附件

```bash
geocrew-server -data /var/lib/geocrew admin files 3f9a          # 只统计
geocrew-server -data /var/lib/geocrew admin files 3f9a prune    # 删除没有记录引用的附件
```

组员删掉照片或删掉记录后，原图仍留在服务器上。`files` 列出工作组附件占用的空间、被记录引用的数量、没有任何记录引用的数量，以及记录引用了但服务器上还没有的数量（通常是组员还没联网上传）。`prune` 只删除没有引用、而且上传超过 1 天的附件：记录任何一个历史版本里出现过的照片和录音都算有引用，因为「修改历史」可以把它恢复回来；组长共享的叠加图（共享设置的 `overlays`）也算有引用；应用总是先传照片再传记录，所以新传的附件会保留。建议先备份再清理。

### 删除工作组

```bash
geocrew-server -data /var/lib/geocrew admin delete 3f9a           # 只列出将删除的内容
geocrew-server -data /var/lib/geocrew admin delete 3f9a confirm   # 真的删除
```

项目结束但数据还要留着看时，先让组长在应用里「归档」工作组（`PATCH /api/v0/workspaces/{id}`，`{"archived": true}`，也可以顺便改名）：归档后记录、设置、讨论和附件都只能读不能改，邀请码不能再加入，`admin workspaces` 在名称后标「已归档」，组长随时可以重新打开；运维也可以用 `admin archive` / `admin reopen`。确实不再需要时，`delete` 永久删除整个工作组：成员和令牌（查看链接一并失效）、全部记录和修改历史、讨论、推送登记和附件目录。不加 `confirm` 只显示工作组名称、成员数、记录数和附件占用的空间。删除不可恢复，请先 `backup`；组员手机上的数据不受影响，仍可以导出。定时备份的快照里还会留着它，按保留份数逐渐淘汰，备份目录里的附件需要手动删除。

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

目标文件以 `.boreholes.csv` 结尾时导出**钻孔分层表**：每个钻孔的每一层一行（孔号、孔口经纬度和高程、终孔深度、水位埋深、层号、层顶和层底深度、厚度、岩性、描述），层顶取上一层的层底，规则见协议第 6.11 节，列与安卓端的「钻孔分层表」一致。组员请求 `GET /api/v0/workspaces/{id}/export.boreholes.csv` 也能下载。

目标文件以 `.structures.csv` 结尾时导出**产状表**：每条面状产状（倾向、倾角、走向，断层和地质界线注明线型）和线状构造（倾伏向、倾伏角、线理或擦痕等类型，协议第 6.12 节）各一行，带位置和岩性；同一条记录两种都有就占两行。角度列是纯数字，可直接粘进赤平投影软件。列与安卓端的「产状表」一致，组员请求 `GET /api/v0/workspaces/{id}/export.structures.csv` 也能下载。

目标文件以 `.workload.csv` 结尾时导出**工作量统计**：每天每个组员一行，列出新建记录、其中的地质点、修改次数、样品、照片和轨迹里程，每人最后有一行合计。记录的创建人和创建日按它最早的一个版本算；样品和照片按采样、拍摄时间计到记录创建人名下（多人共用一条记录时会偏向创建人）；已离开工作组的人显示为「已离开的组员」。按服务器所在时区分天。组员请求 `GET /api/v0/workspaces/{id}/export.workload.csv?tz_offset=480`（本地时区比 UTC 快的分钟数）也能下载，网页查看页的按钮会带上浏览器时区。

目标文件以 `.routes.csv` 结尾时导出**地质路线记录表**：每条路线一个目的行、按走过的顺序每个点一行（点号、类型、坐标、海拔、岩性、产状、点上描述、至下一点描述）和一个小结行，路线按日期和路线号排序，格式与安卓端「地质路线记录表」相同（协议第 6.6 节）。组员请求 `GET /api/v0/workspaces/{id}/export.routes.csv` 也能下载（能力名 `routes`）。

**记录核对**：`GET /api/v0/workspaces/{id}/checks` 列出值得再看一眼的记录：编号重复、样品编号重复、没有位置、定位精度差（默认超过 ±30 米）、位置离群（离其他记录 50 千米以上）、路线点已删除，规则与安卓端「待核对」相同（能力名 `checks`）。网页查看页有「待核对」按钮，把这些记录和原因列出来。

目标文件以 `.kml` 结尾时导出 **KML**：每条有位置的记录一个地标，名称、备注、其余属性（ExtendedData）都带上，颜色按记录自带的 simplestyle 或组长设的共享图例（协议 §2.2 `legend`），在奥维互动地图、Google Earth 里打开即是同一套配色。组员请求 `GET /api/v0/workspaces/{id}/export.kml` 也能下载（能力名 `kml`）。轨迹写成 KML 的时间范围（开始、结束时间），Google Earth 的时间滑块可用；逐点时间 `times_s` 不列进属性。

目标文件以 `.gpx` 结尾时导出 **GPX 1.1**：点成为航点，轨迹成为航迹（记录带逐点时间 `times_s` 时每个点都有时间，协议 §6.13），其他线（地质界线、路线、剖面线）成为航线，面没有 GPX 形式不导出。可导入 GPS 手持机、两步路、奥维等户外软件。组员请求 `GET /api/v0/workspaces/{id}/export.gpx` 也能下载（能力名 `gpx`）。

目标文件以 `.gpkg` 结尾时导出 **GeoPackage**：QGIS、ArcGIS 等桌面 GIS 直接打开的单文件数据库，点、线、面各一张表（「点记录」「线记录」「面记录」，经纬度 EPSG:4326），每条记录一行：记录 id、类型、名称、备注、岩性、产状、线型、集合时间、颜色（自带的或共享图例的）、最后修改人和时间，以及全部属性的 JSON。组员请求 `GET /api/v0/workspaces/{id}/export.gpkg` 也能下载（能力名 `gpkg`），网页查看页有「下载 GeoPackage」按钮。

## 电脑端查看

组员可以在手机上生成一个**查看链接**（`POST /api/v0/workspaces/{id}/view-links`，有效期 1 到 168 小时，默认 24 小时），在电脑浏览器打开服务端的 `/view/` 页面，就能看到工作组的全部记录、照片、录音、组员的实时位置和最近半小时的足迹、组长设的岩性 / 地层图例配色、每人今天的记录 / 样品 / 轨迹、组内消息和记录下的讨论（组内公告、最近 6 小时的紧急消息、最近一次点名的回应情况、在外的人（超时标红）和下一次集合置顶，谁求助、谁还没回应一眼可见）、产状符号（断层等线上的产状画在线的中点）和地质界线 / 断层线型（右上角可开山体阴影，看地形起伏；组长共享了底图或叠加图时，左上角可选共享底图，图例里可开关叠加图；列表上方可按组员和日期筛选，地图同步只显示筛出的记录；轨迹带逐点时间时，地图下方可拖动或播放回放，看当时每人走到了哪里，点开轨迹可看起止时间、行进时间与平均速度和停留处），并下载 GeoJSON、KML、GPX、工程包、样品清单、剖面分层表、钻孔分层表、产状表或工作量统计（钻孔记录在地图上点开可看孔深、水位和分层）。适合组长在驻地或办公室里看全组进度。

链接只能读：不能改记录、不能留言或发紧急消息、不能上报位置，打开网页也不会让这名组员显示为「在线」。令牌放在网址的 `#` 后面，浏览器不会把它发给服务端，所以不会出现在访问日志里。`DELETE /api/v0/workspaces/{id}/view-links` 撤销自己生成的全部链接，组员被移出时链接一并失效。

页面用 MapLibre GL（从 unpkg 加载，带完整性校验）和 OpenStreetMap 底图，在服务端没有其他依赖。共享底图直接从图源加载，所以只有 https 地址的图源能在这个页面上显示（浏览器会拦下 https 页面里的 http 请求），手机上不受这个限制。如果部署在内网、电脑上不了外网，这个页面打不开地图，其余接口不受影响。

## 推送通知

组员可以登记一个推送地址（`PUT /api/v0/workspaces/{id}/push`），之后有新的组内消息、有人在记录下留言、或者有记录被指派给自己时，服务端会向这个地址 POST 一条很短的 JSON。组员发出的**紧急消息**（求助或集合，`urgent: true`）会发给其他所有登记了地址的组员，不受订阅类型限制；只读成员不能发普通消息，但可以发紧急消息。组长可以发起**点名**（`roll_call: true`，例如收工前让大家报平安），通知发给全组，组员的回应（`answer_to` 指向点名）只通知发起的组长，同样不受订阅类型限制，只读成员也能回应。组员单独出去前可以**报外出**（`back_by` 写预计返回时间，正文写去向），回来报**已返回**（`back_from` 指向那条外出，本人或组长都能报）；过了时间还没报返回，服务端每分钟检查一次，给全组发一条 `overdue` 推送，每条外出只发一次。组长在共享设置里发了新的**组内公告**（`notice` 键）时，其他组员收到一条 `notice` 推送；只改别的设置或撤下公告不推送。这和 [UnifiedPush](https://unifiedpush.org/) 的做法一致：手机上装一个分发器（如 ntfy），由它把通知唤醒到同勘，不依赖谷歌服务，分发器也可以自己部署。

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
| `internal/admin` | 运维命令（列出、恢复组长、备份、导出、清理附件、删除工作组） |
| `internal/backup` | 定时备份：数据库快照、附件增量镜像、按份数清理 |

再加一种 SQL 数据库只需写一个 `Dialect` 和它的建表语句，并让测试调用 `storetest.Run`；非 SQL 的存储直接实现 `store.Store` 接口。改表结构时，在 `sqlite` 和 `postgres` 两个包的迁移列表末尾各追加一条。

## 本地开发

```bash
go test -race ./...
go run ./cmd/geocrew-server
# PostgreSQL 的契约测试需要一个可用的库（不设置时跳过）：
GEOCREW_TEST_POSTGRES="postgres://postgres:postgres@localhost:5432/postgres?sslmode=disable" go test ./internal/store/...
```

改了接口后，可以用协议仓库的一致性检查从外部跑一遍（CI 每次提交也会跑）：

```bash
go run ./cmd/geocrew-server -data /tmp/geocrew-check &
go run github.com/GeoCrew-Dev/GeoCrew-Protocol/conformance@latest -server http://localhost:8080
```

## 许可

Apache-2.0
