# 同勘 GeoCrew 协同协议 v0（草案）

> 状态：草案，接口可能变动，但会尽量只做向后兼容的增量修改。机器可读描述见 [`openapi.yaml`](openapi.yaml)。所有路径以服务端根地址为前缀，例如 `https://geo.example.org`。

## 1. 基本约定

- 传输：HTTPS + JSON（UTF-8）；实时通道为 WebSocket（`wss://`）。
- 时间：RFC 3339 UTC 字符串，例如 `2026-11-24T15:00:00Z`，可带小数秒。
- 标识：客户端生成 UUID v4 作为要素 id（1–64 个字符），离线也能创建。工作区和成员 id 由服务端生成。
- 几何：GeoJSON（RFC 7946），坐标 `[经度, 纬度, 高程?]`，CGCS2000 / WGS84。
- 兼容性：
  - 客户端和服务端都必须忽略不认识的 JSON 字段和不认识的实时消息类型。
  - 要素的 `properties` 由客户端自由定义，服务端原样保存、原样返回，不做解释。
  - 服务端在 `GET /api/v0/info` 的 `capabilities` 里声明自己支持的能力，客户端按能力开关功能。

### 1.1 错误格式

所有错误响应都是：

```json
{ "error": "bad_invite", "message": "invalid invite code" }
```

`error` 是稳定的机器码，`message` 只供人读。常见状态码：

| 状态码 | 含义 |
| --- | --- |
| 400 | 请求格式错误（`bad_json`、`invalid`、`invalid_feature`、`bad_hash`、`hash_mismatch`） |
| 401 | 令牌缺失、错误或已被吊销（`unauthorized`） |
| 403 | 角色不允许此操作（`forbidden`） |
| 404 | 资源不存在（`not_found`）或邀请码无效（`bad_invite`） |
| 409 | 冲突，例如移除最后一位组长（`last_leader`）、基于旧版本修改设置（`stale`）、修改已归档的工作区（`archived`，见 2.3） |
| 413 | 请求过大（`batch_too_large`、`too_large`） |

### 1.2 能力一览

`GET /api/v0/info` 的 `capabilities` 列出服务端支持的能力。客户端只在服务端声明了对应能力时打开相应功能，没声明时隐藏或提示「服务器不支持」，不要靠请求失败来判断。自己实现服务端时可以只做核心部分，按需逐项加。

| 能力 | 含义 | 见 |
| --- | --- | --- |
| `sync` | 要素增量同步与冲突规则（核心，必须有） | 第 4 节 |
| `files` | 附件上传下载 | 第 5 节 |
| `realtime` | 实时通道（位置、变更通知） | 第 7 节 |
| `roles` | 组长、组员、只读三种角色与成员管理 | 2.1 |
| `history` | 要素修改历史 | 第 3 节 |
| `settings` | 共享设置 | 2.2 |
| `create` | 接受新建工作组（运维可关闭） | 第 3 节 |
| `archive` | 工作区改名与归档 | 2.3 |
| `export` | GeoJSON 导出 | 第 3 节 |
| `package` | 整组工程包导出 | 第 3 节 |
| `samples` / `sections` / `boreholes` / `structures` / `routes` / `workload` | 样品清单、实测剖面分层表、钻孔分层表、产状表、地质路线记录表、工作量统计（CSV） | 第 3 节、第 6 节 |
| `kml` / `gpx` / `gpkg` | KML、GPX、GeoPackage 导出 | 第 3 节 |
| `checks` | 记录核对 | 第 3 节 |
| `comments` | 记录讨论与组内消息 | 第 8 节 |
| `alerts` | 紧急消息 | 8.1 |
| `rollcall` | 点名 | 8.2 |
| `outing` | 外出报备 | 8.3 |
| `push` | 推送通知（运维配置后才有） | 第 9 节 |
| `view` | 只读查看链接 | 第 10 节 |
| `mcp` | 供 AI 工具读取的 MCP 接口 | 第 11 节 |

不认识的能力名直接忽略。

## 2. 工作区、成员与鉴权

1. 组长调用 `POST /api/v0/workspaces` 创建工作区，返回 `workspace_id`、`invite_code` 和组长的 `token`。
2. 组员调用 `POST /api/v0/workspaces/join`，提交 `invite_code` 与昵称，返回该成员的 `token`。邀请码为 8 位，不区分大小写，不含易混淆字符（0/O、1/I）。
3. 之后的请求在头部携带 `Authorization: Bearer <token>`。令牌只在创建 / 加入时返回一次，服务端只保存其哈希。
4. 组长可重置邀请码（旧码立即失效）、修改成员角色、吊销成员。被吊销成员的令牌立即失效，其实时连接会被断开。

二维码内容为：

```
geocrew://join?server=<URL 编码的根地址>&code=<invite_code>
```

### 2.1 角色

| 角色 | 读取要素与附件 | 提交要素、上传附件 | 共享实时位置 | 管理成员与邀请码 |
| --- | --- | --- | --- | --- |
| `leader` 组长 | ✓ | ✓ | ✓ | ✓ |
| `member` 成员 | ✓ | ✓ | ✓ | |
| `readonly` 只读 | ✓ | | ✓ | |

讨论（第 8 节）随"提交要素"一栏：组长和成员可以发言，只读成员只能看。

工作区至少保留一位未被吊销的组长。

### 2.2 共享设置

每个工作区有一份所有成员都能读取、只有组长能修改的设置，是一个 JSON 对象（不超过 64 KiB）。服务端原样保存，不解释内容；工作区信息里的 `settings_version` 每次修改加 1，修改后通过实时通道广播 `settings_changed`。

- `GET …/settings` 返回 `{version, settings}`。
- `PUT …/settings` 提交 `{settings, base_version?}`。带 `base_version` 时，只有当前版本仍等于它才会写入，否则返回 `409 stale`，客户端应重新读取后再决定是否覆盖。

目前客户端约定的键：

