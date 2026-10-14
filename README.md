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

然后在本机打开 `http://127.0.0.1:8787/`。端口占用时可用 `--web --webPort=8788`。页面可配置活动 ID、起始排名、奖池、奖励币种、个人封顶、多个账户交易量；高级设置包含每页条数、最大页数、请求间隔、接口字段映射和导出目录。多个账户交易量以空格分隔，例如 `12,572.05046 20,180.18002`，数字可带千分位逗号。完整抓取后会按榜单交易量推算每个输入值的名次，无需奖池；填写奖池后另显示理论奖励。抓取进度与结果在页面展示；结果同时显示币安接口提供的榜单更新时间、原始抓取时间、数据来源和 Telegram 通知状态（北京时间），可直接下载 JSON、CSV。参数错误会在提交按钮和状态区显示，修改参数后旧结果暂时隐藏，避免误认为已按新参数重新计算。一次只运行一个任务。

### 本地榜单缓存

完整榜单按 `resourceId / Binance updatedTime` 保存在启动目录的 `cache/` 下；服务器为 `/var/lib/binance-leaderboard/cache/`。例如榜单更新时间为北京时间 `2026-10-13 11:59:59` 时，文件名使用对应的 UTC 毫秒时间戳。仅保存完整、通过汇总核对、且接口提供更新时间的全榜快照；不保存页面输入的个人交易量或奖池。重复查询同一活动时直接读取本地最新快照，按当前起始排名、账户交易量和奖池重新计算，不请求 Binance。页面会显示“本地缓存”；命令行可用 `--refresh=true`，页面可勾选“重新抓取币安榜单”以强制更新。

不访问 Binance 时无法自动知道其榜单是否更新。需要最新数据时主动重新抓取；如果抓取不完整，新数据不会覆盖完整缓存。缓存文件只允许本机运行账户读写。

### Telegram 通知

可选。设置 `TELEGRAM_BOT_TOKEN` 和 `TELEGRAM_CHAT_ID` 后，每次计算完成（包括从缓存重新计算）都会发送一条结果摘要；发送失败不会丢失计算结果，页面会显示通知状态。机器人凭据只从进程环境变量读取，不进页面、缓存、导出文件或 Git。

