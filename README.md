# CheckPackageSize

面向 Minecraft 1.12.2 Cleanroom / Java 25 的网络流量诊断工具。它通过 Mixin 统计 Minecraft 应用层的包次数、编码大小和压缩后大小，用于定位整合包中的异常 Mod 与具体消息类。

SimpleNetworkWrapper 消息会关联 channel、discriminator、真实 `IMessage`、Handler 和所属 Mod；远程字节数在 Vanilla/Netty 层测量，单机则按照 Forge 的 CustomPayload 转换和分片规则计算理论流量。

采集结果在内存中聚合并写入 HTML 报告.

## 运行模式

### 单机与 LAN 主机

按 `F8` 打开界面，输入采集秒数和本端发包调用栈深度后开始采集。客户端与整合服务端在同一 JVM 中联合采集，生成：

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
/cps start 30 16
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

## 游戏内工作台

游戏内界面围绕一次调试会话提供：

- 采集前显示当前环境、实际/理论流量语义和持续时间。
- 采集时长支持输入 1–300 秒；调用栈深度支持 0–64 层，0 表示关闭追溯。
- 采集中每 500 毫秒刷新状态，并显示最近 60 个一秒时间桶。
- 实时显示 Wire 速率、包速率、C2S/S2C、丢弃数和单机待处理任务。
- 结果页提供概览、Mod 和包类型三个视图；包类型表直接显示本端调用路径数量。
- 点击具体网络包进入独立详情页，可分别滚动调用路径列表和配置深度内的完整调用栈。
- Mod/包表格支持搜索、方向过滤、排序、滚动和详情检查器。
- 可暂停界面刷新而不停止采集；停止后生成并可直接打开 HTML 报告。

界面继续使用当前 Minecraft 字体，不内置或替换字体渲染。

## 数据边界

- 不保存包 Payload、NBT 或聊天内容。
- 调用追溯只保存类、方法、行号和有限深度的栈帧，不保存方法参数或局部变量。
- 远程单端采集只能追溯本端发出的包：客户端为 C2S，服务端为 S2C；单机联合模式可覆盖两个逻辑端。
- 远程流量取 Minecraft 应用层压缩帧，不包含 TCP/IP 包头和重传。
- 不保存逐包时间线；只保留最近 60 个一秒聚合桶和按包类型、Mod 汇总的数据。
- 不测量编解码耗时、发送队列延迟、发送结果或主线程处理时间。
- 快捷键可在 Minecraft 控制设置中重新绑定。
