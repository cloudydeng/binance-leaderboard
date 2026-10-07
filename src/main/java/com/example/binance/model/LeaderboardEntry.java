package com.example.binance.model;

import java.math.BigDecimal;

public record LeaderboardEntry(long rank, String userId, BigDecimal volume) {}
