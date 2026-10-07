package com.example.binance.model;

import java.math.BigDecimal;
import java.util.List;

public record LeaderboardStatistics(String resourceId, int startRank, int requestedPageSize, int effectivePageSize,
                                    int pagesRequested, String fetchedAt, String completeness, List<String> issues,
                                    long rankFilteredCount, BigDecimal totalVolume, BigDecimal averageVolume,
                                    Long maxRank, BigDecimal rank1000Volume, String volumeField,
                                    String entriesPath, String qualificationStatus,
                                    Long serverEligibleUserCount, BigDecimal serverEligibleTradingVolume,
                                    List<LeaderboardEntry> entries, RewardEstimate rewardEstimate) {
    public record RewardEstimate(BigDecimal rewardPool, String rewardUnit, BigDecimal maxReward,
                                 BigDecimal rewardPer1000, BigDecimal rewardPer10000,
                                 BigDecimal capVolume, List<AccountReward> accounts) {}
    public record AccountReward(BigDecimal volume, BigDecimal raw, BigDecimal capped) {}
}
