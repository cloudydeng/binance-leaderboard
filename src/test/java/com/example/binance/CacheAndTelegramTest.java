package com.example.binance;

import com.example.binance.config.Options;
import com.example.binance.service.*;
import com.example.binance.telegram.TelegramNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CacheAndTelegramTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    private Options options(String... extra) {
        String[] args = new String[extra.length + 3];
        args[0] = "--resourceId=12";
        args[1] = "--pageSize=3";
        args[2] = "--delayMs=0";
        System.arraycopy(extra, 0, args, 3, extra.length);
        return Options.parse(args);
    }

    @Test void fullSnapshotIsReusedAcrossInputsAndRefreshFetchesAgain() throws Exception {
        JsonNode page = mapper.readTree("""
                {"data":{"list":[{"rank":1,"tradeVolume":"20"},
                {"rank":2,"tradeVolume":"12"},{"rank":3,"tradeVolume":"8"}],
                "total":3,"hasMore":false,"updatedTime":1791853199000}}
                """);
        AtomicInteger calls = new AtomicInteger();
        var source = (LeaderboardStatisticsService.PageSource) (id, index, size) -> {
            calls.incrementAndGet();
            return page;
        };
        var cache = new LeaderboardSnapshotCache(temp.resolve("cache"));
        var first = new CachedLeaderboardService(source, cache, progress -> {})
                .collect(options("--startRank=2", "--volumes=11"));
        assertEquals("BINANCE", first.result().dataSource());
        assertEquals("20", first.result().totalVolume().toPlainString());
        assertEquals(3, first.result().volumeRanks().get(0).rank());
        assertEquals(1, calls.get());
        assertTrue(Files.exists(temp.resolve("cache/12/1791853199000.json")));
        String saved = Files.readString(temp.resolve("cache/12/1791853199000.json"));
        assertFalse(saved.contains("\"11\"")); // personal volumes are never saved in the snapshot

        var second = new CachedLeaderboardService(source, new LeaderboardSnapshotCache(temp.resolve("cache")), progress -> {})
                .collect(options("--startRank=1", "--rewardPool=40", "--volumes=11"));
        assertEquals("CACHE", second.result().dataSource());
        assertEquals("40", second.result().totalVolume().toPlainString());
        assertEquals(3, second.result().volumeRanks().get(0).rank());
        assertNotNull(second.result().rewardEstimate());
        assertEquals(1, calls.get());

        var refreshed = new CachedLeaderboardService(source, cache, progress -> {})
                .collect(options("--startRank=2", "--refresh=true"));
        assertEquals("BINANCE", refreshed.result().dataSource());
        assertEquals(2, calls.get());
    }

    @Test void incompleteOrCorruptSnapshotIsNeverReused() throws Exception {
        JsonNode incomplete = mapper.readTree("""
                {"data":{"list":[{"rank":1,"tradeVolume":5}],
                "total":2,"hasMore":true,"updatedTime":1791853199000}}
                """);
        AtomicInteger calls = new AtomicInteger();
        var source = (LeaderboardStatisticsService.PageSource) (id, index, size) -> {
            calls.incrementAndGet();
            return incomplete;
        };
        var cache = new LeaderboardSnapshotCache(temp.resolve("cache"));
        var service = new CachedLeaderboardService(source, cache, progress -> {});
        assertEquals("INCOMPLETE", service.collect(options("--maxPages=1")).result().completeness());
        assertFalse(Files.exists(temp.resolve("cache/12/1791853199000.json")));
        assertEquals(1, calls.get());
        Path directory = temp.resolve("cache/12");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("1791853199000.json"), "broken");
        assertEquals("BINANCE", service.collect(options("--maxPages=1")).result().dataSource());
        assertEquals(2, calls.get());
    }

    @Test void telegramUsesBotApiAndIncludesCacheSource() throws Exception {
        JsonNode page = mapper.readTree("""
                {"data":{"list":[{"rank":1,"tradeVolume":20}],"total":1,
                "hasMore":false,"updatedTime":1791853199000}}
                """);
        var result = new LeaderboardStatisticsService((id, index, size) -> page)
                .collect(options("--startRank=1", "--volumes=18"));
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":{}}"));
            server.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":[{\"message\":{\"chat\":{\"id\":123}}}]}"));
            var notifier = new TelegramNotifier(server.url("/").uri(), "123:TestToken", "123", HttpClient.newHttpClient());
            notifier.send(result);
            var sent = server.takeRequest();
            assertEquals("/bot123:TestToken/sendMessage", sent.getPath());
            assertEquals("POST", sent.getMethod());
            JsonNode body = mapper.readTree(sent.getBody().readUtf8());
            assertEquals("123", body.path("chat_id").asText());
            assertTrue(body.path("text").asText().contains("交易量合计：20"));
            assertFalse(body.path("text").asText().contains("TestToken"));
            assertEquals(java.util.Set.of("123"), notifier.chatIds());
        }
    }
}