| 键 | 内容 |
| --- | --- |
| `form` | 记录表单：属性数组，每项 `{key, label?, type?, options?, unit?, kinds?, required?}`。`type` 为 `TEXT`（默认）、`NUMBER` 或 `CHOICE`；`kinds` 为适用的要素种类，空表示全部；`required: true` 表示这些种类的记录应当填写该属性，客户端据此提示「待补全」，但服务端不校验，提交时也不应拒绝。值写在要素 `properties[key]` 里，文字始终是字符串，数字是 JSON 数字。 |
| `legend` | 地图图例：`{key, entries: [{value, color}]}`。`key` 是用来着色的要素属性（例如 `lithology` 或表单里的 `formation`），空串表示不着色；`color` 为 `#RRGGBB`。要素 `properties[key]` 的文字（去掉首尾空白、不区分大小写）等于某项 `value` 时，用该颜色画出；要素自带 simplestyle 颜色（`fill`、`stroke`、`marker-color`）时以自带的为准。客户端应忽略颜色格式不对或 `value` 为空、重复的项。 |
| `local_grid` | 本地坐标系（北京 54、西安 80 等旧坐标）：`{name, ellipsoid, width, zone, shift?}`。`ellipsoid` 为 `CGCS2000`、`WGS84`、`XIAN80`、`BEIJING54`；`width` 为 3 或 6（度），`zone` 为带号。`shift` 是从 GPS 坐标（CGCS2000）到本地坐标的转换参数，省略表示只换椭球：`{"type": "plane", dx, dy, rotationSeconds, ppm}` 为四参数，作用在 CGCS2000 同一中央经线的高斯平面坐标（北向 X、去掉带号的东向 Y）上，`rotationSeconds` 以角秒计；`{"type": "bursa", dx, dy, dz, rx, ry, rz, ppm}` 为七参数（布尔莎模型，坐标框架旋转约定），平移以米、旋转以角秒计，作用在地心直角坐标上。`ppm` 为尺度变化（百万分之一）。值为 `null` 表示组长已停止使用；没有这个键时，客户端保留各自本机的设置。客户端应忽略带宽、带号或参数不合理的值。 |
| `phrases` | 描述常用词：数组，每项 `{name, items}`，`name` 为组名（如「地层」「颜色」，最长 20 字），`items` 为词（每个最长 40 字，每组最多 100 个，最多 20 组）。客户端在内置词表之外列出这些词，同名的组合并到内置组前面。客户端应去掉空白、重复和超长的词；没有这个键时，各设备保留自己的词。 |
| `overlays` | 组长共享的叠加图（扫描地质图、草图），数组，先画的在前：每项 `{id, name, file, type, width, height, corners, opacity?, placement?, control_points?}`。`file` 是图片的附件哈希（先按第 5 节上传，再写入设置），`type` 为 `image/jpeg` 或 `image/png`，`width`、`height` 为像素尺寸，`corners` 为左上、右上、右下、左下四角的 `[经度, 纬度]`；`opacity` 为 0.1–1（默认 0.6），`placement` 为 `view`（按视野大致铺放）或 `world_file`（按 world 文件定位），`control_points` 为最近一次对齐用的控制点对数。每台设备自己决定是否显示。客户端应忽略哈希、类型或四角不合理的项；没有这个键时，客户端保留各自本机的叠加图。服务端清理无引用附件时，把这里的 `file` 算作有引用。 |
| `reference_layers` | 组长共享的参考图层（前人地质图的图斑和断层、矿权范围等只用来对照的矢量资料），数组，先画的在下：每项 `{id, name, file, color_by?, count?, opacity?}`。`id` 为 1–64 位字母、数字或连字符；`file` 是一个 GeoJSON FeatureCollection（WGS84 经纬度）的附件哈希，按第 5 节以 `application/geo+json` 上传后再写入设置；`color_by` 为面按哪个属性着色（值和 `legend` 里一致时用图例颜色，否则各端用同一套固定配色），`count` 为图形个数，`opacity` 为面的不透明度 0.1–1（默认 0.45）。参考图层不是记录，不参与同步、核对和导出；每台设备自己决定是否显示，组员不能删改。客户端应忽略 `id` 或哈希不合法的项；没有这个键时，客户端保留各自本机的参考图层。服务端清理无引用附件时，把这里的 `file` 算作有引用。 |
| `basemaps` | 组长共享的底图，数组：每项 `{id, name, url, subdomains?, max_zoom?, tms?, attribution?}`。`id` 为 1–64 位字母、数字或 `_.-`，`url` 是 http(s) 瓦片地址模板，用 `{z}` `{x}` `{y}`（或 `{-y}` 表示 TMS 行号）、`{s}`（从 `subdomains` 里轮换），WMS 地址用 `{bbox-epsg-3857}` 代替行列号，由客户端按每张瓦片的 Web 墨卡托范围（米，`最小x,最小y,最大x,最大y`）填入；`max_zoom` 为 1–24（默认 18），`tms` 为真时 `{y}` 按 TMS 行号。**不得包含 `{key}` 或任何个人密钥**：需要 Key 的图源不共享。客户端应忽略不合规的项；没有这个键时，各设备只有自己的图源。 |
| `notice` | 组内公告：`{text, updated_at, by?}`。`text` 为公告正文（最长 2000 字，可换行），`updated_at` 为发布时间（RFC 3339），`by` 为发布人的昵称，只用于显示。组长发一条新的就整体替换旧的；撤下公告时删掉这个键。新公告会给其他成员发 `notice` 推送（第 9 节）。客户端在工作组页、消息页顶部和网页查看页置顶显示；正文为空时当作没有公告。 |
| `record_rules` | 本工程的记录规则：`{required?, min_photos?, ask_all?, ask_name?, point_prefix?}`。`required` 为地质点必填项数组，取值 `lithology`（岩性）、`attitude`（产状）、`note`（描述）、`sample`（样品）；`min_photos` 为每个地质点至少几张照片（0–20，默认 0 不要求）；`point_prefix` 为全组统一的地质点编号前缀（如 `D`、`2025D`，最长 20 字），设了就代替各手机自己的前缀，按已有编号续排；`ask_all`（默认真）表示语音或助手定点时非必填项也逐项问一遍，`ask_name`（默认真）表示先确认点号。缺必填项或照片不够的地质点列入「待核对」，代码 `incomplete`，但服务端不校验，提交时也不应拒绝。客户端应忽略不认识的必填项；没有这个键时没有额外要求。 |
| `ai_limits` | 组长限制本工作组里 AI 助手能做的事：`{deny: [...]}`，数组取值为操作类别 `edit`（新建和修改）、`bulk`（批量修改）、`delete`（删除）、`send`（对外发送：组内消息、全组推送、上传）、`settings`（改设置、密钥和成员）。列出的类别里，AI 提出的操作一律不执行，组员在自己手机上也打不开；查看和查询（`read`）不能禁止。只约束 AI 提出的操作，用户自己点按和说出的常用口令不受影响。客户端应忽略不认识的类别；没有这个键时由各人自己设置（见 [actions.md](actions.md)）。 |

不认识的键必须原样保留：客户端修改设置时先读取、只改自己负责的键、再整体提交。

### 2.3 改名与归档

组长用 `PATCH /api/v0/workspaces/{id}` 提交 `{name?, archived?}`，两个键至少带一个，返回与 `GET` 相同的工作区信息。

- `name`：新名称，去掉首尾空白后 1–64 字。
- `archived: true`：归档。野外工作结束、数据还要留着看时使用。工作区信息里出现 `archived_at`（RFC 3339，归档的时间；已归档时再次归档保留原来的时间）。
- `archived: false`：重新打开，`archived_at` 消失。

归档期间：

- 提交要素、修改共享设置、发言和删除发言、上传附件都返回 `409 archived`；客户端应停止重试这些请求，把工作区显示为只读，等重新打开后再提交本地的修改。
- 邀请码不能再加入（`409 archived`）。
- 读取、导出、实时通道、查看链接、推送登记、成员管理（改角色、吊销、重置邀请码）照常可用。
- 改名或归档后服务端在实时通道发 `workspace_changed`（第 7 节）。

能力名 `archive`。

