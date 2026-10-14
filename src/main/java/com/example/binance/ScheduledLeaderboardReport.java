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
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    private ScheduledLeaderboardReport() {}

    static int run() {
        try {
            TelegramNotifier notifier = TelegramNotifier.fromEnvironment();
            if (notifier == null) {
                System.err.println("定时报告未配置 Telegram Bot Token 和 Chat ID");
                return 2;
            }
            return run(new BinanceLeaderboardClient()::fetchPage, notifier,
                    BinanceSquarePublisher.fromEnvironment(), Path.of("."), 450);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("定时报告被中断");
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("定时报告发送失败：请核对 Telegram 配置和网络");
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
        var sections = new ArrayList<Section>();
        var cache = new LeaderboardSnapshotCache(workDir.resolve("cache"));
        boolean failed = false;
        for (Campaign campaign : CAMPAIGNS) {
            Options options = Options.parse(new String[]{
                    "--resourceId=" + campaign.resourceId(), "--startRank=1001", "--pageSize=100",
                    "--delayMs=" + delayMs, "--rewardPool=80", "--rewardUnit=BNB",
                    "--maxReward=0.05", "--refresh=true", "--outputDir=" + workDir});
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
        String report = format(checkedAt, sections);
        notifier.sendTextWithLinks(report, CAMPAIGNS.stream()
                .flatMap(c -> c.pairs().stream())
                .map(p -> new TelegramNotifier.TextLink(p.label(), p.url()))
                .toList());
        System.out.println("已发送两项活动的合并报告；完整性：" + (failed ? "部分失败" : "完整"));
        if (square != null && !failed && CAMPAIGNS.stream().anyMatch(c -> checkedAt.isBefore(c.endsAt()))) {
            Path marker = workDir.resolve("square-posts")
                    .resolve(DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneId.of("Asia/Shanghai"))
                            .format(checkedAt) + ".txt");
            if (Files.exists(marker)) {
                System.out.println("本小时币安广场报告已发布，跳过重复发帖");
            } else {
                try {
                    var published = square.publish(formatSquare(report));
                    Files.createDirectories(marker.getParent());
                    Files.writeString(marker, published.link() == null ? "已提交\n" : published.link() + "\n",
                            StandardOpenOption.CREATE_NEW);
                    System.out.println("已发布币安广场报告：" +
                            (published.link() == null ? "已提交，未返回链接" : published.link()));
                } catch (IOException e) {
                    System.err.println("币安广场发布失败：" + e.getMessage());
                    return 2;
                }
            }
        }
        return failed ? 2 : 0;
    }

    static String format(Instant checkedAt, List<Section> sections) {
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
            if (result == null) {
                out.append(section.error() == null ? "计算失败" : section.error()).append("\n");
                continue;
            }
            out.append("榜单更新：").append(beijing(result.leaderboardUpdatedAt())).append("\n")
                    .append("第 1001–").append(result.maxRank() == null ? "?" : result.maxRank())
                    .append(" 名：").append(result.rankFilteredCount()).append(" 人\n");
            if (!"COMPLETE".equals(result.completeness())) {
                out.append("抓取不完整，暂不计算奖励");
                if (!result.issues().isEmpty()) out.append("：").append(shortReason(result.issues().get(0)));
                out.append("\n");
                continue;
            }
            out.append("交易量合计：").append(decimal(result.totalVolume())).append(" USD\n");
            var reward = result.rewardEstimate();
            if (reward == null) {
                out.append("该范围暂无可计算的交易量\n");
                continue;
            }
            out.append("每 1,000 USD 交易量约：")
                    .append(rounded(reward.rewardPer1000(), 8)).append(" BNB\n")
                    .append("达到 0.05 BNB 约需：")
                    .append(rounded(reward.capVolume(), 2)).append(" USD 交易量\n");
        }
        out.append("\n仅按榜单交易量估算；实际资格与奖励以币安结算为准。");
        return out.toString();
    }

    static String formatSquare(String report) {
        StringBuilder out = new StringBuilder(report).append("\n\n现货交易页面：\n");
        for (Campaign campaign : CAMPAIGNS) {
            for (TradingPair pair : campaign.pairs()) {
                out.append(pair.label()).append(" ").append(pair.url()).append("\n");
            }
        }
        return out.toString();
    }

    private static String beijing(String iso) {
        if (iso == null) return "接口未提供";
        try { return BEIJING.format(Instant.parse(iso)) + "（北京时间）"; }
        catch (RuntimeException e) { return "时间格式无效"; }
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

    private static String decimal(BigDecimal value) {
        if (value == null) return "—";
        String plain = value.toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        return whole.replaceAll("\\B(?=(\\d{3})+(?!\\d))", ",") + (dot < 0 ? "" : plain.substring(dot));
    }
}
