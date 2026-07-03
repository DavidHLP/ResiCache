---
title: 修复 Docker 拉取镜像 IPv6 timeout
type: how-to
tags:
  - howto
  - docker
  - 环境修复
  - wsl2
  - 故障排查
related: [README, log]
status: stable
created: 2026-07-03
updated: 2026-07-03
---

# 修复 Docker 拉取镜像 IPv6 timeout

WSL2 + 大陆网络环境的常见痛点。`docker pull` 报 `dial tcp [2a03:2880::/32]:443: i/o timeout`,Docker daemon 走 IPv6 直连 `registry-1.docker.io` 不通,但 `curl` 走 shell 代理能通。

## 症状

```text
$ docker pull hello-world
Error response from daemon: failed to resolve reference "docker.io/library/hello-world:latest":
failed to do request: Head "https://registry-1.docker.io/v2/library/hello-world/manifests/latest":
dial tcp [2a03:2880:f107:83:face:b00c:0:25de]:443: i/o timeout
```

- `2a03:2880::/32` 是 Facebook / Meta 拥有的 IPv6 段
- DNS 解析 `registry-1.docker.io` 优先返回 AAAA 记录
- Docker daemon 默认**不继承 shell 的 `HTTPS_PROXY` 环境变量**(systemd 隔离)

## 根因(三层叠加)

1. **daemon 未配置代理** — `systemd` 启动的 `dockerd` 进程与 shell 环境的代理变量是隔离的
2. **DNS 优先返回 IPv6** — 走直连 `2a03:2880::/32` Facebook 段,防火墙阻断
3. **没有 registry mirror** — 兜底路径缺失,即使配了代理,仍可能被 DNS 劫持

## 修复(三层兜底)

### 1. `/etc/docker/daemon.json` —— 注册大陆 mirror

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io"
  ],
  "log-level": "warn",
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" },
  "default-runtime": "runc",
  "features": { "containerd-snapshotter": true },
  "live-restore": true,
  "userland-proxy": false,
  "no-new-privileges": true
}
```

> **注意**: `storage-driver` 与 `features.containerd-snapshotter` **互斥**。若 `docker info` 显示 `driver-type: io.containerd.snapshotter.v1`,不要写 `storage-driver`,否则 daemon 启动失败。

### 2. `/etc/systemd/system/docker.service.d/http-proxy.conf` —— 注入 daemon 代理

```ini
[Service]
Environment="HTTP_PROXY=http://127.0.0.1:7897"
Environment="HTTPS_PROXY=http://127.0.0.1:7897"
Environment="http_proxy=http://127.0.0.1:7897"
Environment="https_proxy=http://127.0.0.1:7897"
Environment="NO_PROXY=localhost,127.0.0.1,docker.m.daocloud.io,*.daocloud.io,registry-1.docker.io,*.docker.io,registry.aliyuncs.com,*.aliyuncs.com,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16"
Environment="no_proxy=localhost,127.0.0.1,docker.m.daocloud.io,*.daocloud.io,registry-1.docker.io,*.docker.io,registry.aliyuncs.com,*.aliyuncs.com,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16"
```

> `NO_PROXY` 必须涵盖 mirror 端点,避免代理环路。

### 3. `~/.docker/config.json` —— 客户端代理

```json
{
  "proxies": {
    "default": {
      "httpProxy": "http://127.0.0.1:7897",
      "httpsProxy": "http://127.0.0.1:7897",
      "noProxy": "localhost,127.0.0.1,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16"
    }
  },
  "experimental": "enabled"
}
```

> **不要**写 `credsStore: ""` — 空字符串会让 `docker login` 误调不存在的 credential helper。

### 4. 重启生效

```bash
sudo systemctl daemon-reload
sudo systemctl restart docker
docker info | grep -A 3 "Registry Mirrors"   # 应输出 https://docker.m.daocloud.io/
docker pull hello-world                       # 验证
```

## 自审要点(踩过的坑)

| 反模式 | 后果 |
|--------|------|
| 同时写 `storage-driver: overlay2` 和 `features.containerd-snapshotter: true` | daemon 启动失败,镜像丢失风险 |
| 写 `credsStore: ""` | `docker login` 误调不存在的 helper |
| 忘了 `NO_PROXY` 加 mirror 端点 | 代理环路 / 无法拉取 |
| 只配 mirror 不配 daemon 代理 | mirror 临时不可用时无兜底 |
| 只配 daemon 代理不配 mirror | 慢速 + 仍受 DNS 劫持影响 |

## 验证清单

- [ ] `docker info | grep "Registry Mirrors"` 输出 mirror URL
- [ ] `docker pull hello-world` 成功
- [ ] `docker pull testcontainers/ryuk:0.11.0` 成功(testcontainers 关键镜像)
- [ ] `docker run --rm hello-world` 输出 `Hello from Docker!`
- [ ] daemon 日志 `journalctl -u docker` 无 `dial tcp ... i/o timeout`

## 适用环境

- ✅ WSL2 (Windows Subsystem for Linux 2)
- ✅ 大陆网络(防火墙阻断 IPv6 Facebook 段)
- ✅ 本地代理端口 `7897` (Clash / V2RayN / mihomo)
- ⚠️ 代理端口不同时,把 `7897` 替换为实际值

## 关联命令

```bash
# 查看 daemon 当前代理环境
sudo systemctl show docker | grep -iE 'proxy|env'

# 查看 daemon 拉取日志
sudo journalctl -u docker --since '5 min ago' | grep -iE 'daocloud|mirror|registry'

# 验证 mirror 端点健康
curl -s -o /dev/null -w 'HTTP:%{http_code}\n' https://docker.m.daocloud.io/v2/
```