## 3. 接口一览

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| GET | `/healthz` | 无 | 健康检查，返回 `ok` |
| GET | `/api/v0/info` | 无 | 名称、协议版本、能力、上传上限 |
| POST | `/api/v0/workspaces` | 无 | 创建工作区 `{name, nickname}` |
| POST | `/api/v0/workspaces/join` | 无 | 加入 `{invite_code, nickname}` |
| GET | `/api/v0/workspaces/{id}` | 任意 | 工作区信息、我的身份、在线成员；组长额外看到邀请码 |
| PATCH | `/api/v0/workspaces/{id}` | 组长 | 改名、归档或重新打开 `{name?, archived?}`（见 2.3） |
| GET | `/api/v0/workspaces/{id}/members` | 任意 | 成员列表（含已吊销，带 `revoked: true`）；`last_seen` 为该成员最近一次带令牌请求的时间（精确到分钟，从未请求过则没有这个键） |
| GET | `/api/v0/workspaces/{id}/settings` | 任意 | 共享设置（见 2.2） |
| PUT | `/api/v0/workspaces/{id}/settings` | 组长 | 修改共享设置 |
| PATCH | `/api/v0/workspaces/{id}/members/{member_id}` | 组长 | 修改角色 `{role}` |
| DELETE | `/api/v0/workspaces/{id}/members/{member_id}` | 组长 | 吊销成员 |
| POST | `/api/v0/workspaces/{id}/invite` | 组长 | 重置邀请码 |
| GET | `/api/v0/workspaces/{id}/features?since=&limit=` | 任意 | 增量拉取 |
| POST | `/api/v0/workspaces/{id}/features` | 组长、成员 | 批量提交 |
| GET | `/api/v0/workspaces/{id}/features/{fid}/history` | 任意 | 被覆盖的历史版本 |
| GET | `/api/v0/workspaces/{id}/export.geojson` | 任意 | 当前全部未删除记录，标准 GeoJSON FeatureCollection（不含 `sync`），供桌面 GIS 使用 |
| GET | `/api/v0/workspaces/{id}/export.geocrew.zip` | 任意 | 工程包（zip）：`project.json`（`format: "geocrew-project"`、名称、日期，`config.fields` 为共享设置里的 `form`）、`features.geojson`、`photos/<sha256>.jpg`（记录 `photos` 引用且服务器上有的附件）、`audio/<sha256>.m4a`（记录 `audio` 引用的录音，第 6.3 节）。与客户端导出的工程包同一格式，可直接导入为新工程；不含成员、凭据和历史。能力名 `package` |
| GET | `/api/v0/workspaces/{id}/export.samples.csv?tz_offset=` | 任意 | 样品清单（第 6.4 节），UTF-8 CSV（带 BOM），每件样品一行：样品编号、样品类型、所在记录、经度、纬度、高程、岩性、采样时间、样品备注、记录ID，按编号自然排序；线面上的样品取第一个顶点。`tz_offset` 为采样时间所用时区（东为正，分钟，默认 0）。能力名 `samples` |
| GET | `/api/v0/workspaces/{id}/export.sections.csv` | 任意 | 实测剖面分层表（第 6.5 节），UTF-8 CSV（带 BOM），每个剖面的每一层一行：剖面、层号、起点斜距、终点斜距、岩性、倾向、倾角、产状来源（实测 / 借用）、真厚度、备注、记录ID，剖面按名称自然排序。能力名 `sections` |
| GET | `/api/v0/workspaces/{id}/export.boreholes.csv` | 任意 | 钻孔分层表（第 6.11 节），UTF-8 CSV（带 BOM），每个钻孔的每一层一行：孔号、孔口经度、孔口纬度、孔口高程、终孔深度、水位埋深、层号、层顶深度、层底深度、厚度、岩性、描述、记录ID，钻孔按孔号自然排序。能力名 `boreholes` |
| GET | `/api/v0/workspaces/{id}/export.structures.csv` | 任意 | 产状表，UTF-8 CSV（带 BOM），每条面状产状（`dip_direction`、`dip`）和线状构造（第 6.12 节）各一行：记录、种类（面状或线状）、类型（线和面的线型名称，或线状构造的 `lineation_type`，省略时为「线理」）、倾向或倾伏向、倾角或倾伏角、走向（只有面状）、经度、纬度、高程、岩性、记录ID；线和面取第一个顶点的位置，记录按名称自然排序。能力名 `structures` |
| GET | `/api/v0/workspaces/{id}/export.workload.csv?tz_offset=` | 任意 | 工作量统计，UTF-8 CSV（带 BOM），每个组员每天一行：日期、组员、新建记录、其中地质点、修改次数、样品、照片、轨迹(km)，每人最后一行日期为「合计」。记录的创建人和创建日取它最早的版本，之后每个版本按作者计一次修改；样品按 `taken_at`、照片按拍摄时间计到记录创建人名下；轨迹按开始时间和长度计。`tz_offset` 为分天所用时区（东为正，分钟，默认 0）；已离开的成员显示为「已离开的组员」。只供参考，不作考核依据。能力名 `workload` |
| GET | `/api/v0/workspaces/{id}/export.routes.csv` | 任意 | 地质路线记录表（第 6.6 节），UTF-8 CSV（带 BOM）：每条路线一个目的行、按 `stops` 顺序每个点一行（路线、日期、参加人员、序号、点号、类型、经度、纬度、海拔、岩性、产状、点上描述、至下一点描述、记录ID）和一个小结行，找不到的点跳过，路线按日期和路线号自然排序。能力名 `routes` |
| GET | `/api/v0/workspaces/{id}/checks` | 任意 | 记录核对：`{"issues": [{"feature", "title", "code", "message"}]}`，列出值得再看一眼的记录，按记录名自然排序。`code` 固定为 `duplicate_number`（地质点、照片点、剖面、路线等带数字的编号重名，不分大小写）、`duplicate_sample`（样品编号在工程内重复）、`no_position`（点、线、面、轨迹、照片没有几何）、`poor_accuracy`（`accuracy_m` 超过 `?accuracy_m=`，默认 30）、`far_away`（离全部记录位置中位点超过 `?far_km=`，默认 50 千米，且超过典型距离 10 倍；至少 5 条有位置的记录才判断）、`missing_stop`（路线 `stops` 指向不存在或已删除的记录）、`redo`（组长退回的记录，见 §6.9）；`message` 只供显示，客户端可按 `code` 自行措辞。能力名 `checks` |
| GET | `/api/v0/workspaces/{id}/export.kml` | 任意 | KML 2.2：每条有几何的记录一个地标，`title`、`note` 作名称和说明，其余简单属性写成 ExtendedData；颜色取记录自带的 simplestyle，否则取共享设置里的 `legend`，每种颜色一个共享 Style；轨迹带 TimeSpan（`started_at`、`ended_at`），`times_s` 不列进 ExtendedData。能力名 `kml` |
| GET | `/api/v0/workspaces/{id}/export.gpx` | 任意 | GPX 1.1：点为航点（`wpt`），`kind` 为 `track` 的线为航迹（`trk`，带 `times_s` 时每个点有时间，第 6.13 节），其他线为航线（`rte`，多段线每段一条），面不导出；`title`、`note` 作名称和说明。能力名 `gpx` |
| GET | `/api/v0/workspaces/{id}/export.gpkg` | 任意 | GeoPackage 1.3（`application/geopackage+sqlite3`）：点、线、面各一张表（`points`、`lines`、`polygons`，EPSG:4326），列有 `record_id`、`kind`、`title`、`note`、`lithology`、`strike`、`dip_direction`、`dip`、`line_style`、`meet_at`、`color`、`updated_at`、`updated_by`（昵称）和 `properties`（全部属性的 JSON）；没有记录的表不建。能力名 `gpkg` |
| GET | `/api/v0/workspaces/{id}/comments?since=&limit=` | 任意 | 增量拉取讨论（见第 8 节） |
| POST | `/api/v0/workspaces/{id}/comments` | 组长、成员 | 发言 |
| DELETE | `/api/v0/workspaces/{id}/comments/{comment_id}` | 作者、组长 | 删除发言 |
| PUT | `/api/v0/workspaces/{id}/files/{sha256}` | 组长、成员 | 上传附件 |
| GET / HEAD | `/api/v0/workspaces/{id}/files/{sha256}` | 任意 | 下载附件 / 查询是否存在 |
| GET | `/api/v0/workspaces/{id}/ws` | 任意 | 升级为 WebSocket 实时通道 |
| GET / PUT / DELETE | `/api/v0/workspaces/{id}/push` | 任意 | 查看、登记、取消我的推送地址（见第 9 节） |
| POST / DELETE | `/api/v0/workspaces/{id}/view-links` | 任意（不含查看链接本身） | 生成一个只读查看链接 / 撤销我生成的全部查看链接（见第 10 节） |
| POST | `/api/v0/workspaces/{id}/mcp` | 任意（含查看链接） | 供 AI 工具读取工作组的 MCP 接口（JSON-RPC 2.0，见第 11 节）。能力名 `mcp` |
| GET | `/view/` | 无 | 浏览器查看页（令牌在网址 `#` 后面，见第 10 节） |

`info` 示例：

```json
{
  "name": "GeoCrew Server",
  "protocol": "0",
  "version": "1a2b3c",
  "capabilities": ["sync", "files", "realtime", "roles", "history", "settings", "export", "comments", "package", "push", "alerts", "view", "samples", "sections", "kml", "routes", "checks", "rollcall", "gpkg", "outing", "boreholes", "structures", "workload", "gpx", "create"],
  "max_upload_bytes": 33554432,
  "max_push_batch": 500
}
```

