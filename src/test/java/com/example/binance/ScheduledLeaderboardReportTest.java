package com.example.binance;

import com.example.binance.service.LeaderboardStatisticsService;
import com.example.binance.telegram.TelegramNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledLeaderboardReportTest {
    @TempDir Path temp;

    @Test void refreshesBothLeaderboardsAndSendsOneCombinedMessage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        LeaderboardStatisticsService.PageSource source = (id, page, size) -> {
            assertTrue(id.equals("100030536") || id.equals("100030286"));
            calls.incrementAndGet();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode data = root.putObject("data");
            data.put("updatedTime", 1791853199000L);
            ObjectNode list = data.putObject("resourceSummaryList");
            list.put("total", 1001);
            list.put("hasMore", page < 11);
            ArrayNode entries = list.putArray("data");
            for (int rank = (page - 1) * size + 1; rank <= Math.min(page * size, 1001); rank++) {
                entries.addObject().put("sequence", rank).put("tradingVolume", 2000 - rank);
            }
            return root;
        };
        try (MockWebServer telegram = new MockWebServer()) {
            telegram.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":{}}"));
            telegram.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":{}}"));
            TelegramNotifier notifier = new TelegramNotifier(telegram.url("/").uri(),
                    "123:TestToken", "456", HttpClient.newHttpClient());

            assertEquals(0, ScheduledLeaderboardReport.run(source, notifier, temp, 0));
            assertEquals(22, calls.get());
            String first = mapper.readTree(telegram.takeRequest().getBody().readUtf8()).path("text").asText();
            assertTrue(first.contains("100030536"));
            assertTrue(first.contains("100030286"));
            assertTrue(first.contains("交易量合计：999 USD"));
            assertTrue(first.contains("第 1001–1001 名：1 人"));
            assertTrue(first.contains("80 BNB"));
            assertTrue(first.contains("0.05 BNB"));
            assertFalse(first.contains("TestToken"));

            assertEquals(0, ScheduledLeaderboardReport.run(source, notifier, temp, 0));
            assertEquals(44, calls.get()); // each scheduled run checks Binance again
            assertNotNull(telegram.takeRequest());
        }
    }
}
