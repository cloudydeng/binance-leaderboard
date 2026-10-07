package com.example.binance.model;

import java.util.List;
import java.math.BigDecimal;

public record LeaderboardPage(List<LeaderboardEntry> entries, Long total, Boolean hasMore,
                              Integer reportedPageSize, String volumeField, String entriesPath,
                              String rankField, String userIdField, Long eligibleUserCount,
                              BigDecimal eligibleTradingVolume) {}
