package com.example.binance;

import com.example.binance.client.BinanceLeaderboardClient;
import com.example.binance.config.Options;
import com.example.binance.model.LeaderboardStatistics;
import com.example.binance.service.LeaderboardStatisticsService;
import com.example.binance.util.ResultWriter;
import com.example.binance.web.LocalWebServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class BinanceLeaderboardApp {
    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println(Options.help() + "\n页面模式: java -jar target/binance-leaderboard-stat.jar --web [--webPort=8787] [--webOrigin=https://域名]");
            return 0;
        }
        if (args.length > 0 && args[0].equals("--web")) return runWeb(args);
        Options options;
        try { options = Options.parse(args); }
        catch (IllegalArgumentException e) { System.err.println("参数错误：" + e.getMessage() + "\n" + Options.help()); return 1; }
        try {
            BinanceLeaderboardClient client = new BinanceLeaderboardClient();
            LeaderboardStatistics result = new LeaderboardStatisticsService(client::fetchPage).collect(options);
            new ResultWriter().write(result, options.outputDir());
            print(result);
            return result.completeness().equals("COMPLETE") ? 0 : 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("抓取被中断，结果未完成");
            return 2;
        } catch (IOException e) {
            System.err.println("结果写入失败：" + e.getMessage());
            return 2;
        }
    }

    private static int runWeb(String[] args) {
        int port = 8787;
        String webOrigin = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--webPort=")) {
                try { port = Integer.parseInt(args[i].substring("--webPort=".length())); }
                catch (NumberFormatException e) { System.err.println("webPort 必须是整数"); return 1; }
            } else if (args[i].startsWith("--webOrigin=")) {
                webOrigin = args[i].substring("--webOrigin=".length());
            } else {
                System.err.println("页面模式只接受 --webPort 和 --webOrigin"); return 1;
            }
        }
        try {
            BinanceLeaderboardClient client = new BinanceLeaderboardClient();
            LocalWebServer web = new LocalWebServer(port, client::fetchPage, Path.of("."), webOrigin);
            Runtime.getRuntime().addShutdownHook(new Thread(web::close));
            web.start();
            System.out.println("本地配置页面已启动：" + web.url());
            new CountDownLatch(1).await();
            return 0;
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("页面启动失败：" + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static void print(LeaderboardStatistics r) {
        System.out.println("活动 ID: " + r.resourceId());
        System.out.println("排名范围: " + r.startRank() + "–" + (r.maxRank() == null ? "未知" : r.maxRank()));
        System.out.println("完整性: " + r.completeness() + (r.completeness().equals("INCOMPLETE") ? "（未完成，不可作为最终数）" : ""));
        for (String issue : r.issues()) System.out.println("原因: " + issue);
        System.out.println("请求页数: " + r.pagesRequested() + "；排名范围人数: " + r.rankFilteredCount());
        System.out.println("交易量合计: " + plain(r.totalVolume()) + "；人均: " + plain(r.averageVolume()));
        if (r.rank1000Volume() != null) System.out.println("第 1000 名交易量: " + plain(r.rank1000Volume()));
        System.out.println("资格规则: 未核实；请核对活动币种、交易量单位、奖池归属与计奖资格。");
        if (r.rewardEstimate() != null) {
            var reward = r.rewardEstimate();
            System.out.println("理论/预估奖励（" + reward.rewardUnit() + "）: 每 1,000 交易量 "
                    + plain(reward.rewardPer1000()) + "；每 10,000 交易量 " + plain(reward.rewardPer10000()));
            if (reward.capVolume() != null) System.out.println("达到个人封顶所需交易量: " + plain(reward.capVolume()));
            for (int i = 0; i < reward.accounts().size(); i++) {
                var account = reward.accounts().get(i);
                System.out.println("账户 " + (i + 1) + "：交易量 " + plain(account.volume()) + "，理论 "
                        + plain(account.raw()) + "，封顶后 " + plain(account.capped()));
            }
            System.out.println("实际分配可能受资格、封顶及官方结算规则影响；不假设封顶余额会重新分配。");
        }
        System.out.println("已保存 result-" + r.resourceId() + ".json / .csv");
    }
    private static String plain(BigDecimal x) { return x.stripTrailingZeros().toPlainString(); }
}
