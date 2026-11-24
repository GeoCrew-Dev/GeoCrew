# 同勘 GeoCrew 协同协议 v0（草案）

> 状态：草案，接口可能变动。所有路径以服务端根地址为前缀，例如 `https://geo.example.org`。

## 1. 基本约定

- 传输：HTTPS + JSON（UTF-8）；实时通道为 WebSocket（`wss://`）。
- 时间：RFC 3339 UTC 字符串。
- 标识：客户端生成 UUID v4 作为要素 id，离线也能创建。
- 几何：GeoJSON（RFC 7946），坐标 `[经度, 纬度, 高程?]`。
- 版本：`GET /api/v0/info` 返回服务端支持的协议版本。

## 2. 鉴权

1. 组长调用 `POST /api/v0/workspaces` 创建工作区，返回 `workspace_id`、`invite_code` 和组长的 `token`。
2. 组员调用 `POST /api/v0/workspaces/join`，提交 `invite_code` 与昵称，返回该成员的 `token`。
3. 之后的请求在头部携带 `Authorization: Bearer <token>`。
4. 邀请码可由组长重置；令牌可由组长吊销。

二维码内容为 `geocrew://join?server=<根地址>&code=<invite_code>`。

## 3. 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v0/info` | 服务端名称、协议版本 |
| POST | `/api/v0/workspaces` | 创建工作区 `{name, nickname}` |
| POST | `/api/v0/workspaces/join` | 加入 `{invite_code, nickname}` |
| GET | `/api/v0/workspaces/{id}/members` | 成员列表 |
| GET | `/api/v0/workspaces/{id}/features?since=<seq>` | 拉取 `seq` 之后的变更 |
| POST | `/api/v0/workspaces/{id}/features` | 批量提交变更（见第 4 节） |
| PUT | `/api/v0/workspaces/{id}/files/{sha256}` | 上传照片等附件，按内容哈希去重 |
| GET | `/api/v0/workspaces/{id}/files/{sha256}` | 下载附件 |
| GET | `/api/v0/workspaces/{id}/ws` | 升级为 WebSocket 实时通道 |

## 4. 要素同步

要素即一个 GeoJSON Feature，附加同步字段：

```json
{
  "type": "Feature",
  "id": "8d3c…",
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
    "deleted": false
  }
}
```

- 客户端离线时把修改放入本地队列，联网后批量 `POST`。
- 服务端为每次接受的修改分配单调递增的 `seq`；客户端记住最后的 `seq`，用 `since` 增量拉取。
- 冲突处理（v0）：同一要素以 `version` 较大者为准，版本相同时比较 `updated_at`，再相同时比较 `updated_by`。被覆盖的旧版本保留在服务端历史中，可人工恢复。
- 删除为软删除（`deleted: true`）。

## 5. 实时通道

WebSocket 消息均为 JSON，带 `type` 字段：

| type | 方向 | 内容 |
| --- | --- | --- |
| `position` | 双向 | 成员实时位置 `{member_id, lon, lat, accuracy, at}` |
| `feature_changed` | 服务端→客户端 | 有新变更，附 `seq`，客户端随后增量拉取 |
| `comment` | 双向 | 挂在要素上的讨论 `{feature_id, text, at}`（第三阶段） |
| `ping` / `pong` | 双向 | 保活 |

位置只做转发与短期保留，不写入要素历史。
