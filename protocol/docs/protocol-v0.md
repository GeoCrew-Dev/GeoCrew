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
| 409 | 冲突，例如移除最后一位组长（`last_leader`）、基于旧版本修改设置（`stale`） |
| 413 | 请求过大（`batch_too_large`、`too_large`） |

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
| `form` | 记录表单：属性数组，每项 `{key, label?, type?, options?, unit?, kinds?}`。`type` 为 `TEXT`（默认）、`NUMBER` 或 `CHOICE`；`kinds` 为适用的要素种类，空表示全部。值写在要素 `properties[key]` 里，文字始终是字符串，数字是 JSON 数字。 |

不认识的键必须原样保留：客户端修改设置时先读取、只改自己负责的键、再整体提交。

## 3. 接口一览

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| GET | `/healthz` | 无 | 健康检查，返回 `ok` |
| GET | `/api/v0/info` | 无 | 名称、协议版本、能力、上传上限 |
| POST | `/api/v0/workspaces` | 无 | 创建工作区 `{name, nickname}` |
| POST | `/api/v0/workspaces/join` | 无 | 加入 `{invite_code, nickname}` |
| GET | `/api/v0/workspaces/{id}` | 任意 | 工作区信息、我的身份、在线成员；组长额外看到邀请码 |
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
| GET | `/api/v0/workspaces/{id}/export.geocrew.zip` | 任意 | 工程包（zip）：`project.json`（`format: "geocrew-project"`、名称、日期，`config.fields` 为共享设置里的 `form`）、`features.geojson`、`photos/<sha256>.jpg`（记录 `photos` 引用且服务器上有的附件）。与客户端导出的工程包同一格式，可直接导入为新工程；不含成员、凭据和历史。能力名 `package` |
| GET | `/api/v0/workspaces/{id}/comments?since=&limit=` | 任意 | 增量拉取讨论（见第 8 节） |
| POST | `/api/v0/workspaces/{id}/comments` | 组长、成员 | 发言 |
| DELETE | `/api/v0/workspaces/{id}/comments/{comment_id}` | 作者、组长 | 删除发言 |
| PUT | `/api/v0/workspaces/{id}/files/{sha256}` | 组长、成员 | 上传附件 |
| GET / HEAD | `/api/v0/workspaces/{id}/files/{sha256}` | 任意 | 下载附件 / 查询是否存在 |
| GET | `/api/v0/workspaces/{id}/ws` | 任意 | 升级为 WebSocket 实时通道 |
| GET / PUT / DELETE | `/api/v0/workspaces/{id}/push` | 任意 | 查看、登记、取消我的推送地址（见第 9 节） |

`info` 示例：

```json
{
  "name": "GeoCrew Server",
  "protocol": "0",
  "version": "1a2b3c",
  "capabilities": ["sync", "files", "realtime", "roles", "history", "settings", "export", "comments", "package", "push", "create"],
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
| `sync.deleted` | 客户端 | 软删除标记；删除也是一次修改，`version` 加 1 |
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
| `point` | Point | `title`、`note`、`photos` |
| `geo_point` | Point | 地质点：`title`、`lithology`、`strike`、`dip_direction`、`dip`、`note`、`photos` |
| `line` | LineString | `title`、`note`、`length_m` |
| `polygon` | Polygon | `title`、`note`、`area_m2` |
| `track` | LineString | 轨迹：`title`、`started_at`、`ended_at`、`length_m`；坐标可带高程 |
| `photo` | Point | 独立照片点：`photos`、`azimuth`（拍摄方位角） |

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

各字段都可省略；照片从 `photos` 移除时，对应条目也应删除。服务端不解析这个对象。旧版独立照片点的 `azimuth` 属性仍然有效。

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

## 9. 推送通知

服务器能力里有 `push` 时可用（运维人员可以关闭，此时这组接口返回 `404 push_disabled`）。实时通道只在应用打开时有效；推送让应用在后台也能知道有新动静。

- 登记：`PUT …/push` 提交 `{endpoint, events?, keys?}`。`endpoint` 是手机上的推送分发器（[UnifiedPush](https://unifiedpush.org/)，如 ntfy）给出的地址；`events` 是要订阅的类型，省略时全部订阅。`keys` 是设备的 Web Push 密钥 `{p256dh, auth}`（base64url，与浏览器订阅里的同名字段一致），给了就加密投递。每个成员一个地址，再次登记即替换。返回 `{endpoint, events, keys?, updated_at}`。
- 查看与取消：`GET …/push`（没有登记时 `404 not_found`），`DELETE …/push`（返回 `204`）。成员被吊销时登记一并删除。

| 类型 | 何时发 | 发给谁 |
| --- | --- | --- |
| `message` | 有人发了全组消息 | 除发送者外的全部成员 |
| `comment` | 有人在记录下发言 | 除发送者外的全部成员 |
| `task` | 记录的 `assignee` 变成某人（6.1），且不是本人自己指派、`task_status` 不是 `done` | 被指派的人 |

服务端向地址 `POST` 一个 JSON（`Content-Type: application/json`）：

```json
{ "type": "task", "workspace": "w-1c2d", "feature": "f-8a1c", "from": "m-3f2a", "seq": 128 }
```

没有 `keys` 时请求体就是上面的 JSON；有 `keys` 时服务端按 RFC 8291 把它加密成一个 aes128gcm 记录（`Content-Encoding: aes128gcm`，`Content-Type: application/octet-stream`），分发器只看得到密文。

通知里**不含**消息正文、记录内容或昵称：分发器常由第三方运营，客户端被唤醒后应通过带凭据的接口拉取详情（`seq` 告诉它至少要拉到哪里），并用成员列表把 `from` 换成昵称。投递是尽力而为的：失败不重试，分发器回复 `404` / `410` 时服务端删除该登记，客户端可以随时重新登记。网络重试导致同一条发言被重复提交时可能收到两次通知，客户端按 `seq` 去重即可。

为防止服务端被用来访问它所在的内网，服务端默认只接受公网的 `https` 地址，并在连接时检查解析出的 IP，不跟随重定向。

## 10. 版本演进

- 同一主版本（v0）内只增加字段、接口和消息类型，不删除、不改变含义。
- 不兼容的修改会使用新的路径前缀（`/api/v1/`），服务端可同时提供多个版本。