`create` 表示该服务器接受新建工作组；运维人员可以在组建好后关闭，此时新建请求返回 `403 create_disabled`，客户端应只显示"加入"。

新建与加入工作组这两个无需凭据的接口按客户端地址限流，超出时返回 `429 rate_limited`，并带 `Retry-After`（秒）。

## 4. 要素同步

### 4.1 要素格式

要素即一个 GeoJSON Feature，附加 `sync` 成员：

```json
{
  "type": "Feature",
  "id": "8d3c6f0e-0c1a-4d7e-9a43-3f1d9b0f4e21",
  "geometry": { "type": "Point", "coordinates": [116.39, 39.91, 52.3] },
  "properties": {
    "kind": "geo_point",
    "title": "D001",
    "strike": 35, "dip_direction": 125, "dip": 42,
    "photos": ["<sha256>"],
    "note": "灰岩，层理清晰"
  },
  "sync": {
    "version": 3,
    "updated_at": "2026-11-24T15:00:00Z",
    "updated_by": "<member_id>",
    "deleted": false,
    "seq": 17
  }
}
```

| 字段 | 谁写 | 说明 |
| --- | --- | --- |
| `sync.version` | 客户端 | 从 1 开始，每次本地修改加 1 |
| `sync.updated_at` | 客户端 | 本地修改时间（离线修改保留真实时间） |
| `sync.updated_by` | 服务端 | 服务端用提交者的成员 id 覆盖客户端的值 |
| `sync.deleted` | 客户端 | 软删除标记；删除也是一次修改，`version` 加 1，内容保留，可再改回 `false` 恢复（§4.4） |
| `sync.seq` | 服务端 | 服务端接受该修改时分配的序号，提交时忽略 |

`geometry` 可以为 `null`（例如纯文字记录）。常用的 `properties.kind` 约定见第 6 节。

### 4.2 提交

```
POST /api/v0/workspaces/{id}/features
{ "features": [ <Feature>, ... ] }
```

单次最多 `max_push_batch` 个要素，同一请求内 id 不可重复。服务端在一个事务里逐个处理，响应与请求一一对应：

```json
{
  "results": [
    { "id": "8d3c…", "status": "applied", "seq": 18 },
    { "id": "77ab…", "status": "duplicate", "seq": 12 },
    { "id": "5e10…", "status": "stale", "seq": 15, "current": { <服务端当前版本的 Feature> } }
  ],
  "seq": 18
}
```

| status | 含义 | 客户端处理 |
| --- | --- | --- |
| `applied` | 已保存并分配新 `seq` | 标记为已同步 |
| `duplicate` | 服务端已有完全相同的版本（重试） | 标记为已同步 |
| `stale` | 服务端已有更新的版本 | 用 `current` 覆盖本地；如需保留本地修改，以 `current.sync.version + 1` 重新提交 |

### 4.3 冲突规则（v0）

同一要素的两个版本按以下顺序比较，较大者胜出：

1. `version`
2. `updated_at`
3. `updated_by`（字符串比较，保证结果确定）

三者都相同视为同一版本（`duplicate`）。被覆盖的旧版本保存在服务端历史中，可通过 `GET …/features/{fid}/history` 查看并人工恢复（恢复即以更高版本重新提交旧内容）。

### 4.4 拉取

```
GET /api/v0/workspaces/{id}/features?since=17&limit=500
```

返回 `seq > since` 的要素当前版本，按 `seq` 升序：

```json
{ "features": [ … ], "seq": 25, "more": false }
```

- `seq` 是下次请求应传的 `since`（没有新数据时等于请求的 `since`）。
- `more` 为 `true` 时应立即继续拉取。
- 一个要素多次修改只返回最新版本；已删除要素也会返回（`deleted: true`），客户端据此删除本地副本。
- 删除不清空内容：已删除要素照常带着删除前的 `geometry` 和 `properties`，服务端原样保存和返回，客户端可以据此做「最近删除」并恢复。恢复就是再提交一次 `version` 更高、`deleted: false` 的修改，和普通修改一样处理。要真正抹掉内容（如误录了隐私信息），先把内容改掉再删除。

### 4.5 推荐的客户端同步流程

1. 本地每次修改：`version + 1`、更新 `updated_at`，标记为"待上传"。
2. 先上传要素引用的附件（见第 5 节），再分批提交待上传要素。
3. 从上次保存的 `seq` 开始拉取直到 `more` 为 `false`，合并到本地：远端版本按第 4.3 节规则更新时覆盖本地，本地仍有待上传修改时保留本地。
4. 保存最新 `seq`。
5. 收到实时通道的 `feature_changed` 时重复第 3–4 步。

## 5. 附件

照片、草图、录音等按内容的 SHA-256（小写十六进制）存储，同一工作区内自动去重。

- `PUT …/files/{sha256}`：请求体为文件原始字节，`Content-Type` 为文件类型。成功返回 `201`；已存在返回 `204`（不读取请求体，客户端可放心重试）；哈希不符返回 `400 hash_mismatch`；超过上限返回 `413`。
- `HEAD …/files/{sha256}`：存在返回 `200`，否则 `404`，用于上传前检查。
- `GET …/files/{sha256}`：返回文件，支持 `Range` 断点续传，带 `ETag` 和长期缓存头。

要素通过 `properties.photos`（或其他字段）引用附件的哈希。

## 6. 要素种类约定（properties.kind）

`kind` 由客户端定义，服务端不校验。官方客户端目前使用：

| kind | 几何 | 主要属性 |
| --- | --- | --- |
| `point` | Point | `title`、`note`、`photos`、`audio`、`meet_at`（第 6.8 节） |
| `geo_point` | Point | 地质点：`title`、`lithology`、`strike`、`dip_direction`、`dip`、`trend`、`plunge`、`lineation_type`（§6.12）、`note`、`photos`、`audio` |
| `line` | LineString | `title`、`note`、`length_m`、`line_style`（第 6.7 节） |
| `polygon` | Polygon | `title`、`note`、`area_m2` |
| `track` | LineString | 轨迹：`title`、`started_at`、`ended_at`、`length_m`、`times_s`（第 6.13 节）；坐标可带高程 |
| `photo` | Point | 独立照片点：`photos`、`azimuth`（拍摄方位角） |
| `section` | LineString（可省略） | 实测剖面：`title`、`section`（第 6.5 节） |
| `route` | LineString / Point（可省略） | 地质路线：`title`（路线号）、`route`（第 6.6 节） |
| `borehole` | Point（可省略） | 钻孔、浅井编录：`title`（孔号）、`borehole`（第 6.11 节）；点为孔口 |

角度单位为度：走向、倾向为 0–360（正北为 0，顺时针），倾角 0–90。自定义属性请加命名空间前缀，例如 `myteam:sample_no`，避免与将来的官方字段冲突。

### 6.1 任务指派

任何一条记录都可以指派给一位组员，例如组长先画好要去的采样点或调查范围，再分给组员。指派只是三个普通属性，跟其他修改一样同步、进入修改历史、按第 4.3 节处理冲突，服务端不需要额外接口：

| 属性 | 说明 |
| --- | --- |
| `assignee` | 被指派组员的 `member_id`；没有这个键（或为空）表示未指派 |
| `assignee_name` | 指派时该组员的昵称，便于离线和导出后的文件里也能看出是谁 |
| `task_status` | `todo`（待办）、`doing`（进行中）、`done`（已完成）；不认识的值按 `todo` 显示 |

取消指派时三个键一起删除。改派给另一位组员时，`task_status` 回到 `todo`。只读成员不能修改记录，也就不能指派或改状态。

### 6.2 照片信息

一条记录可以有多张照片，每张的拍摄方向、位置、时间放在 `photo_info` 对象里，键是附件哈希（与 `photos` 中的一致）：

