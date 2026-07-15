# CheckPackageSize

面向 Minecraft 1.12.2 Cleanroom / Java 25 的网络流量诊断工具。它通过 Mixin 统计 Minecraft 应用层的包次数、编码大小和压缩后大小，用于定位整合包中的异常 Mod 与具体消息类。

采集结果在内存中聚合并写入 HTML 报告.

## 运行模式

### 单机与 LAN 主机

按 `F8` 打开界面，选择 10、30 或 60 秒并开始采集。客户端与整合服务端在同一 JVM 中联合采集，生成：

```text
logs/checkpackagesize/<会话>/
  report.html
```

流量是按照 Minecraft 默认 256 字节压缩阈值计算的远程理论开销.

### 连接远程服务器的客户端

F8 只采集当前客户端，不向服务器发送控制消息，也不尝试取得服务端数据：

```text
logs/checkpackagesize/<会话>/
  report.html
```

### 独立服务端

由服务器控制台、RCON 或 OP 2 级用户控制：

```text
/cps start 30
/cps stop
/cps status
/cps report
```

生成：

```text
logs/checkpackagesize/<会话>/
  report.html
```

服务端报告不会发送给客户端。

## 数据边界

- 不保存包 Payload、NBT 或聊天内容。
- 远程流量取 Minecraft 应用层压缩帧，不包含 TCP/IP 包头和重传。
- 快捷键可在 Minecraft 控制设置中重新绑定。
