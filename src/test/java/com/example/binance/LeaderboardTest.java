package com.example.binance;

import com.example.binance.client.BinanceLeaderboardClient;
import com.example.binance.config.Options;
import com.example.binance.model.LeaderboardStatistics;
import com.example.binance.service.LeaderboardStatisticsService;
import com.example.binance.util.JsonFieldDetector;
import com.example.binance.util.ResultWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LeaderboardTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temp;
    private Options options(String... extra) {
        List<String> args = new ArrayList<>(List.of("--resourceId=100027837", "--pageSize=2", "--delayMs=0"));
        args.addAll(List.of(extra));
        return Options.parse(args.toArray(String[]::new));
    }
    private JsonNode json(String raw) {
        try { return mapper.readTree(raw); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void parsesGroupedDecimalAndRejectsAmbiguousOrMissingVolume() {
        var page = new JsonFieldDetector().parse(json("""
                {"data":{"list":[{"rank":1,"userId":"u1","tradeVolume":"14,048.0258"},
                {"rank":2,"tradeVolume":14048.0258}],"total":2,"hasMore":false}}
                """), options());
        assertEquals(new BigDecimal("14048.0258"), page.entries().get(0).volume());
        assertEquals(2, page.total());
        assertThrows(IllegalArgumentException.class, () -> new JsonFieldDetector().parse(
                json("{" + "\"data\":{\"list\":[{\"rank\":1,\"reward\":3}]}}"), options()));
        assertThrows(IllegalArgumentException.class, () -> new JsonFieldDetector().parse(
                json("{" + "\"data\":{\"list\":[{\"rank\":1,\"tradeVolume\":2,\"tradingVolume\":3}]}}"), options()));
    }

    @Test void countsByRealRankAndExportsParsableFiles() throws Exception {
        List<JsonNode> pages = List.of(
                json("{" + "\"data\":{\"list\":[{\"rank\":1,\"tradeVolume\":\"9\"},{\"rank\":2,\"tradeVolume\":\"8\"}],\"total\":4,\"hasMore\":true}}"),
                json("{" + "\"data\":{\"list\":[{\"rank\":3,\"tradeVolume\":\"4.4\"},{\"rank\":4,\"tradeVolume\":\"3.3\"}],\"total\":4,\"hasMore\":false}}"));
        var result = new LeaderboardStatisticsService((id, index, size) -> pages.get(index - 1))
                .collect(options("--startRank=3", "--rewardPool=80", "--maxReward=5", "--volumes=1,10"));
        assertEquals("COMPLETE", result.completeness());
        assertEquals(2, result.rankFilteredCount());
        assertEquals(new BigDecimal("7.7"), result.totalVolume());
        assertEquals(new BigDecimal("3.85"), result.averageVolume());
        assertEquals(2, result.pagesRequested());
        assertEquals(0, result.rewardEstimate().accounts().get(1).capped().compareTo(new BigDecimal("5")));
        new ResultWriter().write(result, temp);
        JsonNode saved = mapper.readTree(temp.resolve("result-100027837.json").toFile());
        assertEquals("7.7", saved.path("totalVolume").asText());
        assertEquals(2, saved.path("entries").size());
        assertEquals(3, Files.readAllLines(temp.resolve("result-100027837.csv")).size());
    }

    @Test void detectsDuplicateAndMissingRanksAsIncomplete() throws Exception {
        JsonNode first = json("{" + "\"data\":{\"list\":[{\"rank\":1,\"userId\":\"a\",\"tradeVolume\":2},{\"rank\":2,\"userId\":\"b\",\"tradeVolume\":1}],\"hasMore\":true}}" );
        JsonNode duplicate = json("{" + "\"data\":{\"list\":[{\"rank\":1,\"userId\":\"a\",\"tradeVolume\":2},{\"rank\":2,\"tradeVolume\":1}],\"hasMore\":false}}" );
        JsonNode missing = json("{" + "\"data\":{\"list\":[{\"rank\":4,\"tradeVolume\":4}],\"hasMore\":false}}" );
        for (JsonNode second : List.of(duplicate, missing)) {
            var result = new LeaderboardStatisticsService((id, index, size) -> index == 1 ? first : second)
                    .collect(options("--startRank=1", "--rewardPool=10"));
            assertEquals("INCOMPLETE", result.completeness());
            assertNull(result.rewardEstimate());
            assertFalse(result.issues().isEmpty());
        }
    }

    @Test void acceptsLegitimateTiedRanksAndScientificNumbers() throws Exception {
        JsonNode first = json("{\"data\":{\"resourceSummaryList\":{\"data\":[{\"sequence\":1,\"tradingVolume\":1.5e2},{\"sequence\":2,\"userId\":\"masked\",\"tradingVolume\":100}],\"total\":4},\"eligibleUserCount\":4,\"eligibleTradingVolume\":350}}" );
        JsonNode second = json("{\"data\":{\"resourceSummaryList\":{\"data\":[{\"sequence\":2,\"userId\":\"masked\",\"tradingVolume\":100},{\"sequence\":4,\"tradingVolume\":0}],\"total\":4},\"eligibleUserCount\":4,\"eligibleTradingVolume\":350}}" );
        var result = new LeaderboardStatisticsService((id, index, size) -> index == 1 ? first : second)
                .collect(options("--startRank=2"));
        assertEquals("COMPLETE", result.completeness());
        assertEquals(3, result.rankFilteredCount());
        assertEquals(new BigDecimal("200"), result.totalVolume());
    }

    @Test void reducesCappedPageSizeAndRestartsAtFirstPage() throws Exception {
        List<String> requests = new ArrayList<>();
        var result = new LeaderboardStatisticsService((id, index, size) -> {
            requests.add(index + "/" + size);
            if (index == 1) return json("{" + "\"data\":{\"list\":[{\"rank\":1,\"tradeVolume\":3},{\"rank\":2,\"tradeVolume\":2}],\"total\":3,\"hasMore\":true}}" );
            return json("{" + "\"data\":{\"list\":[{\"rank\":3,\"tradeVolume\":1}],\"total\":3,\"hasMore\":false}}" );
        }).collect(Options.parse(new String[]{"--resourceId=12", "--pageSize=100", "--delayMs=0", "--startRank=1"}));
        assertEquals(List.of("1/100", "1/2", "2/2"), requests);
        assertEquals(2, result.effectivePageSize());
        assertEquals("COMPLETE", result.completeness());
        assertEquals(3, result.rankFilteredCount());
    }

    @Test void retriesSmallerFirstPageWhenServerRejectsPageSize() throws Exception {
        List<Integer> sizes = new ArrayList<>();
        var result = new LeaderboardStatisticsService((id, index, size) -> {
            sizes.add(size);
            if (size > 2) throw new java.io.IOException("HTTP 400：请求失败");
            return json("{\"data\":{\"list\":[{\"rank\":1,\"tradeVolume\":2},{\"rank\":2,\"tradeVolume\":1}],\"total\":2,\"hasMore\":false}}" );
        }).collect(Options.parse(new String[]{"--resourceId=12", "--pageSize=8", "--delayMs=0", "--startRank=1"}));
        assertEquals(List.of(8, 4, 2), sizes);
        assertEquals("COMPLETE", result.completeness());
    }

    @Test void emptyWithHasMoreAndPageLimitStayIncomplete() throws Exception {
        JsonNode empty = json("{" + "\"data\":{\"list\":[],\"hasMore\":true}}" );
        var result = new LeaderboardStatisticsService((id, index, size) -> empty).collect(options());
        assertEquals("INCOMPLETE", result.completeness());
        var limited = new LeaderboardStatisticsService((id, index, size) -> json("{" + "\"data\":{\"list\":[{\"rank\":1,\"tradeVolume\":1}],\"hasMore\":true}}"))
                .collect(options("--maxPages=1"));
        assertEquals("INCOMPLETE", limited.completeness());
    }

    @Test void twoEmptyPagesConfirmEndWithoutPaginationMetadata() throws Exception {
        JsonNode first = json("{\"data\":{\"list\":[{\"rank\":1,\"tradeVolume\":\"0.02\"},{\"rank\":2,\"tradeVolume\":\"0.01\"}]}}" );
        JsonNode empty = json("{\"data\":{\"list\":[]}}" );
        var result = new LeaderboardStatisticsService((id, index, size) -> index == 1 ? first : empty)
                .collect(options("--startRank=1"));
        assertEquals("COMPLETE", result.completeness());
        assertEquals(3, result.pagesRequested());
        assertEquals(new BigDecimal("0.03"), result.totalVolume());
    }

    @Test void rewardMathAndInvalidInput() {
        var estimate = LeaderboardStatisticsService.estimate(options("--rewardPool=80", "--maxReward=0.05", "--volumes=1,10"), new BigDecimal("1000"));
        assertEquals(new BigDecimal("80"), estimate.rewardPer1000());
        assertEquals(new BigDecimal("0.625"), estimate.capVolume());
        assertEquals(new BigDecimal("0.05"), estimate.accounts().get(0).capped());
        assertThrows(IllegalArgumentException.class, () -> options("--rewardPool=0"));
        assertThrows(IllegalArgumentException.class, () -> options("--volumes=1,broken", "--rewardPool=2"));
    }

    @Test void httpClientHandlesAuthAndRetriesServerErrorWithoutLeakingCookie() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":\"000000\",\"success\":true,\"data\":{\"list\":[]}}"));
            var client = new BinanceLeaderboardClient(server.url("/test"), Map.of("BINANCE_COOKIE", "secret-cookie"), new OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build());
            assertTrue(client.fetchPage("12", 1, 10).path("success").asBoolean());
            assertEquals(2, server.getRequestCount());
            assertEquals("secret-cookie", server.takeRequest().getHeader("Cookie"));
            server.takeRequest();
            server.enqueue(new MockResponse().setResponseCode(403).setBody("secret-cookie"));
            String error = assertThrows(java.io.IOException.class, () -> client.fetchPage("12", 1, 10)).getMessage();
            assertTrue(error.contains("403"));
            assertFalse(error.contains("secret-cookie"));
        }
    }

    @Test void timeoutAndBusinessFailureAreExplicit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":\"123456\",\"success\":false}"));
            var client = new BinanceLeaderboardClient(server.url("/test"), Map.of(), new OkHttpClient());
            assertTrue(assertThrows(java.io.IOException.class, () -> client.fetchPage("12", 1, 10))
                    .getMessage().contains("业务请求失败"));
            for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setBodyDelay(250, TimeUnit.MILLISECONDS)
                    .setBody("{\"code\":\"000000\"}"));
            var shortTimeoutClient = new BinanceLeaderboardClient(server.url("/test"), Map.of(),
                    new OkHttpClient.Builder().readTimeout(20, TimeUnit.MILLISECONDS).build());
            String error = assertThrows(java.io.IOException.class, () -> shortTimeoutClient.fetchPage("12", 1, 10)).getMessage();
            assertTrue(error.contains("重试 3 次"));
        }
    }

    @Test void longRetryAfterStopsWithoutEarlyRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "120"));
            var client = new BinanceLeaderboardClient(server.url("/test"), Map.of(), new OkHttpClient());
            String error = assertThrows(java.io.IOException.class, () -> client.fetchPage("12", 1, 10)).getMessage();
            assertTrue(error.contains("Retry-After"));
            assertEquals(1, server.getRequestCount());
        }
    }
}