```json
"photos": ["<sha256-a>", "<sha256-b>"],
"photo_info": {
  "<sha256-a>": { "azimuth": 235.0, "lon": 116.391, "lat": 39.907, "alt": 52.3, "taken_at": "2026-11-24T10:40:00Z" }
}
```

| 字段 | 说明 |
| --- | --- |
| `azimuth` | 拍摄方向，真北起算 0–360° |
| `lon`、`lat`、`alt` | 拍摄者所在位置（不一定是记录本身的位置），`alt` 可省略 |
| `taken_at` | 拍摄时间，RFC 3339 |
| `sketch` | `true` 表示这是在应用里手绘的野外素描（仍是 JPEG，与照片同样上传和打包），不是相机拍的 |
| `drawn_on` | 素描画在哪张照片上：那张照片的附件哈希；空白纸上画的省略 |

各字段都可省略；照片从 `photos` 移除时，对应条目也应删除。服务端不解析这个对象。旧版独立照片点的 `azimuth` 属性仍然有效。

### 6.3 录音

野外口述记录放在 `audio` 数组里，元素是附件哈希，按录制先后排列；每段的时长、录制时间和位置放在 `audio_info` 里，写法与 `photo_info` 相同：

```json
"audio": ["<sha256-c>"],
"audio_info": {
  "<sha256-c>": { "duration_s": 42.5, "recorded_at": "2026-11-24T10:41:00Z", "lon": 116.391, "lat": 39.907 }
}
```

| 字段 | 说明 |
| --- | --- |
| `duration_s` | 时长，秒 |
| `recorded_at` | 开始录制的时间，RFC 3339 |
| `lon`、`lat`、`alt` | 录制时所在位置，可省略 |
| `transcript` | 这段录音说了什么：语音识别转写或手工整理的文字，可省略；转写结果不自动写进 `note`，由用户决定是否加入描述 |

官方客户端录成 AAC 音频（MP4 容器，上传时 `Content-Type: audio/mp4`，导出文件扩展名 `.m4a`）；读取方应按文件内容而不是扩展名判断格式。服务端把 `audio` 引用的附件与照片同样对待：工程包里放在 `audio/<sha256>.m4a`，清理无引用附件时不会删除。

### 6.4 样品

在记录上采的样品放在 `samples` 数组里，每件一个对象，按登记先后排列。一个点可以有多件样品（例如一件薄片、一件化学样），所以样品不单独成为记录：

```json
"samples": [
  { "no": "D012-1", "type": "薄片", "taken_at": "2026-11-24T10:45:00Z" },
  { "no": "D012-2", "type": "岩石化学", "note": "新鲜面，约 2 kg", "results": { "Cu(ppm)": 35.2, "Au(ppb)": "<0.5" } }
]
```

| 字段 | 说明 |
| --- | --- |
| `no` | 样品编号，必填；客户端应保证在同一工作组（工程）内不重复，比较时不分大小写 |
| `type` | 样品类型，自由文本；常用写法见下 |
| `note` | 备注，可省略 |
| `taken_at` | 登记时间，RFC 3339，可省略 |
| `results` | 分析结果，可省略。键是分析项（建议带单位，如 `Cu(ppm)`），值是数字，或低于检出限时写成字符串 `"<检出限"`（如 `"<0.5"`）；其他值应忽略 |

客户端导入分析结果时，可以把同一记录各样品的结果取平均写成记录本身的数值属性（键与 `results` 中相同，低于检出限按检出限的一半计），供图例、统计和插值使用；这些属性只是派生值，以 `results` 为准。

常用类型：薄片、光片、岩石化学、微量元素、同位素年龄、单矿物、重砂、古生物、土壤、水系沉积物。没有 `no` 的条目应忽略；不认识的字段应原样保留。整个数组随记录一起按第 4.3 节的规则合并，所以两人同时给同一个点加样品时，后写入的一方会覆盖前一方，客户端可从修改历史中找回。服务端不解析这个数组。

### 6.5 实测剖面

`kind` 为 `section` 的记录是一条实测剖面：沿剖面拉皮尺、用罗盘测出的导线，以及沿途划分的地层。数据放在 `section` 对象里，几何是从起点按导线推算出的折线，便于在地图上显示；没有起点时几何可以为空。

