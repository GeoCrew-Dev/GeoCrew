# 同勘 GeoCrew 服务端

同勘 GeoCrew 小组协同功能的自部署服务端参考实现，按 [geocrew-protocol](https://github.com/GeoCrew-Dev/geocrew-protocol) 中的协议实现。

> 状态：早期骨架。目前支持创建工作区、邀请码加入和成员列表，数据仅存内存；要素同步、实时通道和持久化存储正在开发。

## 快速部署

需要一台装有 Docker 的 Linux 服务器：

```bash
git clone https://github.com/GeoCrew-Dev/geocrew-server.git
cd geocrew-server
docker compose up -d
```

服务默认监听 `8080` 端口。生产环境请在前面加一层 HTTPS 反向代理（如 Caddy 或 Nginx）。

## 本地开发

```bash
go test ./...
go run ./cmd/geocrew-server -addr :8080
curl http://localhost:8080/api/v0/info
```

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `GEOCREW_ADDR` | `:8080` | 监听地址 |
| `GEOCREW_NAME` | `GeoCrew Server` | 服务器显示名称 |

## 许可

Apache-2.0
