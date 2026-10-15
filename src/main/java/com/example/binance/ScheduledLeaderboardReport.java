package com.example.binance;

import com.example.binance.client.BinanceLeaderboardClient;
import com.example.binance.config.Options;
import com.example.binance.model.LeaderboardStatistics;
import com.example.binance.service.CachedLeaderboardService;
import com.example.binance.service.LeaderboardSnapshotCache;
import com.example.binance.service.LeaderboardStatisticsService;
import com.example.binance.telegram.TelegramNotifier;
import com.example.binance.square.BinanceSquarePublisher;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;

/** One fresh, combined Telegram report for the two current main reward leaderboards. */
final class ScheduledLeaderboardReport {
    record TradingPair(String label, String symbol) {
        String url() { return "https://www.binance.com/zh-CN/trade/" + symbol; }
    }
    record Campaign(String name, String resourceId, List<TradingPair> pairs, Instant endsAt) {}
    record Section(Campaign campaign, LeaderboardStatistics result, String error) {}

    private static final List<Campaign> CAMPAIGNS = List.of(
            new Campaign("现货交易量锦标赛第四期", "100030536",
                    List.of(new TradingPair("ZEC/USDC", "ZEC_USDC"),
                            new TradingPair("USUAL/USDT", "USUAL_USDT")),
                    Instant.parse("2026-10-16T10:00:00Z")),
            new Campaign("MET 交易锦标赛", "100030286",
                    List.of(new TradingPair("MET/USDC", "MET_USDC")),
                    Instant.parse("2026-10-15T10:00:00Z")));
    private static final DateTimeFormatter BEIJING = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final DateTimeFormatter SHORT_BEIJING = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));
    // Regular-user spot fee (0.10%) after the 25% discount for paying with BNB.
    private static final BigDecimal BNB_SPOT_FEE_RATE = new BigDecimal("0.00075");

    private ScheduledLeaderboardReport() {}

    static int run() {
        try {
            TelegramNotifier notifier = TelegramNotifier.fromEnvironment();
            if (notifier == null) {
                System.err.println("定时报告未配置 Telegram Bot Token 和 Chat ID");
                return 2;
            }
            return runIfUpdated(new BinanceLeaderboardClient()::fetchPage, notifier,
                    BinanceSquarePublisher.fromEnvironment(), Path.of("."), 450, Clock.systemUTC());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("定时报告被中断");
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("定时报告检查或发送失败：" + e.getMessage());
        }
        return 2;
    }

    static int run(LeaderboardStatisticsService.PageSource source, TelegramNotifier notifier,
                   Path workDir, int delayMs) throws IOException, InterruptedException {
        return run(source, notifier, null, workDir, delayMs);
    }

    static int run(LeaderboardStatisticsService.PageSource source, TelegramNotifier notifier,
                   BinanceSquarePublisher square, Path workDir, int delayMs)
            throws IOException, InterruptedException {
        return run(source, notifier, square, workDir, delayMs, Clock.systemUTC());
    }

    static int run(LeaderboardStatisticsService.PageSource source, TelegramNotifier notifier,
                   BinanceSquarePublisher square, Path workDir, int delayMs, Clock clock)
            throws IOException, InterruptedException {
        return run(source, notifier, square, workDir, delayMs, clock, Map.of());
    }

    /** Timer poll: one light first-page check per active campaign; a later timer tick retries unchanged data. */
    static int runIfUpdated(LeaderboardStatisticsService.PageSource source, TelegramNotifier notifier,
                            BinanceSquarePublisher square, Path workDir, int delayMs, Clock clock)
            throws IOException, InterruptedException {
        Instant now = clock.instant();
        var cache = new LeaderboardSnapshotCache(workDir.resolve("cache"));
        Properties reported = new Properties();
        Path reportedFile = workDir.resolve("last-reported-updates.properties");
        if (Files.exists(reportedFile)) {
            try (var input = Files.newInputStream(reportedFile)) { reported.load(input); }
        }
        String reportHour = DateTimeFormatter.ofPattern("yyyyMMddHH")
                .withZone(ZoneId.of("Asia/Shanghai")).format(now);
        if (reportHour.equals(reported.getProperty("reportHour"))) {
            System.out.println("本小时已发送榜单报告，等待下小时检查");
            return 0;
        }
        Map<String, Instant> priorUpdates = new HashMap<>();
        boolean hasActiveCampaign = false;
        for (Campaign campaign : CAMPAIGNS) {
            if (!now.isBefore(campaign.endsAt())) continue;
            hasActiveCampaign = true;
            String reportedTime = reported.getProperty(campaign.resourceId());
            Instant previous = reportedTime == null
                    ? cache.latest(campaign.resourceId()).map(LeaderboardStatistics::leaderboardUpdatedAt)
                            .map(Instant::parse).orElse(Instant.EPOCH)
                    : Instant.parse(reportedTime);
            priorUpdates.put(campaign.resourceId(), previous);
            JsonNode firstPage = source.fetch(campaign.resourceId(), 1, 10);
            JsonNode updatedNode = firstPage.path("data").path("updatedTime");
            Instant current;
            try { current = Instant.ofEpochMilli(Long.parseLong(updatedNode.asText())); }
            catch (RuntimeException e) {
                System.out.println(campaign.name() + " 未提供有效榜单更新时间，等待下次检查");
                return 0;
            }
            if (!current.isAfter(previous)) {
                System.out.println(campaign.name() + " 榜单尚未更新（" + beijing(previous.toString()) + "），等待下次检查");
                return 0;
            }
        }
        if (!hasActiveCampaign) {
            System.out.println("活动均已结束，不再发送定时报告");
            return 0;
        }
        return run(source, notifier, square, workDir, delayMs, clock, priorUpdates);
    }

    private static int run(LeaderboardStatisticsService.PageSource source, TelegramNotifier notifier,
                           BinanceSquarePublisher square, Path workDir, int delayMs, Clock clock,
                           Map<String, Instant> priorUpdates)
            throws IOException, InterruptedException {
        var sections = new ArrayList<Section>();
        var cache = new LeaderboardSnapshotCache(workDir.resolve("cache"));
        Map<String, LeaderboardStatistics> previousReports = loadPreviousReports(workDir, cache);
        Instant startedAt = clock.instant();
        boolean failed = false;
        for (Campaign campaign : CAMPAIGNS) {
            Options options = campaignOptions(campaign, workDir, delayMs,
                    startedAt.isBefore(campaign.endsAt()));
            try {
                var outcome = new CachedLeaderboardService(source, cache, progress -> {})
                        .collect(options);
                var result = outcome.result();
                sections.add(new Section(campaign, result, null));
                if (!"COMPLETE".equals(result.completeness())) failed = true;
            } catch (RuntimeException e) {
                sections.add(new Section(campaign, null, "计算异常：" + e.getClass().getSimpleName()));
                failed = true;
            }
        }
        Instant checkedAt = clock.instant();
        if (!priorUpdates.isEmpty() && failed) {
            System.out.println("完整榜单抓取失败，等待下次检查");
            return 2;
        }
        for (Section section : sections) {
            Instant previous = priorUpdates.get(section.campaign().resourceId());
            if (previous == null) continue;
            LeaderboardStatistics result = section.result();
            if (result == null || !"COMPLETE".equals(result.completeness()) ||
                    result.leaderboardUpdatedAt() == null) return 2;
            if (!Instant.parse(result.leaderboardUpdatedAt()).isAfter(previous)) {
                System.out.println(section.campaign().name() + " 完整抓取期间榜单未更新，等待下次检查");
                return 0;
            }
        }
        String report = format(checkedAt, sections, previousReports);
        notifier.sendTextWithLinks(report, CAMPAIGNS.stream()
                .flatMap(c -> c.pairs().stream())
                .map(p -> new TelegramNotifier.TextLink(p.label(), p.url()))
                .toList());
        if (!failed) saveReportedUpdates(workDir, sections, checkedAt);
        System.out.println("已发送两项活动的合并报告；完整性：" + (failed ? "部分失败" : "完整"));
        if (square != null && !failed) {
            for (Section section : sections) {
                if (!checkedAt.isBefore(section.campaign().endsAt())) continue;
                Path marker = workDir.resolve("square-posts")
                        .resolve(DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneId.of("Asia/Shanghai"))
                                .format(checkedAt) + "-" + section.campaign().resourceId() + ".txt");
                if (Files.exists(marker)) {
                    System.out.println(section.campaign().name() + " 本小时币安广场帖子已发布，跳过");
                    continue;
                }
                try {
                    var published = square.publish(formatSquare(checkedAt, section,
                            previousReports.get(section.campaign().resourceId())));
                    Files.createDirectories(marker.getParent());
                    Files.writeString(marker, published.link() == null ? "已提交\n" : published.link() + "\n",
                            StandardOpenOption.CREATE_NEW);
                    System.out.println("已发布 " + section.campaign().name() + " 币安广场帖子：" +
                            (published.link() == null ? "已提交，未返回链接" : published.link()));
                } catch (IOException e) {
                    System.err.println(section.campaign().name() + " 币安广场发布失败：" + e.getMessage());
                    return 2;
                }
            }
        }
        return failed ? 2 : 0;
    }

    private static void saveReportedUpdates(Path workDir, List<Section> sections, Instant reportedAt) throws IOException {
        Properties reported = new Properties();
        reported.setProperty("reportHour", DateTimeFormatter.ofPattern("yyyyMMddHH")
                .withZone(ZoneId.of("Asia/Shanghai")).format(reportedAt));
        for (Section section : sections) {
            if (section.result() != null && section.result().leaderboardUpdatedAt() != null)
                reported.setProperty(section.campaign().resourceId(), section.result().leaderboardUpdatedAt());
        }
        Path destination = workDir.resolve("last-reported-updates.properties");
        Path temporary = Files.createTempFile(workDir, ".last-reported-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) { reported.store(output, "Last Telegram report by resource ID"); }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Map<String, LeaderboardStatistics> loadPreviousReports(
            Path workDir, LeaderboardSnapshotCache cache) throws IOException {
        Path state = workDir.resolve("last-reported-updates.properties");
        if (!Files.exists(state)) return Map.of();
        Properties reported = new Properties();
        try (var input = Files.newInputStream(state)) { reported.load(input); }
        Map<String, LeaderboardStatistics> previous = new HashMap<>();
        for (Campaign campaign : CAMPAIGNS) {
            String timestamp = reported.getProperty(campaign.resourceId());
            if (timestamp == null) continue;
            try {
                Options options = campaignOptions(campaign, workDir, 0, false);
                cache.at(campaign.resourceId(), Instant.parse(timestamp))
                        .map(full -> LeaderboardStatisticsService.recalculate(options, full, true))
                        .ifPresent(result -> previous.put(campaign.resourceId(), result));
            } catch (IOException | RuntimeException e) {
                System.err.println(campaign.name() + " 上次推送快照不可用，本次不显示该活动增量");
            }
        }
        return previous;
    }

    private static Options campaignOptions(Campaign campaign, Path workDir, int delayMs, boolean refresh) {
        return Options.parse(new String[]{
                "--resourceId=" + campaign.resourceId(), "--startRank=1001", "--pageSize=100",
                "--delayMs=" + delayMs, "--rewardPool=80", "--rewardUnit=BNB",
                "--maxReward=0.05", "--refresh=" + refresh,
                "--outputDir=" + workDir});
    }

    static String format(Instant checkedAt, List<Section> sections) {
        return format(checkedAt, sections, Map.of());
    }

    static String format(Instant checkedAt, List<Section> sections,
                         Map<String, LeaderboardStatistics> previousReports) {
        StringBuilder out = new StringBuilder("Binance 双活动榜单报告\n")
                .append("计算时间：").append(BEIJING.format(checkedAt)).append("（北京时间）\n")
                .append("规则：第 1001 名及以后瓜分 80 BNB；单人封顶 0.05 BNB\n");
        for (Section section : sections) {
            out.append("\n").append(section.campaign().name()).append(" · ")
                    .append(section.campaign().resourceId()).append("\n")
                    .append("交易对：").append(section.campaign().pairs().stream()
                            .map(TradingPair::label).collect(Collectors.joining("、"))).append("\n")
                    .append("活动结束：").append(BEIJING.format(section.campaign().endsAt()))
                    .append("（北京时间）\n")
                    .append("距离结束：").append(remaining(checkedAt, section.campaign().endsAt()))
                    .append("\n");
            LeaderboardStatistics result = section.result();
            LeaderboardStatistics previous = previousReports.get(section.campaign().resourceId());
            if (result == null) {
                out.append(section.error() == null ? "计算失败" : section.error()).append("\n");
                continue;
            }
            out.append("榜单更新：").append(beijing(result.leaderboardUpdatedAt())).append("\n");
            if (previous != null) out.append("对比上次推送榜单：")
                    .append(beijing(previous.leaderboardUpdatedAt())).append("\n");
            out
                    .append("第 1001–").append(result.maxRank() == null ? "?" : result.maxRank())
                    .append(" 名：").append(result.rankFilteredCount()).append(" 人")
                    .append(countChange(result.rankFilteredCount(), previous)).append("\n");
            if (!"COMPLETE".equals(result.completeness())) {
                out.append("抓取不完整，暂不计算奖励");
                if (!result.issues().isEmpty()) out.append("：").append(shortReason(result.issues().get(0)));
                out.append("\n");
                continue;
            }
            out.append("第 1000 名交易量：")
                    .append(result.rank1000Volume() == null
                            ? "榜单未列出该名次"
                            : decimal(result.rank1000Volume()) + " USD")
                    .append(change(result.rank1000Volume(), previous == null ? null : previous.rank1000Volume(), " USD"))
                    .append("\n");
            if (result.rank1000Volume() != null) {
                out.append("第 1000 名手续费估算（BNB 支付）：约 ")
                        .append(rounded(result.rank1000Volume().multiply(BNB_SPOT_FEE_RATE), 2))
                        .append(" USDT")
                        .append(changeRounded(result.rank1000Volume().multiply(BNB_SPOT_FEE_RATE),
                                previous == null || previous.rank1000Volume() == null ? null
                                        : previous.rank1000Volume().multiply(BNB_SPOT_FEE_RATE), 2, " USDT"))
                        .append("\n");
            }
            out.append("交易量合计：").append(decimal(result.totalVolume())).append(" USD")
                    .append(change(result.totalVolume(), previous == null ? null : previous.totalVolume(), " USD"))
                    .append("\n");
            var reward = result.rewardEstimate();
            if (reward == null) {
                out.append("该范围暂无可计算的交易量\n");
                continue;
            }
            out.append("每 1,000 USD 交易量约：")
                    .append(rounded(reward.rewardPer1000(), 8)).append(" BNB")
                    .append(changeRounded(reward.rewardPer1000(), previous == null || previous.rewardEstimate() == null
                            ? null : previous.rewardEstimate().rewardPer1000(), 8, " BNB"))
                    .append("\n")
                    .append("达到 0.05 BNB 约需：")
                    .append(rounded(reward.capVolume(), 2)).append(" USD 交易量")
                    .append(changeRounded(reward.capVolume(), previous == null || previous.rewardEstimate() == null
                            ? null : previous.rewardEstimate().capVolume(), 2, " USD"))
                    .append("\n");
        }
        out.append("\n手续费按普通用户现货标准费率 0.10%、BNB 抵扣 25%（即交易量 × 0.075%）估算，USD 近似按 USDT 计；USDC 吃单优惠、VIP 费率等可能使实际扣费不同。奖励与资格以币安结算为准。");
        return out.toString();
    }

    static String formatSquare(Instant checkedAt, Section section, LeaderboardStatistics previous) {
        LeaderboardStatistics result = section.result();
        if (result == null || !"COMPLETE".equals(result.completeness()))
            throw new IllegalArgumentException("币安广场只发布完整的活动榜单");
        StringBuilder out = new StringBuilder("币安现货榜单｜")
                .append(section.campaign().pairs().stream()
                        .map(TradingPair::label).collect(Collectors.joining("、")))
                .append("\n")
                .append("活动 ").append(section.campaign().resourceId()).append("｜")
                .append(SHORT_BEIJING.format(checkedAt)).append(" 北京时间\n")
                    .append("第1000名：").append(decimal(result.rank1000Volume())).append(" USD")
                    .append(change(result.rank1000Volume(), previous == null ? null : previous.rank1000Volume(), " USD"))
                    .append("\n")
                    .append("1001名后：").append(result.rankFilteredCount()).append("人")
                    .append(countChange(result.rankFilteredCount(), previous))
                    .append("｜交易量 ").append(rounded(result.totalVolume(), 2)).append(" USD")
                    .append(changeRounded(result.totalVolume(), previous == null ? null : previous.totalVolume(), 2, " USD"))
                    .append("\n");
        if (result.rewardEstimate() != null) {
            out.append("每1000 USD约 ").append(rounded(result.rewardEstimate().rewardPer1000(), 8))
                    .append(" BNB｜封顶约需 ").append(rounded(result.rewardEstimate().capVolume(), 2))
                    .append(" USD\n");
        }
        out.append("榜单更新 ").append(shortBeijing(result.leaderboardUpdatedAt()))
                .append("｜剩余 ").append(remaining(checkedAt, section.campaign().endsAt()))
                .append("\n");
        out.append("\n现货交易：\n");
        for (TradingPair pair : section.campaign().pairs())
            out.append(pair.label()).append(" ").append(pair.url()).append("\n");
        return out.append("理论奖励仅供参考，以币安结算为准。").toString();
    }

    private static String beijing(String iso) {
        if (iso == null) return "接口未提供";
        try { return BEIJING.format(Instant.parse(iso)) + "（北京时间）"; }
        catch (RuntimeException e) { return "时间格式无效"; }
    }

    private static String shortBeijing(String iso) {
        if (iso == null) return "未知";
        try { return SHORT_BEIJING.format(Instant.parse(iso)); }
        catch (RuntimeException e) { return "未知"; }
    }

    private static String remaining(Instant now, Instant endsAt) {
        if (!now.isBefore(endsAt)) return "已结束";
        long seconds = Duration.between(now, endsAt).getSeconds();
        if (seconds < 60) return "不足 1 分钟";
        long minutes = seconds / 60;
        long days = minutes / (24 * 60);
        long hours = minutes / 60 % 24;
        long restMinutes = minutes % 60;
        return (days > 0 ? days + " 天 " : "") + hours + " 小时 " + restMinutes + " 分钟";
    }

    private static String shortReason(String value) {
        return value.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(value.length(), 180));
    }

    private static String rounded(BigDecimal value, int scale) {
        if (value == null) return "—";
        return decimal(value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    private static String countChange(long current, LeaderboardStatistics previous) {
        return previous == null ? "" : "（较上次 " + signed(BigDecimal.valueOf(current - previous.rankFilteredCount())) + " 人）";
    }

    private static String change(BigDecimal current, BigDecimal previous, String unit) {
        return current == null || previous == null ? "" :
                "（较上次 " + signed(current.subtract(previous)) + unit + "）";
    }

    private static String changeRounded(BigDecimal current, BigDecimal previous, int scale, String unit) {
        return current == null || previous == null ? "" : change(
                current.setScale(scale, RoundingMode.HALF_UP),
                previous.setScale(scale, RoundingMode.HALF_UP), unit);
    }

    private static String signed(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + decimal(value.stripTrailingZeros());
    }

    private static String decimal(BigDecimal value) {
        if (value == null) return "—";
        String plain = value.toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        return whole.replaceAll("\\B(?=(\\d{3})+(?!\\d))", ",") + (dot < 0 ? "" : plain.substring(dot));
    }
}
