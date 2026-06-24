# 编程作业：P2P 文件共享系统


## 一、背景

在本次作业中，你需要实现一个简化版的 P2P 文件共享系统。系统由三个角色组成：**Tracker** 负责维护元数据，**Peer** 负责存储和提供文件，**Client** 负责查询和下载文件。

---

## 二、系统架构

```
Client ──查询──▶ Tracker ──响应──▶ Client
Client ──下载──▶ Peer
Peer   ──注册──▶ Tracker
```

---

## 三、角色说明

**Tracker**
维护节点和文件的映射关系，需要同时处理来自多个 Peer 和 Client 的并发请求。

**Peer**
启动时向 Tracker 注册自身信息和文件列表，同时接受 Client 的下载请求。

**Client**
向 Tracker 查询目标文件在哪个 Peer 上，然后直接从该 Peer 下载文件。

---

## 四、框架说明

我们提供了包含类骨架的代码框架，所有方法体为空，你需要自己实现所有逻辑。框架结构如下：

```
src/
├── common/
│   ├── Message.java
│   ├── FileTransfer.java
│   └── PeerInfo.java
├── tracker/
│   ├── Tracker.java
│   └── TrackerConnectionHandler.java
├── peer/
│   ├── Peer.java
│   ├── PeerConnectionHandler.java
│   ├── ChunkManager.java
│   ├── FileChunk.java
│   └── ParallelDownloader.java
└── client/
    └── Client.java
```

---

## 五、实现指引

### 5.1　类的职责与依赖关系

```
PeerInfo          — 数据类，已实现，直接使用
Message           — 协议报文工具，所有网络类都依赖它，最先实现
FileTransfer      — 文件读写工具，Peer 和 Client 依赖它，第二实现
Tracker           — 启动服务器，依赖 TrackerConnectionHandler
TrackerConnectionHandler — 处理单个连接，依赖 Message、PeerInfo
Peer              — 注册 + 启动服务器，依赖 PeerConnectionHandler
PeerConnectionHandler    — 处理下载请求，依赖 Message、FileTransfer
Client            — 串联全流程，依赖 Message、FileTransfer、PeerInfo
FileChunk         — 分块数据类，依赖无
ChunkManager      — 文件切分与合并，依赖 FileChunk
ParallelDownloader — 多 Peer 并发分块下载，依赖 ChunkManager、FileChunk、PeerInfo
```

### 5.2　建议实现顺序

| 步骤 | 目标                                                     | 完成标志                                     |
| ---- | -------------------------------------------------------- | -------------------------------------------- |
| 1    | 实现 `Message`（parse + 所有 build 方法）                | 能正确拼装和解析协议字符串                   |
| 2    | 实现 `FileTransfer`（sendFile / receiveFile / md5）      | 能把文件写出再读回、MD5 一致                 |
| 3    | 实现 `Tracker` + `TrackerConnectionHandler`              | Peer 能注册，Client 能查到 FOUND / NOT_FOUND |
| 4    | 实现 `Peer` + `PeerConnectionHandler`                    | Client 能从 Peer 下载文件且 MD5 正确         |
| 5    | 实现 `Client`                                            | 完整走通"查询 → 下载 → 校验"流程             |
| 6    | 实现 `FileChunk` + `ChunkManager` + `ParallelDownloader` | 多 Peer 分块并发下载，合并后 MD5 正确        |

### 5.3　自动测试

项目根目录提供了 `run_tests.sh` 和 `p2p-tests.jar`，可一键编译并运行所有测试。

```bash
# 运行功能测试（F1–F7）
bash run_tests.sh

# 运行全部测试（含性能测试，耗时较长）
bash run_tests.sh --perf
```

脚本会自动编译你的源码，然后用 `p2p-tests.jar` 运行测试，输出每个用例的 `[PASS]` / `[FAIL]` 及最终通过数。

### 5.4　本地手动验证

**编译**（在项目根目录下执行）

```bash
javac -d out/production src/common/*.java src/tracker/*.java src/peer/*.java src/client/*.java
cd out/production
```

**准备测试文件**

```bash
mkdir -p /tmp/peer_files
echo "hello p2p" > /tmp/peer_files/test.txt
```

**开三个终端，依次执行**

```bash
# 终端 1 — 启动 Tracker
java Tracker 9000

# 终端 2 — 启动 Peer（peer_id 可用任意唯一字符串，如 localhost:9001）
java Peer localhost 9000 9001 /tmp/peer_files

# 终端 3 — Client 下载
java Client localhost 9000 test.txt /tmp/downloaded.txt
```

**用 `nc` 手动验证 Tracker（无需启动 Peer）**

```bash
printf "QUERY\nfilename: test.txt\n\n" | nc localhost 9000
```

---

## 六、启动方式

```bash
# 启动 Tracker
java Tracker <port>

# 启动 Peer
java Peer <tracker_host> <tracker_port> <peer_port> <file_dir>

# 启动 Client
java Client <tracker_host> <tracker_port> <filename> <save_path>
```

---

## 七、通信协议

所有通信基于 TCP。协议格式如下，所有字符编码为 UTF-8，换行符统一使用 `\n`，每条消息以一个空行（即连续两个 `\n`）结尾，字段之间用 `\n` 分隔。

