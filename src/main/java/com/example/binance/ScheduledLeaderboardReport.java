package com.example.binance;

import com.example.binance.client.BinanceLeaderboardClient;
import com.example.binance.config.Options;
import com.example.binance.model.LeaderboardStatistics;
import com.example.binance.service.CachedLeaderboardService;
import com.example.binance.service.LeaderboardSnapshotCache;
import com.example.binance.service.LeaderboardStatisticsService;
import com.example.binance.telegram.TelegramNotifier;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** One fresh, combined Telegram report for the two current main reward leaderboards. */
final class ScheduledLeaderboardReport {
    record Campaign(String name, String resourceId) {}
    record Section(Campaign campaign, LeaderboardStatistics result, String error) {}

    private static final List<Campaign> CAMPAIGNS = List.of(
            new Campaign("现货锦标赛第四期（ZEC/USUAL）", "100030536"),
            new Campaign("MET", "100030286"));
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
            return run(new BinanceLeaderboardClient()::fetchPage, notifier, Path.of("."), 450);
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
        notifier.sendText(format(Instant.now(), sections));
        System.out.println("已发送两项活动的合并报告；完整性：" + (failed ? "部分失败" : "完整"));
        return failed ? 2 : 0;
    }

    static String format(Instant checkedAt, List<Section> sections) {
        StringBuilder out = new StringBuilder("Binance 双活动榜单报告\n")
                .append("计算时间：").append(BEIJING.format(checkedAt)).append("（北京时间）\n")
                .append("规则：第 1001 名及以后瓜分 80 BNB；单人封顶 0.05 BNB\n");
        for (Section section : sections) {
            out.append("\n").append(section.campaign().name()).append(" · ")
                    .append(section.campaign().resourceId()).append("\n");
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

    private static String beijing(String iso) {
        if (iso == null) return "接口未提供";
        try { return BEIJING.format(Instant.parse(iso)) + "（北京时间）"; }
        catch (RuntimeException e) { return "时间格式无效"; }
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