1. 在 Telegram 打开 [@BotFather](https://t.me/BotFather)，发送 `/newbot`，按提示设置名称和用户名，保存生成的 Token。向新机器人发送 `/start`。
2. 在运行工具的终端安全设置 `TELEGRAM_BOT_TOKEN`，执行 `java -jar target/binance-leaderboard-stat.jar --telegram-chat-id`。命令只打印从 Telegram `getUpdates` 找到的 Chat ID，不打印 Token。若显示没有消息，先向机器人再发一条消息后重试。机器人若已有 webhook，需先关闭 webhook 才能使用 `getUpdates`。
3. 设置 `TELEGRAM_CHAT_ID` 并重启程序。服务器 systemd 可在 root 专用的 `/etc/binance-leaderboard/telegram.env` 写入两行 `TELEGRAM_BOT_TOKEN=...` 与 `TELEGRAM_CHAT_ID=...`，权限设为 `0600` 后执行 `systemctl restart binance-leaderboard`。服务文件会读取此文件；文件不存在时通知功能保持关闭。

服务器也提供交互式脚本 `bash /opt/binance-leaderboard/deploy/setup-telegram.sh`：在终端无回显地输入 Token，脚本查询 Chat ID，写入 root 专用环境文件并重启服务。不要把 Token 放在命令行参数或聊天消息中。

请勿把 Token 发到聊天、命令参数或 Git；如果泄露，通过 @BotFather 重新生成。

### 每小时双活动报告

`java -jar target/binance-leaderboard-stat.jar --scheduled-report` 会重新抓取主榜 `100030536`（现货交易量锦标赛第四期，指定交易对 ZEC/USDC、USUAL/USDT；北京时间 2026-10-16 18:00 结束）和 `100030286`（MET/USDC；北京时间 2026-10-15 18:00 结束），按第 1001 名及以后各自的完整交易量计算 **80 BNB** 比例奖池与 **0.05 BNB** 个人封顶，并把两项结果合并为一条 Telegram 消息。消息列出完整交易对、结束时间与动态倒计时、榜单更新时间、第 1000 名交易量、范围人数、交易量合计、每 1,000 USD 交易量对应的理论 BNB 与达到个人封顶所需交易量。Telegram 中点击交易对文字可直接打开对应币安现货交易页。若某项抓取不完整，会明确标记且不输出该项奖励估算。定时模式每次强制请求币安，完整快照另存于自己的 `cache/`；不使用页面的旧缓存，也不读取页面中输入的个人交易量。

服务器使用 `deploy/binance-leaderboard-hourly.service` 和 `.timer`，按北京时间每小时的第 10 分钟运行。部署后以 `systemctl list-timers binance-leaderboard-hourly.timer` 查看下次执行时间，以 `journalctl -u binance-leaderboard-hourly.service` 查看执行状态。该任务复用 `/etc/binance-leaderboard/telegram.env` 中的机器人凭据，数据目录为 `/var/lib/binance-leaderboard-hourly`。

### 币安广场每小时发帖

配置广场专用的 **Posting API Key** 后，同一个定时任务会把完整的双活动报告作为纯文本帖发布到币安广场，并附上三个交易对各自的现货交易页直链。它与现货交易 API Key 不同，仅用于发帖。服务器以 root 运行 `bash /opt/binance-leaderboard/deploy/setup-square.sh`，在无回显提示下输入密钥；脚本将其写入权限 `0600` 的 `/etc/binance-leaderboard/square.env`。密钥不进命令参数、Git、榜单缓存或 Telegram 文本。未配置时跳过广场，Telegram 照常发送。

每次完整抓取后至多发一帖；同一北京时间小时内手动重跑不会重复发帖。抓取不完整时只通知 Telegram。两个活动都结束后不再发广场帖。发布记录和链接保存在独立状态目录 `/var/lib/binance-leaderboard-hourly/square-posts/`。广场接口返回错误时任务会在日志中记录错误码，下一小时仍会重试。创建密钥的入口：[币安广场创作者中心](https://www.binance.com/square/creator-center/home)。

服务只监听 `127.0.0.1`，并检查页面请求来源。Cookie、CSRF Token、UUID 不在页面输入或存储，仍由启动进程从环境变量读取。奖池和个人封顶默认留空，页面不会把示例的 40,000 USDC 或 30 USDC 当作真实活动规则。关闭运行程序的终端即可停止页面服务。

### 服务器子路径

在反向代理下可用 `--webOrigin=https://8.216.38.70` 接受该 HTTPS 页面的同源请求；静态资源和 API 使用相对路径，因此可挂载在 `/leaderboard/`。仓库的 `deploy/` 包含 systemd 服务和 Nginx 路由示例。服务器必须对整个子路径应用认证；示例通过交易控制台的登录会话验证。服务仍只监听 `127.0.0.1`，并以独立的受限 systemd 用户运行，结果写入 `/var/lib/binance-leaderboard`。

## 运行

```bash
java -jar target/binance-leaderboard-stat.jar \
  --resourceId=100027837 --startRank=1001 \
  --rewardPool=40000 --rewardUnit=USDC --maxReward=30
```

`--resourceId` 必填；`--startRank` 默认 1001，`--pageSize` 默认 100，`--maxPages` 默认 1000，`--delayMs` 默认 450。多个账户的交易量用 `--volumes='12,572.05046 20,180.18002'`，旧版英文逗号列表仍兼容。默认在当前目录写 `result-<resourceId>.json` 与 `.csv`，可用 `--outputDir` 改目录。`INCOMPLETE` 的 CSV 首行有醒目标记，JSON 有完整性状态及原因；命令退出码为 2。`--refresh=true` 跳过本地缓存。

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
