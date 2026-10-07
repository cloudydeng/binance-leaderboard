package com.example.binance.util;

import com.example.binance.config.Options;
import com.example.binance.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

public class JsonFieldDetector {
    private static final List<String> RANK = List.of("rank", "ranking", "sequence");
    private static final List<String> VOLUME = List.of("tradeVolume", "tradingVolume", "totalTradeVolume");
    private static final List<String> ID = List.of("userId", "uid");

    public LeaderboardPage parse(JsonNode root, Options options) {
        return parse(root, options, null);
    }
    public LeaderboardPage parse(JsonNode root, Options options, LeaderboardPage previous) {
        String path = previous == null ? options.entriesPath() : previous.entriesPath();
        JsonNode array;
        if (path != null) {
            array = at(root, path);
            if (!array.isArray()) throw new IllegalArgumentException("entriesPath 不是数组；结构：" + shape(root));
        } else {
            List<String> candidates = new ArrayList<>();
            findArrays(root, "", candidates, 0);
            if (candidates.size() != 1) throw new IllegalArgumentException("无法唯一识别排行榜数组，请指定 --entriesPath；结构：" + shape(root));
            path = candidates.get(0);
            array = at(root, path);
        }
        String rankField = selectField(array, previous == null ? options.rankField() : previous.rankField(), RANK, "rankField", true);
        String volumeField = selectField(array, previous == null ? options.volumeField() : previous.volumeField(), VOLUME, "volumeField", true);
        String idField = selectField(array, previous == null ? options.userIdField() : previous.userIdField(), ID, "userIdField", false);
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) throw new IllegalArgumentException("排行榜数组含非对象元素");
            // 资格字段含义取决于活动规则，遇到时停止，防止把不计奖用户纳入分母。
            for (String eligibility : List.of("eligible", "isEligible", "rewardEligible", "qualified", "qualification"))
                if (item.has(eligibility)) throw new IllegalArgumentException("发现资格字段 " + eligibility + "，需确认活动规则后再统计");
            if (item.path("hitRisk").isBoolean() && item.path("hitRisk").booleanValue())
                throw new IllegalArgumentException("发现 hitRisk=true 的用户，需确认计奖资格后再统计");
            long rank = parseRank(item.get(rankField));
            BigDecimal volume = parseVolume(item.get(volumeField));
            String id = idField == null || item.path(idField).isNull() ? null : item.path(idField).asText(null);
            entries.add(new LeaderboardEntry(rank, id, volume));
        }
        JsonNode parent = parent(root, path);
        Long total = numericLong(parent, "total", "totalCount", "totalSize");
        Boolean hasMore = booleanField(parent, "hasMore", "hasNext");
        Integer reportedSize = numericInt(parent, "pageSize", "size");
        if (total == null) total = numericLong(root, "total", "totalCount", "totalSize");
        if (hasMore == null) hasMore = booleanField(root, "hasMore", "hasNext");
        Long eligibleCount = numericLong(root.path("data"), "eligibleUserCount");
        BigDecimal eligibleVolume = optionalVolume(root.path("data").get("eligibleTradingVolume"));
        return new LeaderboardPage(List.copyOf(entries), total, hasMore, reportedSize, volumeField, path,
                rankField, idField, eligibleCount, eligibleVolume);
    }

    private String selectField(JsonNode array, String specified, List<String> candidates, String option, boolean required) {
        if (specified != null) return specified;
        if (array.isEmpty()) return required ? "" : null;
        JsonNode first = array.get(0);
        if (!first.isObject()) throw new IllegalArgumentException("排行榜元素格式无效");
        List<String> found = candidates.stream().filter(first::has).toList();
        if (found.size() > 1) throw new IllegalArgumentException(option + " 有歧义，请明确指定 --" + option);
        if (found.isEmpty()) {
            if (required) throw new IllegalArgumentException("无法识别 " + option + "，请指定 --" + option + "；字段：" + fieldNames(first));
            return null;
        }
        return found.get(0);
    }

    private void findArrays(JsonNode node, String path, List<String> result, int depth) {
        if (depth > 5 || node == null) return;
        if (node.isArray()) {
            if (node.size() > 0 && node.get(0).isObject()) {
                JsonNode first = node.get(0);
                if (RANK.stream().anyMatch(first::has) && VOLUME.stream().anyMatch(first::has)) result.add(path);
            }
            return;
        }
        if (node.isObject()) node.fields().forEachRemaining(e -> findArrays(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), result, depth + 1));
    }

    private JsonNode at(JsonNode node, String path) {
        for (String part : path.split("\\.")) node = node.path(part);
        return node;
    }
    private JsonNode parent(JsonNode root, String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? root : at(root, path.substring(0, dot));
    }
    private long parseRank(JsonNode node) {
        if (node == null || node.isNull() || !node.asText().matches("[1-9][0-9]*")) throw new IllegalArgumentException("排名缺失或格式错误");
        try { return Long.parseLong(node.asText()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("排名超出范围"); }
    }
    private BigDecimal parseVolume(JsonNode node) {
        if (node == null || node.isNull() || (!node.isTextual() && !node.isNumber())) throw new IllegalArgumentException("交易量缺失或格式错误");
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            if (value.signum() < 0) throw new IllegalArgumentException("交易量不能为负");
            return value;
        }
        String s = node.asText().trim();
        if (!s.matches("(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("交易量格式错误");
        BigDecimal value = new BigDecimal(s.replace(",", ""));
        if (value.signum() < 0) throw new IllegalArgumentException("交易量不能为负");
        return value;
    }
    private BigDecimal optionalVolume(JsonNode node) {
        return node == null || node.isNull() ? null : parseVolume(node);
    }
    private Long numericLong(JsonNode node, String... names) {
        for (String name : names) if (node.has(name) && node.path(name).asText().matches("[0-9]+")) return node.path(name).asLong();
        return null;
    }
    private Integer numericInt(JsonNode node, String... names) {
        Long value = numericLong(node, names);
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }
    private Boolean booleanField(JsonNode node, String... names) {
        for (String name : names) if (node.path(name).isBoolean()) return node.path(name).booleanValue();
        return null;
    }
    private String fieldNames(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return String.join(",", keys);
    }
    public String shape(JsonNode root) { return shapeNode(root, 0); }
    private String shapeNode(JsonNode node, int depth) {
        if (depth > 4) return "...";
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fields().forEachRemaining(e -> fields.add(e.getKey() + ":" + shapeNode(e.getValue(), depth + 1)));
            return "{" + String.join(",", fields) + "}";
        }
        if (node.isArray()) return "[" + (node.isEmpty() ? "" : shapeNode(node.get(0), depth + 1)) + "]";
        return node.getNodeType().name();
    }
}