### 7.1　Peer 向 Tracker 注册

```
REGISTER
peer_id: <id>
host: <host>
port: <port>
files: <file1>,<file2>,...

```

> - `peer_id` 为任意能唯一标识该 Peer 的字符串，推荐使用 `host:port`，例如 `localhost:9001`。
> - Tracker 内部以 filename 为 key 维护"该文件的当前持有者"：当多个 Peer 注册同一文件时，后注册者覆盖先注册者。`files` 字段以英文逗号分隔。

### 7.2　Client 向 Tracker 查询

```
QUERY
filename: <filename>

```

### 7.3　Tracker 响应

找到文件时（单 Peer 持有）：

```
FOUND
host: <host>
port: <port>

```

未找到文件时：

```
NOT_FOUND

```

> `FOUND` 始终只包含一组 `host`/`port`。当多个 Peer 注册同一文件时，按 7.1 的覆盖语义返回最后注册者。

### 7.4　Client 向 Peer 请求文件

完整文件下载：

```
GET
filename: <filename>

```

范围下载（分块并发时使用）：

```
GET
filename: <filename>
offset: <起始字节偏移>
length: <读取字节数>

```

> 携带 `offset` 和 `length` 时，Peer 只发送对应范围的字节流，不发送完整文件。

### 7.5　Peer 响应

```
OK
filename: <filename>
size: <bytes>
md5: <md5>

<文件字节流>
```

> **OK 头中的 `size` 与 `md5` 始终对应整文件**，与是否带 `offset`/`length` 无关；`offset`/`length` 仅决定紧随空行的字节流范围。
>
> 读取字节流时：完整下载按 `size` 字节读，范围下载按请求的 `length` 字节读（**不要**用 OK 头里的 `size`，那是整文件大小）。任何情况下都不可使用 `readLine()` 读取字节流部分。

### 7.6　错误响应

当请求的文件不存在或发生其他错误时，Peer 返回：

```
ERROR
reason: <错误原因>

```

---

## 八、提交要求

在 Gradescope 上提交，将 `src/` 目录打包成 zip 上传：

```bash
zip -r submission.zip src/
```

- 提交内容只需包含 `src/` 目录，不要包含 `out/`、`p2p-tests.jar` 等其他文件
- 目录结构须与框架一致，入口类名和启动参数不可修改
- 代码须能通过以下命令编译，不依赖任何构建工具：`javac -d out/production src/common/*.java src/tracker/*.java src/peer/*.java src/client/*.java`
- 提交前在本地测试多客户端并发场景
- ⚠️：为避免测试资源抢占，请不要高强度提交代码

---

## 九、评分方式

本次作业共 100 分，分为功能测试和性能测试两个部分，独立评分。

### 9.1　功能测试（80 分）

测试脚本直接与你的系统通信，按协议判断结果是否正确，每个用例通过 / 不通过独立给分。

| 测试用例                           | 分值  |
| ---------------------------------- | ----- |
| Peer 注册成功                      | 10 分 |
| 查询存在的文件                     | 10 分 |
| 查询不存在的文件（返回 NOT_FOUND） | 10 分 |
| 单文件传输，MD5 校验正确           | 20 分 |
| 多 Peer 注册，查询返回正确节点     | 15 分 |
| 分块并发下载，合并后 MD5 正确      | 15 分 |


### 9.2　性能测试（20 分）

截止日期后对所有提交按全班相对排名换算分数，五个指标独立排名后加权合成。提交后可在 Gradescope 上看到自己的性能指标参考值。

| 指标                  | 权重 | 测试方式                                     |
| --------------------- | ---- | -------------------------------------------- |
| 单客户端吞吐量        | 25%  | 单 Client 下载 100MB 文件，测 MB/s           |
| 高并发压力            | 25%  | 100 个 Client 同时下载，测总完成时间与成功率 |
| 分块下载加速比        | 20%  | 双 Peer 分块下载时间 / 单 Peer 下载时间      |
| 并发 Tracker 查询延迟 | 20%  | 100 线程并发查询，测 p99 延迟                |
| 大文件传输            | 10%  | 传输 1GB 文件，验证 MD5                      |

---

## 十、注意事项

**协议规范**
- 所有字符编码统一使用 UTF-8
- 换行符统一使用 `\n`，不接受 `\r\n`
- 每条消息以连续两个 `\n` 结尾（即一个空行）
- 文件字节流与文本头部之间以空行分隔，接收方须按 `size` 字段读取固定字节数，不得使用 `readLine()` 读取字节流部分

**实现限制**
- 不允许使用第三方库，只能使用 Java 标准库
- 所有网络通信必须基于 TCP
- 协议格式必须严格遵守，测试脚本不会容忍格式偏差

**建议**
- 先在本地启动多个进程验证单文件传输正确性，再测试并发场景
- `FileTransfer` 建议使用带缓冲的流式传输，缓冲区大小会影响传输性能，需自行测试调优
- Tracker 需要同时处理多个并发连接，并发模型由你自己设计
- 大文件传输时注意内存占用，不要一次性将文件读入内存

---

## 十一、学术诚信

本次作业须独立完成。提交的代码将使用进行相似度检测，高相似度的提交将进一步接受人工审查和口头质询。

允许参考公开资料和文档，但不允许直接复制他人代码。