package com.example.binance.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

public record Options(String resourceId, int startRank, int pageSize, int maxPages, int delayMs,
                      BigDecimal rewardPool, String rewardUnit, BigDecimal maxReward,
                      List<BigDecimal> volumes, String entriesPath, String rankField,
                      String volumeField, String userIdField, Path outputDir) {
    private static final Set<String> KEYS = Set.of("resourceId", "startRank", "pageSize", "maxPages", "delayMs",
            "rewardPool", "rewardUnit", "maxReward", "volumes", "entriesPath", "rankField",
            "volumeField", "userIdField", "outputDir");

    public static Options parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException("参数格式应为 --名称=值：" + arg);
            int equal = arg.indexOf('=');
            String key = arg.substring(2, equal);
            if (!KEYS.contains(key)) throw new IllegalArgumentException("未知参数：--" + key);
            if (values.putIfAbsent(key, arg.substring(equal + 1)) != null) throw new IllegalArgumentException("重复参数：--" + key);
        }
        String resourceId = values.get("resourceId");
        if (resourceId == null || !resourceId.matches("[1-9][0-9]*")) throw new IllegalArgumentException("--resourceId 必填且必须是正整数");
        try { Long.parseLong(resourceId); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("resourceId 超出整数范围"); }
        int startRank = positiveInt(values, "startRank", 1001);
        int pageSize = positiveInt(values, "pageSize", 100);
        int maxPages = positiveInt(values, "maxPages", 1000);
        int delayMs = nonnegativeInt(values, "delayMs", 450);
        if (pageSize > 1000 || maxPages > 100000 || delayMs > 60000) throw new IllegalArgumentException("pageSize、maxPages 或 delayMs 超出安全范围");
        BigDecimal rewardPool = decimal(values.get("rewardPool"), "rewardPool");
        BigDecimal maxReward = decimal(values.get("maxReward"), "maxReward");
        if (rewardPool != null && rewardPool.signum() <= 0) throw new IllegalArgumentException("rewardPool 必须大于 0");
        if (maxReward != null && maxReward.signum() < 0) throw new IllegalArgumentException("maxReward 不能为负数");
        if (maxReward != null && rewardPool == null) throw new IllegalArgumentException("maxReward 需要 rewardPool");
        List<BigDecimal> volumes = new ArrayList<>();
        if (values.containsKey("volumes")) {
            for (String part : values.get("volumes").split(",", -1)) {
                BigDecimal volume = decimal(part, "volumes");
                if (volume == null || volume.signum() < 0) throw new IllegalArgumentException("volumes 中每个值必须非负");
                volumes.add(volume);
            }
            if (rewardPool == null) throw new IllegalArgumentException("volumes 需要 rewardPool");
        }
        String unit = values.getOrDefault("rewardUnit", "");
        if (!unit.isEmpty() && !unit.matches("[A-Za-z0-9]{2,16}")) throw new IllegalArgumentException("rewardUnit 格式无效");
        String entriesPath = values.get("entriesPath");
        String rankField = values.get("rankField");
        String volumeField = values.get("volumeField");
        String userIdField = values.get("userIdField");
        for (String field : List.of(entriesPath == null ? "" : entriesPath, rankField == null ? "" : rankField,
                volumeField == null ? "" : volumeField, userIdField == null ? "" : userIdField)) {
            if (!field.isEmpty() && !field.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("字段路径只允许字母、数字、下划线和点");
        }
        return new Options(resourceId, startRank, pageSize, maxPages, delayMs, rewardPool, unit, maxReward,
                List.copyOf(volumes), entriesPath, rankField, volumeField, userIdField,
                Path.of(values.getOrDefault("outputDir", ".")));
    }

    private static int positiveInt(Map<String, String> m, String k, int defaultValue) {
        int value = nonnegativeInt(m, k, defaultValue);
        if (value == 0) throw new IllegalArgumentException(k + " 必须大于 0");
        return value;
    }
    private static int nonnegativeInt(Map<String, String> m, String k, int defaultValue) {
        try {
            int value = Integer.parseInt(m.getOrDefault(k, Integer.toString(defaultValue)));
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(k + " 必须是非负整数"); }
    }
    private static BigDecimal decimal(String value, String key) {
        if (value == null) return null;
        try { return new BigDecimal(value.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须是有效十进制数"); }
    }

    public static String help() {
        return "用法: java -jar target/binance-leaderboard-stat.jar --resourceId=活动ID [选项]\n"
                + "--startRank=1001 --pageSize=100 --maxPages=1000 --delayMs=450\n"
                + "--rewardPool=40000 --rewardUnit=USDC --maxReward=30 --volumes=1000,2000（奖励参数仅作示例）\n"
                + "--entriesPath=data.resourceSummaryList.data --rankField=sequence --volumeField=tradingVolume [--userIdField=userId]\n"
                + "--outputDir=.  结构无法自动确认时必须显式指定字段映射。\n"
                + "身份信息仅从 BINANCE_COOKIE / BINANCE_CSRF_TOKEN / BINANCE_UUID 环境变量读取。";
    }
}
