package com.example.binance.service;

import com.example.binance.config.Options;
import com.example.binance.model.LeaderboardStatistics;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/** Uses the newest saved complete snapshot unless the caller requests a refresh. */
public class CachedLeaderboardService {
    public record Outcome(LeaderboardStatistics result, String cacheWarning) {}

    private final LeaderboardStatisticsService.PageSource source;
    private final LeaderboardSnapshotCache cache;
    private final Consumer<LeaderboardStatisticsService.Progress> progress;

    public CachedLeaderboardService(LeaderboardStatisticsService.PageSource source, LeaderboardSnapshotCache cache,
                                    Consumer<LeaderboardStatisticsService.Progress> progress) {
        this.source = source;
        this.cache = cache;
        this.progress = progress;
    }

    public Outcome collect(Options options) throws InterruptedException {
        String warning = null;
        if (!options.refresh()) {
            try {
                Optional<LeaderboardStatistics> cached = cache.latest(options.resourceId());
                if (cached.isPresent())
                    return new Outcome(LeaderboardStatisticsService.recalculate(options, cached.get(), true), null);
            } catch (IOException e) { warning = "本地缓存不可用，已重新抓取"; }
        }
        LeaderboardStatistics full = new LeaderboardStatisticsService(source, progress)
                .collect(options.forFullLeaderboard());
        if ("COMPLETE".equals(full.completeness())) {
            if (full.leaderboardUpdatedAt() == null) warning = "接口未提供榜单更新时间，本次结果未缓存";
            else {
                try { cache.save(full); }
                catch (IOException e) { warning = "缓存写入失败：" + e.getMessage(); }
            }
        }
        return new Outcome(LeaderboardStatisticsService.recalculate(options, full, false), warning);
    }
}