```json
"section": {
  "legs": [
    { "azimuth": 135, "slope": 8, "distance": 42.5 },
    { "azimuth": 150, "slope": -3, "distance": 30 }
  ],
  "layers": [
    { "no": "1", "top": 18.2, "lithology": "灰岩", "dip_direction": 320, "dip": 35 },
    { "no": "2", "top": 55, "lithology": "页岩", "note": "含笔石" },
    { "no": "3", "top": 72.5, "lithology": "砂岩", "dip_direction": 315, "dip": 38 }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `legs` | 导线，从起点依次排列。`azimuth` 前进方位 0–360；`slope` 坡角 −90–90，沿前进方向上坡为正；`distance` 斜距（米），大于 0 |
| `layers` | 分层，从起点依次排列。`no` 层号（文本）；`top` 本层终点在整条导线上的累计斜距（米），本层从上一层的 `top`（第一层从 0）延伸到这里 |
| `lithology`、`note` | 岩性和备注，可省略 |
| `dip_direction`、`dip` | 本层实测产状，可省略；省略时客户端可借用最近一层的实测产状来计算，并应标明是借用的 |

各层真厚度不存储，由读取方计算：把本层范围内的每段导线化为向量（东、北、上），与层面单位法线做点积再求和取绝对值，导线的坡度、与走向斜交和转折都自然计入。缺少有效数值的导线或分层条目应忽略；不认识的字段应原样保留。整个对象随记录一起按第 4.3 节的规则合并。服务端不解析这个对象。

### 6.6 地质路线

`kind` 为 `route` 的记录是一天的地质路线：按走过的顺序排列的观测点，加上点与点之间的路线描述、路线目的和小结。点本身是普通记录（通常是 `geo_point`），路线只按 id 引用它们，所以修改点不需要改路线，点被删除后读取方直接跳过。几何是按顺序连接各点位置的折线（只有一个有位置的点时为 Point，都没有位置时为空），只用于地图显示，以 `stops` 为准。

```json
"route": {
  "date": "2026-11-24",
  "members": "王明、李华",
  "purpose": "追索 F3 断层北段",
  "stops": [
    { "feature": "0d2c…", "onward": "沿沟向东 200 米，见灰岩夹页岩" },
    { "feature": "7a91…" }
  ],
  "summary": "断层走向北东，破碎带宽约 5 米"
}
```

| 字段 | 说明 |
| --- | --- |
| `date` | 路线日期，`YYYY-MM-DD`，可省略 |
| `members`、`purpose`、`summary` | 参加人员、路线目的与任务、路线小结，文本，可省略 |
| `stops` | 按走过的顺序排列。`feature` 是点记录的 id；`onward` 是从这个点到下一个点之间的描述，可省略 |

同一个 id 在 `stops` 中重复时只取第一次；找不到或已删除的记录应跳过，但不要从对象里删掉（可能还没同步到）。不认识的字段应原样保留，整个对象随记录一起按第 4.3 节的规则合并。服务端不解析这个对象。

### 6.7 线型

线和面可以带 `line_style`，说明它在地质图上代表什么、该怎么画：

| 值 | 含义 | 画法 |
| --- | --- | --- |
| `contact` | 实测地质界线 | 黑色实线 |
| `inferred_contact` | 推测地质界线 | 黑色虚线 |
| `fault` | 实测断层 | 红色粗实线 |
| `inferred_fault` | 推测断层 | 红色粗虚线 |

记录自带 simplestyle 颜色（`stroke`）时颜色以自带的为准，虚实和粗细仍按线型。不认识的值当作没有线型画，但要原样保留。DXF 等导出时，带线型的线放在以线型命名的图层上，推测的用虚线线型。线和面也可以带 `strike`、`dip_direction`、`dip`（断层面或界面的产状），显示时把产状符号画在线长的中点（面取外环）。

### 6.8 集合点

任何点记录都可以带 `meet_at`（ISO 8601 时间，例如 `"2026-11-24T09:30:00Z"`），表示全组在这个时间到这个点集合：出发、吃饭、等车接。客户端应在地图上把它和普通点区分开，并提示离现在最近的一次集合：从集合时间前 24 小时开始显示，过了时间再保留 1 小时，给迟到的人看；同时有几处时取时间上离现在最近的。显示时给出集合时间、还剩多久，以及本机到集合点的距离和方位。取消集合点就是删掉这个属性；`meet_at` 不是合法时间时当作没有。服务端不解析这个属性。

### 6.9 审核

组长可以给任何记录加 `review`，表示看过了：

```json
"review": { "state": "approved", "by": "<member_id>", "by_name": "王组长", "at": "2026-11-24T18:30:00Z", "note": "" }
```

| 字段 | 说明 |
| --- | --- |
| `state` | `approved` 通过；`redo` 退回，要记录人补充或改正 |
| `by` | 审核的组长的成员 id |
| `by_name` | 当时的昵称，离线也能显示是谁审的（可选） |
| `at` | 审核时间 |
| `note` | 给记录人的话，退回时写要改什么 |

只有组长能设、改或删 `review`。服务端对组员提交的修改，一律保留库里已有的 `review`（没有就去掉提交里带的），组员的其他修改照常生效；所以客户端不必另外防伪，组员改了审核过的记录，审核也还在。审核之后记录又被改过（`sync.updated_at` 晚于 `review.at`）时，客户端应提示「审核后有改动」，由组长决定要不要再看一遍。退回的记录出现在「待核对」里，代码 `redo`。不认识的 `state` 当作没有审核显示，但要原样保留。

### 6.10 高程来源

点坐标的第三个数是高程（米）。默认它来自记录时的定位；不是定位来的高程，用 `alt_source` 注明：

| 值 | 含义 |
| --- | --- |
| `dem` | 取自公开数字高程模型（官方客户端用 Terrarium 地形瓦片，约 30 m 分辨率），代表地面高程，不含定位误差也没有定位的精度 |

没有 `alt_source` 就当作定位高程。客户端显示高程时应注明来源；以后可能增加其他值（例如水准或地形图读数），不认识的值照原样保留，显示为「其他来源」。

### 6.11 钻孔编录

`kind` 为 `borehole` 的记录是一个钻孔（也可以是浅井、竖井或探槽壁）的编录，点为孔口位置，没有位置时几何可以为空。数据放在 `borehole` 对象里：

```json
"borehole": {
  "depth": 25,
  "water": 6.4,
  "layers": [
    { "no": "1", "bottom": 1.2, "lithology": "素填土" },
    { "no": "2", "bottom": 8.5, "lithology": "粉质黏土", "note": "可塑，含铁锰结核" },
    { "no": "3", "bottom": 25, "lithology": "砂岩", "note": "强风化至中风化" }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `layers` | 分层，自上而下排列。`no` 层号（文本）；`bottom` 层底深度（米），从孔口沿孔量，本层从上一层的 `bottom`（第一层从 0）延伸到这里；`lithology`、`note` 岩性和描述，可省略 |
| `depth` | 终孔深度（米），可省略；省略时取最深一层的 `bottom` |
| `water` | 地下水位埋深（米），可省略；省略表示未见地下水 |

深度单位为米，范围 0–5000。服务端能力里有 `boreholes` 时，可以按上表的钻孔分层表下载全组的钻孔。v0 把孔当作铅直的，斜孔的方位和倾角留待以后用新字段表示。`bottom` 不比上一层深的分层应忽略并提示，缺少有效数值的条目应忽略，不认识的字段应原样保留。整个对象随记录一起按第 4.3 节的规则合并。服务端不解析这个对象。

### 6.12 线状构造

线理、擦痕、褶皱枢纽、交面线等线状构造用倾伏向和倾伏角表示，放在记录的属性里，可以和面状产状（`strike`、`dip_direction`、`dip`）同时出现在一条记录上：

```json
"dip_direction": 125, "dip": 42,
"trend": 160, "plunge": 30, "lineation_type": "擦痕"
```

| 字段 | 说明 |
| --- | --- |
| `trend` | 倾伏向，度，0–360，取线向下倾的一端的方位（真北） |
| `plunge` | 倾伏角，度，0–90；水平线取 0，这时 `trend` 两个方向都对 |
| `lineation_type` | 类型，文本，可省略；常用值有「线理」「擦痕」「褶皱枢纽」「交面线」「拉伸线理」，省略时按「线理」显示 |

两个数要同时出现才算有效，超出范围的应忽略并原样保留。客户端存储前可以把倾伏角为负的读数换成另一端（`trend` 加 180°、`plunge` 取正）。同一条记录有面状产状时，客户端可以算出线在面上的侧伏角，并在线明显不在面上时提醒。服务端不检查这些字段；能力里有 `structures` 时，可以按产状表下载全组的面状和线状产状。

### 6.13 轨迹逐点时间

轨迹（`kind` 为 `track`、几何为 LineString）可以带每个坐标点的定位时间，放在属性 `times_s` 里：一个整数数组，长度与坐标个数相同、顺序一致，每个数是该点距 `started_at` 的秒数：

```json
"started_at": "2026-11-24T02:00:00Z",
"times_s": [3, 13, 23, 33]
```

- 数组不递减。客户端写入时遇到手机时钟往回调，应把后面的数取成前一个数，不要写出变小的值。
- 读取时只有在 `started_at` 有效、数组长度与坐标个数相同、每一项都是整数且不递减时才使用；否则当作没有逐点时间，原样保留这个字段。
- 坐标被改动（如抽稀、改形状）时，客户端应同时更新或去掉 `times_s`。MultiLineString 不使用这个字段。
- 精度为秒，足够做 GPX 导出和按拍摄时间给照片落点；更细的定位信息（精度、速度）仍只留在记录它的手机上。

服务端不检查这个字段。能力里有 `gpx` 时，可以按第 3 节的 GPX 导出下载全组的轨迹（带逐点时间）和点；KML 导出把轨迹的 `started_at`、`ended_at` 写成时间范围，不把 `times_s` 列进属性。

## 7. 实时通道

连接：`GET /api/v0/workspaces/{id}/ws`，携带 `Authorization: Bearer <token>`；无法设置请求头的客户端（如浏览器）可改用查询参数 `?access_token=<token>`。

消息均为 JSON 文本帧，带 `type` 字段。服务端每 30 秒发送 WebSocket ping 帧；应用层 `ping` / `pong` 供不便处理协议帧的客户端使用。

| type | 方向 | 内容 |
| --- | --- | --- |
| `hello` | 服务端→客户端 | 连接后第一条：`{member_id, online: [member_id…], positions: [position…]}`，`positions` 为最近 15 分钟内的最后位置 |
| `position` | 双向 | 客户端发送 `{lon, lat, alt?, accuracy?, heading?, speed?, at?}`；服务端补上 `member_id`、`nickname` 后转发给其他成员 |
| `presence` | 服务端→客户端 | `{member_id, online}`，成员上线 / 下线 |
| `feature_changed` | 服务端→客户端 | `{seq}`，有新变更，客户端随后增量拉取 |
| `members_changed` | 服务端→客户端 | 成员或角色有变化，客户端重新获取成员列表 |
| `settings_changed` | 服务端→客户端 | `{version}`，共享设置有变化，客户端重新读取 |
| `comments_changed` | 服务端→客户端 | `{seq}`，有新的讨论或删除，客户端随后增量拉取（第 8 节） |
| `workspace_changed` | 服务端→客户端 | `{name, archived_at?}`，工作区改名、归档或重新打开（2.3） |
| `ping` / `pong` | 双向 | 保活 |
| `error` | 服务端→客户端 | `{error}`，例如 `bad_json` |

位置规则：

- 服务端对每位成员的位置更新限流为每秒最多一次，多余的直接丢弃。
- 坐标超出范围的位置会被丢弃。
- 位置只在内存中短期保留，不写入要素历史；服务端重启后清空。
- 客户端可以把轨迹作为 `track` 要素提交，用于长期保存。

## 8. 讨论与消息

服务器能力里有 `comments` 时可用。每条发言要么挂在一条记录上（带 `feature_id`），要么是发给全组的消息（不带）；全组消息可以带 `at: [经度, 纬度]`，客户端据此"定位到地图"。

```json
{
  "id": "c-01HZX3",
  "feature_id": "f-8a1c",
  "body": "这里的接触关系明天再看一下",
  "author_id": "m-3f2a",
  "nickname": "小王",
  "created_at": "2026-11-24T09:12:00Z",
  "seq": 42
}
```

- 发言：`POST …/comments` 提交 `{id, feature_id?, body, at?}`，返回保存后的发言。`id` 由客户端生成（规则同要素），网络重试时用同一个 `id`，服务端会返回已保存的那条；同一个 `id` 被用于不同内容时返回 `409 id_conflict`。`body` 去掉首尾空白后不能为空，最长 2000 字。`author_id`、`nickname`、`created_at`、`seq` 由服务端填写。
- 拉取：`GET …/comments?since=&limit=`，返回 `{comments, seq, more}`，用法与要素拉取（4.4）相同。讨论有自己的 `seq`，与要素的 `seq` 互不相干。
- 删除：`DELETE …/comments/{comment_id}`，作者本人或组长可删。被删的发言保留一条 `deleted: true`、`body` 为空的记录并获得新的 `seq`，客户端拉到后删除本地副本。
- 发言不能修改；`feature_id` 指向的记录被删除时，讨论仍然保留。
- 每次发言或删除后，服务端通过实时通道广播 `comments_changed`。

### 8.1 紧急消息

服务器能力里有 `alerts` 时可用。全组消息可以带 `"urgent": true`，表示求助或集合，一般同时带上发送者的位置 `at`。

- 只能用于全组消息；带 `feature_id` 时返回 `400 invalid_comment`。
- 只读成员不能发普通发言（`403`），但可以发紧急消息，求助不受角色限制。
- 重试时 `urgent` 也要一致，否则按 `409 id_conflict` 处理。删除后 `urgent` 随正文一起清除。
- 推送类型为 `alert`（第 9 节），发给除发送者外所有登记了地址的成员，**不受**各自 `events` 订阅的限制。
- 客户端应醒目地显示紧急消息（例如置顶、红色、可以一键定位到地图），并用高优先级通知提醒。旧版本服务端会忽略这个字段，客户端发送前应检查 `alerts` 能力。

### 8.2 点名

服务器能力里有 `rollcall` 时可用。组长可以发起点名，例如收工前让大家报平安、转移前清点人数；组员一键回应，组长看到谁回了、在哪、谁还没回。

- 发起：全组消息带 `"roll_call": true`，正文写要求（如"18 点前回营地报平安"）。只有组长能发起（否则 `403 forbidden`）。
- 回应：全组消息带 `"answer_to": "<点名的 id>"`，一般同时带上自己的位置 `at`，正文由客户端填，如"平安""需要帮助"。`answer_to` 必须指向本工作区一条未删除的点名，否则 `400 invalid_comment`。只读成员也可以回应。同一个人可以回应多次，以最新一条为准。
- 点名和回应都只能是全组消息，带 `feature_id` 时返回 `400 invalid_comment`；一条消息不能既是点名又是回应。重试时这两个字段也要一致，否则按 `409 id_conflict` 处理。
- 删除点名后 `roll_call` 随正文一起清除，之后的回应会被拒绝；已有的回应保留。
- 推送类型为 `rollcall`（第 9 节）：点名发给除发起人外的所有成员，每条回应只发给发起点名的组长；都**不受** `events` 订阅的限制。
- 名单由客户端按成员列表计算：点名之后每位未吊销成员（除发起人外）最新的一条回应。客户端应把点名置顶显示到组长结束（删除）为止，回应用一个按钮完成。

### 8.3 外出报备

服务器能力里有 `outing` 时可用。组员单独出去（爬一个山头、去另一条沟）前报一声"去哪、几点回"，回来再报一声；过了时间还没报回来，全组都会收到提醒。

- 外出：全组消息带 `"back_by": "<RFC 3339 时间>"`，正文写去向（如"去北坡看断层"），一般同时带上出发位置 `at`。`back_by` 必须在提交时刻前后 7 天之内，服务端截到整秒保存，否则 `400 invalid_comment`。没有信号时写下的外出可能在预计时间过后才传到服务端，这时它立刻算作超时。
- 返回：全组消息带 `"back_from": "<外出消息的 id>"`，正文由客户端填，如"已返回"，一般带上位置。`back_from` 必须指向本工作区一条未删除的外出消息，否则 `400 invalid_comment`；只有外出的本人或组长能报返回（组长可以替没带手机的人销号），否则 `403 forbidden`。
- 外出和返回都只能是全组消息，不能同时带，也不能和 `urgent`、`roll_call`、`answer_to` 同时用，否则 `400 invalid_comment`。只读成员也可以报外出和返回。重试时这两个字段也要一致，否则按 `409 id_conflict` 处理。
- 一条外出在有未删除的返回指向它之前都算"在外"。还没发出的外出就报了返回时，客户端应把两条都丢掉，不必发送。删除返回会让它重新算在外；删除外出本身则一并作废。
- 超时提醒：过了 `back_by` 仍在外时，服务端发一次推送 `overdue`（第 9 节）给**全部**成员（含本人，提醒他报返回），每条外出最多一次，通知里的 `from` 是外出的人，`seq` 是那条外出消息的 `seq`。服务端关闭推送时不发，客户端自己按 `back_by` 显示即可。
- 客户端应把在外的人置顶显示，超时的醒目标红，并提供"已返回"按钮；超时多久、去向和出发位置都由消息本身算出，不依赖推送。

## 9. 推送通知

服务器能力里有 `push` 时可用（运维人员可以关闭，此时这组接口返回 `404 push_disabled`）。实时通道只在应用打开时有效；推送让应用在后台也能知道有新动静。

- 登记：`PUT …/push` 提交 `{endpoint, events?, keys?}`。`endpoint` 是手机上的推送分发器（[UnifiedPush](https://unifiedpush.org/)，如 ntfy）给出的地址；`events` 是要订阅的类型，省略时全部订阅。`keys` 是设备的 Web Push 密钥 `{p256dh, auth}`（base64url，与浏览器订阅里的同名字段一致），给了就加密投递。每个成员一个地址，再次登记即替换。返回 `{endpoint, events, keys?, updated_at}`。
- 查看与取消：`GET …/push`（没有登记时 `404 not_found`），`DELETE …/push`（返回 `204`）。成员被吊销时登记一并删除。

| 类型 | 何时发 | 发给谁 |
| --- | --- | --- |
| `message` | 有人发了全组消息 | 除发送者外的全部成员 |
| `comment` | 有人在记录下发言 | 除发送者外的全部成员 |
| `task` | 记录的 `assignee` 变成某人（6.1），且不是本人自己指派、`task_status` 不是 `done` | 被指派的人 |
| `alert` | 有人发了紧急消息（8.1）；不能通过 `events` 退订 | 除发送者外的全部成员 |
| `rollcall` | 组长发起点名，或有人回应点名（8.2）；不能通过 `events` 退订 | 点名：除发起人外的全部成员；回应：发起点名的组长 |
| `overdue` | 外出报备（8.3）过了预计返回时间仍未报返回，每条一次；不能通过 `events` 退订 | 全部成员（含外出的本人） |
| `notice` | 组长在共享设置里发了新的或改过的组内公告（2.2 的 `notice`，按 `text` 和 `updated_at` 判断）；只改别的设置或撤下公告不发。`seq` 是新的设置版本 | 除发布人外的全部成员 |

服务端向地址 `POST` 一个 JSON（`Content-Type: application/json`）：

```json
{ "type": "task", "workspace": "w-1c2d", "feature": "f-8a1c", "from": "m-3f2a", "seq": 128 }
```

没有 `keys` 时请求体就是上面的 JSON；有 `keys` 时服务端按 RFC 8291 把它加密成一个 aes128gcm 记录（`Content-Encoding: aes128gcm`，`Content-Type: application/octet-stream`），分发器只看得到密文。

通知里**不含**消息正文、记录内容或昵称：分发器常由第三方运营，客户端被唤醒后应通过带凭据的接口拉取详情（`seq` 告诉它至少要拉到哪里），并用成员列表把 `from` 换成昵称。投递是尽力而为的：失败不重试，分发器回复 `404` / `410` 时服务端删除该登记，客户端可以随时重新登记。网络重试导致同一条发言被重复提交时可能收到两次通知，客户端按 `seq` 去重即可。

为防止服务端被用来访问它所在的内网，服务端默认只接受公网的 `https` 地址，并在连接时检查解析出的 IP，不跟随重定向。

## 10. 查看链接

服务器能力里有 `view` 时可用。成员可以生成一个有时限的只读令牌，在电脑浏览器上查看工作组（例如组长在驻地看全组进度），不必在电脑上加入工作组。

- 生成：`POST …/view-links` 提交 `{hours?}`，`hours` 为 1 到 168，省略时为 24，越界返回 `400 bad_hours`。返回 `201`：

```json
{ "token": "v_…", "expires_at": "2026-11-25T19:30:00Z", "path": "/view/#ws=w-1c2d&t=v_…" }
```

- 令牌以 `v_` 开头，和普通令牌一样放在 `Authorization: Bearer` 或实时通道的 `access_token` 里。服务端只保存它的哈希。
- `path` 是服务器上的查看页；客户端把服务器地址和它拼起来交给用户（复制、分享）。令牌放在 `#` 后面，浏览器不会把它发给服务器，所以不会进入访问日志或 `Referer`。
- 撤销：`DELETE …/view-links` 撤销调用者生成的全部链接，返回 `{revoked: n}`（仍有效的条数）。成员被吊销时，他生成的链接一并失效。过期或撤销后，用这个令牌的请求返回 `401`。

持查看链接的请求以生成者的身份、按**只读**角色处理，并且进一步限制：

- 可以读：工作区信息、成员、共享设置、要素拉取与历史、两种导出、讨论、附件下载、实时通道、MCP 接口（第 11 节）。
- 不能写：提交要素、上传附件、发言（包括紧急消息）、推送登记、生成或撤销查看链接，一律返回 `403`。
- 实时通道上可以收到全部消息，但发送 `position` 会收到 `{"type":"error","code":"read_only"}`；这个连接不算该成员在线，不触发 `presence`，也不更新 `last_seen`。

查看页是服务端自带的一张静态网页，不需要另外部署；它只用上面的公开接口，第三方也可以用查看链接做自己的看板。

## 11. AI 工具接口（MCP）

服务器能力里有 `mcp` 时可用。`POST …/mcp` 是一个 [Model Context Protocol](https://modelcontextprotocol.io) 服务端（Streamable HTTP 传输），让桌面上的 AI 工具（例如支持 MCP 的聊天客户端、编辑器）读取工作组，回答「今天谁采了哪些样」「D012 附近的花岗岩点有哪些」「还有哪些记录要补」这类问题。

- 鉴权：`Authorization: Bearer` 放查看链接的令牌（`v_…`，第 10 节）或组员令牌。交给 AI 工具的应当是查看链接：它只能读、有时限、可撤销，客户端生成后把地址和它一起给用户，不要把组员令牌显示出来。
- 传输：每个 JSON-RPC 2.0 消息一次 `POST`，响应为 `application/json`；服务端无状态，不发 `Mcp-Session-Id`，不提供 `GET` 推送流（返回 `405`）。通知（没有 `id` 的消息）返回 `202` 空响应。不支持批量（数组），返回 `400`。
- 方法：`initialize`（协议版本取客户端请求的，若不认识则回最新的 `2025-06-18`）、`ping`、`tools/list`、`tools/call`；其余返回 `-32601`。
- 工具全部只读（`readOnlyHint`），改动仍在应用里做，那里有动作的风险分级和确认（见 [actions.md](actions.md)）。结果同时放在 `structuredContent` 和一段 JSON 文本里；参数错误、记录不存在以 `isError: true` 的工具结果返回，内部错误只说「稍后再试」，细节只进服务端日志。

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `workspace_summary` | 无 | 名称、是否归档、成员（昵称、角色、最近活动）、记录总数和按 `kind` 计数、样品数、最近修改时间 |
| `search_records` | `query`（空格分隔、都要出现，在名称、岩性、描述、样品号里找，不分大小写）、`kind`、`member`（最近修改者昵称）、`since`（日期或 RFC 3339 时间）、`near_record`（某条记录的 id）或 `near_lon` 与 `near_lat`、`radius_m`（默认 1000，最多 100000）、`limit`（默认 20，最多 100） | `matched` 总数和记录摘要（id、kind、名称、岩性、描述前 160 字、样品号、首个坐标、修改时间与修改人）；按修改时间从新到旧，给了位置时按首个坐标的距离从近到远并带 `distance_m`，不含作为中心的那条记录 |
| `get_record` | `id`（记录 id；也可以是名称如 `D012`，只有一条记录叫这个名字时才认） | 完整要素（与拉取接口同格式）、最近修改人昵称、历史里较早版本的个数、这条记录的讨论（最近 50 条，旧的在前） |
| `review_issues` | 无 | 与 `GET …/checks` 相同的待核对清单 |
| `reference_at` | `record`（记录 id，取首个坐标）或 `lon` 与 `lat`、`reach_m`（默认 200，最多 5000） | 组长共享的参考图层（§2.2 `reference_layers`）在该处的结果：包含它的面（图层名、`color_by` 属性的值、最多 12 个属性）和 `reach_m` 内的线（同上，带 `distance_m`，从近到远）；没有参考图层时 `layers` 为 0 并带说明 |
| `workload` | `from`、`to`（日期，含两端）、`tz_offset_minutes`（默认 480，即北京时间） | 与工作量统计表相同的列，每天每人一行；不限日期时带每人的合计行 |
| `recent_messages` | `limit`（默认 30，最多 100） | 最近的组内消息和记录讨论（发言人、内容、时间、所属记录、是否紧急或点名），旧的在前 |
| `tasks` | `member`（被指派人昵称）、`status`（`todo`、`doing`、`done`）、`limit`（默认 50，最多 200） | 指派给组员的记录（§6.1）：id、种类、名称、被指派人、状态、首个坐标、修改时间，按状态、人、名称排序；另给每人的待办、进行中、已完成数 |
| `team_positions` | 无 | 组员最近共享的实时位置（§7 `position`，只含 15 分钟内的）：昵称、是否在线、经纬度、精度、时间和距今秒数，从新到旧；另列在线但没有共享位置的组员。服务端只有内存里的最后位置，不留轨迹 |

接入时把 `https://服务器/api/v0/workspaces/{id}/mcp` 和令牌填进 AI 工具的 MCP 设置（远程服务器、自定义请求头）。拿到令牌的工具在有效期内能读全组数据（包括组员此刻的位置），只填进自己信任的工具，用完撤销。

## 12. 版本演进

- 同一主版本（v0）内只增加字段、接口和消息类型，不删除、不改变含义。
- 不兼容的修改会使用新的路径前缀（`/api/v1/`），服务端可同时提供多个版本。
- 本仓库的 [`conformance`](../conformance/) 工具从外部检查一个服务端是否符合本协议的核心部分（鉴权、同步与冲突、历史、设置、附件、实时通道），自己实现服务端时可以用它对照。
