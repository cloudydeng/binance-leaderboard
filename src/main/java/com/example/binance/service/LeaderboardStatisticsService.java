package com.example.binance.service;

import com.example.binance.config.Options;
import com.example.binance.model.*;
import com.example.binance.util.JsonFieldDetector;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeaderboardStatisticsService {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderboardStatisticsService.class);
    @FunctionalInterface public interface PageSource {
        JsonNode fetch(String resourceId, int pageIndex, int pageSize) throws IOException, InterruptedException;
    }
    private final PageSource source;
    private final JsonFieldDetector parser = new JsonFieldDetector();
    private final Consumer<Progress> progress;

    public record Progress(int pagesRequested, int recordsRead) {}

    public LeaderboardStatisticsService(PageSource source) { this(source, progress -> {}); }
    public LeaderboardStatisticsService(PageSource source, Consumer<Progress> progress) {
        this.source = source;
        this.progress = progress;
    }

    public LeaderboardStatistics collect(Options options) throws InterruptedException {
        int pageSize = options.pageSize();
        int requested = 0;
        List<LeaderboardEntry> all = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        String volumeField = null, entriesPath = null;
        boolean complete = false;
        boolean previousEmpty = false;
        Long expectedTotal = null;
        Long eligibleUserCount = null;
        BigDecimal eligibleTradingVolume = null;
        LeaderboardPage previousPage = null;
        for (int pageIndex = 1; pageIndex <= options.maxPages(); pageIndex++) {
            if (requested >= options.maxPages()) break;
            if (pageIndex > 1 && options.delayMs() > 0) Thread.sleep(options.delayMs());
            JsonNode root;
            try {
                requested++;
                root = source.fetch(options.resourceId(), pageIndex, pageSize);
            } catch (IOException e) {
                if (pageIndex == 1 && pageSize > 1 && e.getMessage() != null && e.getMessage().startsWith("HTTP 400")) {
                    // 仅首请求可安全试小页；后续页失败不能改变页大小接着抓。
                    pageSize = Math.max(1, pageSize / 2);
                    pageIndex = 0;
                    continue;
                }
                issues.add("第 " + pageIndex + " 页请求失败：" + e.getMessage());
                break;
            }
            LeaderboardPage page;
            try { page = parser.parse(root, options, previousPage); }
            catch (IllegalArgumentException e) { issues.add("第 " + pageIndex + " 页解析失败：" + e.getMessage()); break; }
            LOG.info("已抓取第 {} 页，记录 {} 条", pageIndex, page.entries().size());
            if (volumeField == null) { volumeField = page.volumeField(); entriesPath = page.entriesPath(); }
            else if (!volumeField.equals(page.volumeField()) || !entriesPath.equals(page.entriesPath())) {
                issues.add("页间 JSON 字段结构变化"); break;
            }
            if (page.total() != null) {
                if (expectedTotal != null && !expectedTotal.equals(page.total())) { issues.add("榜单总人数在抓取中变化"); break; }
                expectedTotal = page.total();
            }
            if (page.eligibleUserCount() != null) {
                if (eligibleUserCount != null && !eligibleUserCount.equals(page.eligibleUserCount())) { issues.add("接口合格人数在抓取中变化"); break; }
                eligibleUserCount = page.eligibleUserCount();
            }
            if (page.eligibleTradingVolume() != null) {
                if (eligibleTradingVolume != null && eligibleTradingVolume.compareTo(page.eligibleTradingVolume()) != 0) {
                    issues.add("接口合格交易量在抓取中变化"); break;
                }
                eligibleTradingVolume = page.eligibleTradingVolume();
            }
            if (pageIndex == 1 && !page.entries().isEmpty() && page.entries().size() < pageSize &&
                    !Boolean.FALSE.equals(page.hasMore()) &&
                    (expectedTotal == null || expectedTotal > page.entries().size())) {
                int effective = page.entries().size();
                if (effective == 0) { issues.add("第一页为空但接口表示还有数据，无法确定页大小"); break; }
                // 服务端限制 pageSize 时从第一页重来，不能直接沿用页码，否则会漏排名。
                pageSize = effective;
                pageIndex = 0;
                all.clear();
                progress.accept(new Progress(requested, 0));
                expectedTotal = null;
                eligibleUserCount = null;
                eligibleTradingVolume = null;
                volumeField = null;
                entriesPath = null;
                previousPage = null;
                continue;
            }
            if (page.reportedPageSize() != null && page.reportedPageSize() != pageSize &&
                    !(page.reportedPageSize() == page.entries().size() && expectedTotal != null &&
                            all.size() + page.entries().size() == expectedTotal)) {
                issues.add("接口报告的 pageSize 与请求值不一致"); break;
            }
            if (page.entries().isEmpty()) {
                if (expectedTotal != null && all.size() == expectedTotal) { complete = true; break; }
                if (previousEmpty && expectedTotal == null) { complete = true; break; }
                if (Boolean.FALSE.equals(page.hasMore()) && expectedTotal == null) { complete = true; break; }
                if (Boolean.TRUE.equals(page.hasMore())) { issues.add("空页但接口表示还有数据"); break; }
                if (previousEmpty && expectedTotal != null) { issues.add("连续空页但记录数未达到接口总人数"); break; }
                previousEmpty = true;
                previousPage = page;
                continue;
            }
            if (previousEmpty) { issues.add("空页后又出现数据，分页不稳定"); break; }
            for (LeaderboardEntry entry : page.entries()) {
                long position = all.size() + 1L;
                LeaderboardEntry prior = all.isEmpty() ? null : all.get(all.size() - 1);
                boolean tie = prior != null && prior.volume().compareTo(entry.volume()) == 0;
                if ((prior != null && entry.volume().compareTo(prior.volume()) > 0) ||
                        (entry.rank() != position && !(tie && entry.rank() == prior.rank()))) {
                    issues.add("排名/交易量排序异常；第 " + position + " 条的排名为 " + entry.rank()); break;
                }
                // 接口 userId 可能已脱敏，不能用其重复值断言页间重叠。
                // 相同交易量可能并列排名；按记录位置核对页码。
                all.add(entry);
            }
            if (!issues.isEmpty()) break;
            progress.accept(new Progress(requested, all.size()));
            if (expectedTotal != null && all.size() > expectedTotal) { issues.add("实际记录数超过接口总人数"); break; }
            if (Boolean.FALSE.equals(page.hasMore())) {
                if (expectedTotal == null || all.size() == expectedTotal) complete = true;
                else issues.add("接口声称末页，但记录数与总人数不符");
                break;
            }
            if (expectedTotal != null && all.size() == expectedTotal) {
                if (Boolean.TRUE.equals(page.hasMore())) issues.add("记录数已达到总人数，但接口仍表示有下一页");
                else complete = true;
                break;
            }
            previousPage = page;
        }
        if (!complete && issues.isEmpty()) issues.add("达到 maxPages 上限，结果未完成");
        if (complete && eligibleUserCount != null && eligibleUserCount != all.size()) {
            complete = false; issues.add("接口合格人数与榜单记录数不一致，资格范围需核对");
        }
        BigDecimal allVolume = all.stream().map(LeaderboardEntry::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (complete && eligibleTradingVolume != null && eligibleTradingVolume.compareTo(allVolume) != 0) {
            complete = false; issues.add("榜单交易量合计与接口合格交易量不一致，资格范围需核对");
        }
        List<LeaderboardEntry> selected = all.stream().filter(e -> e.rank() >= options.startRank()).toList();
        BigDecimal total = selected.stream().map(LeaderboardEntry::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = selected.isEmpty() ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(selected.size()), MathContext.DECIMAL128);
        BigDecimal rank1000 = all.stream().filter(e -> e.rank() == 1000L).findFirst().map(LeaderboardEntry::volume).orElse(null);
        LeaderboardStatistics.RewardEstimate reward = complete && total.signum() > 0 && options.rewardPool() != null
                ? estimate(options, total) : null;
        return new LeaderboardStatistics(options.resourceId(), options.startRank(), options.pageSize(), pageSize,
                requested, Instant.now().toString(), complete ? "COMPLETE" : "INCOMPLETE", List.copyOf(issues),
                selected.size(), total, average, all.isEmpty() ? null : all.get(all.size() - 1).rank(), rank1000,
                volumeField, entriesPath, "UNVERIFIED_RULES", eligibleUserCount, eligibleTradingVolume,
                List.copyOf(selected), reward);
    }
    public static LeaderboardStatistics.RewardEstimate estimate(Options options, BigDecimal total) {
        if (total.signum() <= 0 || options.rewardPool() == null) throw new IllegalArgumentException("总交易量和奖池必须大于 0");
        BigDecimal pool = options.rewardPool();
        MathContext mc = MathContext.DECIMAL128;
        BigDecimal per1000 = pool.multiply(BigDecimal.valueOf(1000)).divide(total, mc);
        BigDecimal per10000 = pool.multiply(BigDecimal.valueOf(10000)).divide(total, mc);
        BigDecimal capVolume = options.maxReward() == null ? null : total.multiply(options.maxReward()).divide(pool, mc);
        List<LeaderboardStatistics.AccountReward> accounts = options.volumes().stream().map(volume -> {
            BigDecimal raw = volume.multiply(pool).divide(total, mc);
            BigDecimal capped = options.maxReward() == null ? raw : raw.min(options.maxReward());
            return new LeaderboardStatistics.AccountReward(volume, raw, capped);
        }).toList();
        return new LeaderboardStatistics.RewardEstimate(pool, options.rewardUnit(), options.maxReward(),
                per1000, per10000, capVolume, accounts);
    }
}
