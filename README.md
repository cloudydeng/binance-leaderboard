# Binance 交易赛排行榜统计工具

Java 17 本地工具，提供命令行与浏览器配置页面。读取指定活动的 Binance 排行榜，按实际排名筛选并累计交易量；提供奖池参数时输出理论奖励估算。2026-10-07 已对活动 `100027837` 完成公开请求验证：榜单路径为 `data.resourceSummaryList.data`，排名字段 `sequence`，交易量字段 `tradingVolume`，页码从 1 开始，`pageSize=100` 可用。交易量单位及活动奖励规则仍需核对。程序遇到不明确的字段、页间重叠或请求失败会标记 `INCOMPLETE`，不会把部分数据当成最终统计。

## 构建

需要 JDK 17+ 和 Maven 3.9+：

```bash
mvn clean test
mvn clean package
java -jar target/binance-leaderboard-stat.jar --help
```

## 浏览器配置页面

```bash
java -jar target/binance-leaderboard-stat.jar --web
```

然后在本机打开 `http://127.0.0.1:8787/`。端口占用时可用 `--web --webPort=8788`。页面可配置活动 ID、起始排名、奖池、奖励币种、个人封顶、多个账户交易量；高级设置包含每页条数、最大页数、请求间隔、接口字段映射和导出目录。抓取进度与结果在页面展示，可直接下载 JSON、CSV。一次只运行一个抓取任务。

服务只监听 `127.0.0.1`，并检查页面请求来源。Cookie、CSRF Token、UUID 不在页面输入或存储，仍由启动进程从环境变量读取。奖池和个人封顶默认留空，页面不会把示例的 40,000 USDC 或 30 USDC 当作真实活动规则。关闭运行程序的终端即可停止页面服务。

## 运行

```bash
java -jar target/binance-leaderboard-stat.jar \
  --resourceId=100027837 --startRank=1001 \
  --rewardPool=40000 --rewardUnit=USDC --maxReward=30
```

`--resourceId` 必填；`--startRank` 默认 1001，`--pageSize` 默认 100，`--maxPages` 默认 1000，`--delayMs` 默认 450。多个账户的交易量用 `--volumes=14048.0258,18545.05623`。默认在当前目录写 `result-<resourceId>.json` 与 `.csv`，可用 `--outputDir` 改目录。`INCOMPLETE` 的 CSV 首行有醒目标记，JSON 有完整性状态及原因；命令退出码为 2。

如果返回结构不能唯一识别，需要根据**实际响应**指定映射：

```bash
java -jar target/binance-leaderboard-stat.jar \
  --resourceId=100027837 \
  --entriesPath=data.resourceSummaryList.data --rankField=sequence --volumeField=tradingVolume
```

自动识别只接受排名字段 `rank` / `ranking` / `sequence` 和交易量字段 `tradeVolume` / `tradingVolume` / `totalTradeVolume`。若其他活动使用 `volume` 等字段，必须显式指定 `--volumeField` 并人工核对其含义与单位。结构错误只展示字段名和类型，不展示整份响应。出现 `hitRisk=true` 或未知资格字段时工具停止统计，需要先核实该活动计奖规则。

## 身份信息

公开请求优先。需要登录时，仅通过环境变量 `BINANCE_COOKIE`、`BINANCE_CSRF_TOKEN`、`BINANCE_UUID` 传入。不要放进命令参数、项目文件或日志。HTTP 401/403 会立即停止；验证码或风控拦截需人工处理。客户端只访问固定的 `https://www.binance.com` 接口，不跟随跳转。

## 完整性与解释

- 从第一页顺序读取，通过记录位置、排名和交易量检查漏页及页间重叠；相同交易量的合法并列排名予以保留。接口 `userId` 可能已脱敏并发生重复，不能用来唯一识别用户。服务端限制页大小时从第一页按新页大小重抓。
- 可信总人数、`hasMore=false` 或无元数据时连续两次空页可结束；`maxPages` 达限、请求或解析失败均标记 `INCOMPLETE`。
- `rankFilteredCount` 仅是排名范围人数。`qualificationStatus=UNVERIFIED_RULES` 表示是否实际合格仍须核对活动条款。
- 对已验证活动，工具还比对全榜人数、交易量与接口的 `eligibleUserCount`、`eligibleTradingVolume`。接口返回的 `pageIndex` 固定为 1，因此不以它判断页码。
- 奖励是比例计算的理论估算；活动币种、奖池适用排名、封顶后的重新分配及官方结算口径需用户核对。程序不假设封顶余额重新分配。
- 该 `bapi/growth/...` 网页接口已做实际请求验证，但未证实属于公开稳定 API，可能变更或限制访问。

## 常见错误

- `HTTP 401/403`：Cookie 失效或接口访问受限。检查环境变量并手工处理账户验证。
- `HTTP 429`：程序最多重试 3 次，尊重 `Retry-After`（最长 60 秒）。
- `无法唯一识别排行榜数组/volumeField`：用真实响应确认字段后传入显式映射，切勿把奖励字段当交易量。
- `INCOMPLETE`：查看 JSON `issues`。部分数据仅供排障，不应作为最终奖池分母。
