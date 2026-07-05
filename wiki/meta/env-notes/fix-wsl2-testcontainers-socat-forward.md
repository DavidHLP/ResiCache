---
title: 修复 WSL2 native docker 下 testcontainers 集成测试不通
type: how-to
tags:
  - howto
  - docker
  - testcontainers
  - wsl2
  - 集成测试
  - 故障排查
related: [README, log, fix-docker-pull-ipv6-timeout]
status: stable
created: 2026-07-03
updated: 2026-07-03
---

# 修复 WSL2 native docker 下 testcontainers 集成测试不通

WSL2 + `docker-ce`(非 Docker Desktop)特定拓扑下,testcontainers 1.20.4 有三层叠加问题,需用 **bridge network 容器 + socat 中转** 方案,**保留 testcontainers 完整框架**。

## 症状

```text
$ docker pull testcontainers/ryuk:0.11.0      # 能拉
$ docker run redis:7-alpine                   # 能跑
$ python3 -c "import socket; ..."             # 127.0.0.1:6379 能通 +PONG

$ ./mvnw -Dtest='*IntegrationTest' test
[ERROR] Tests run: 47, Failures: 0, Errors: 48
```

集成测试要么 `Skipped`,要么 `Application run failed: Unable to connect to Redis server: localhost/127.0.0.1:37797`。

## 根因(三层叠加,WSL2 + docker-ce 特有)

1. **docker0 网桥 state DOWN** —— WSL2 内 docker0 网卡 `state DOWN, NO-CARRIER`。容器用默认 bridge 分配 IP 172.17.0.x,但 WSL userspace 流量到 172.17.0.0/16 子网 timeout
2. **testcontainers 1.20.4 + host network isRunning() 谓词 bug** —— `withNetworkMode("host")` 让容器直接 bind WSL 的 0.0.0.0:6379,但 testcontainers 1.20.4 内部 `tryStart` 调用 `Awaitility.await().until(containerInfo -> state.running)` 谓词在 host network 模式下持续 false(state 已 running=true 但谓词 false),最终 `RetryCountExceededException`
3. **socat 中转可通** —— 同一容器 IP `172.17.0.2` 直连 timeout,但通过 `socat TCP-LISTEN:6390,fork,reuseaddr TCP:172.17.0.2:6379` 转发后,`127.0.0.1:6390` 可通(已用 python socket 验证 +PONG)

## 修复方案(保留 testcontainers 完整框架)

**核心思路**:bridge network 容器(让 testcontainers isRunning 谓词正常工作)+ socat 在 WSL 内 127.0.0.1 中转(绕开 docker0 down)。

### `src/test/java/.../AbstractRedisIntegrationTest.java` —— 关键变更

```java
public abstract class AbstractRedisIntegrationTest {
    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final int REDIS_PORT = 6379;
    private static final int FORWARD_PORT;  // 预分配空闲端口
    private static final List<Process> SOCAT_PROCESSES = new ArrayList<>();

    static {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            FORWARD_PORT = s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot allocate free local port", e);
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(AbstractRedisIntegrationTest::stopSocatForwards));
    }

    @Container
    protected static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "")
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> FORWARD_PORT);
    }

    @org.junit.jupiter.api.BeforeAll
    static void startContainerAndForward() {
        Startables.deepStart(Collections.singletonList(REDIS_CONTAINER)).join();
        startSocatForward();
    }

    private static void startSocatForward() {
        String containerIp = REDIS_CONTAINER.getContainerInfo()
                .getNetworkSettings().getNetworks()
                .values().iterator().next().getIpAddress();
        Process p = new ProcessBuilder("socat",
                "TCP-LISTEN:" + FORWARD_PORT + ",fork,reuseaddr",
                "TCP:" + containerIp + ":" + REDIS_PORT)
                .redirectErrorStream(true).start();
        // 等待 socat 端口 listen(最多 5s, socket probe loop)
        SOCAT_PROCESSES.add(p);
    }
}
```

### 系统依赖

```bash
sudo dnf install -y socat    # Fedora
```

### surefire 环境变量

```bash
export TESTCONTAINERS_RYUK_DISABLED=true    # 绕开 WSL2 下 ryuk 容器启动失败
```

## 自审要点(踩过的坑)

| 反模式 | 后果 |
|--------|------|
| `withNetworkMode("host")` + testcontainers 1.20.4 | isRunning() 谓词 false 误判,容器永远起不来 |
| `withExposedPorts(6379).withNetworkMode("host")` | getMappedPort() 抛 IllegalStateException |
| 不设 `TESTCONTAINERS_RYUK_DISABLED=true` | ryuk 容器在 WSL2 启动失败,所有 test class 被 skip |
| 让 testcontainers 监听 0.0.0.0:6390 端口(用 -p) | WSL 内 localhost:6390 Connection refused(WSL2 NAT 阻断) |
| 不预分配空闲端口(`new ServerSocket(0)`) | 端口冲突风险,集成测试间互相抢端口 |

## 验证清单

- [ ] `which socat` 有输出
- [ ] `docker info` 显示 `Server Version: 29.x` 且 `Operating System` 不是 Docker Desktop
- [ ] `./mvnw verify -Dmaven.javadoc.skip=true` 跑通
- [ ] `Tests run: 787, Failures: 0, Errors: 0, Skipped: 0`
- [ ] JaCoCo `All coverage checks have been met`

## 适用环境

- ✅ WSL2 Fedora 44(其他 WSL2 distro 类似)
- ✅ WSL2 内 `docker-ce`(非 Docker Desktop)
- ✅ testcontainers 1.20.4
- ⚠️ Docker Desktop 用户**不需要**这个方案(网络栈不同)
- ⚠️ testcontainers 升级到 1.21+ 需重新验证

## 关联命令

```bash
# 验证 socat 转发
python3 -c "import socket; s=socket.create_connection(('127.0.0.1', <FORWARD_PORT>), timeout=3); s.send(b'PING\r\n'); print(s.recv(100))"

# 看 socat 进程
ps aux | grep socat

# 清理残留 socat
pkill -f 'socat TCP-LISTEN.*reuseaddr'

# 跑全 verify
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -B verify -Dmaven.javadoc.skip=true
```
