package com.example.binance;

import com.example.binance.service.LeaderboardStatisticsService;
import com.example.binance.square.BinanceSquarePublisher;
import com.example.binance.telegram.TelegramNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
        try (MockWebServer telegram = new MockWebServer(); MockWebServer square = new MockWebServer()) {
            telegram.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":{}}"));
            telegram.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":{}}"));
            square.enqueue(new MockResponse().setBody(
                    "{\"code\":\"000000\",\"data\":{\"id\":\"42\",\"shareLink\":\"https://www.binance.com/en/square/post/42\"}}"));
            TelegramNotifier notifier = new TelegramNotifier(telegram.url("/").uri(),
                    "123:TestToken", "456", HttpClient.newHttpClient());
            BinanceSquarePublisher publisher = new BinanceSquarePublisher(square.url("/content/add").uri(),
                    "test-posting-key", HttpClient.newHttpClient());
            Clock clock = Clock.fixed(Instant.parse("2026-10-14T04:00:00Z"), ZoneOffset.UTC);

            assertEquals(0, ScheduledLeaderboardReport.run(source, notifier, publisher, temp, 0, clock));
            assertEquals(22, calls.get());
            JsonNode telegramBody = mapper.readTree(telegram.takeRequest().getBody().readUtf8());
            String first = telegramBody.path("text").asText();
            assertTrue(first.contains("100030536"));
            assertTrue(first.contains("100030286"));
            assertTrue(first.contains("交易量合计：999 USD"));
            assertTrue(first.contains("第 1001–1001 名：1 人"));
            assertEquals(2, first.split("第 1000 名交易量：1,000 USD", -1).length - 1);
            assertEquals(2, first.split("第 1000 名手续费估算（BNB 支付）：约 0.75 USDT", -1).length - 1);
            assertTrue(first.contains("交易量 × 0.075%"));
            assertTrue(first.contains("80 BNB"));
            assertTrue(first.contains("0.05 BNB"));
            assertTrue(first.contains("交易对：ZEC/USDC、USUAL/USDT"));
            assertTrue(first.contains("交易对：MET/USDC"));
            assertTrue(first.contains("活动结束：2026-10-15 18:00:00（北京时间）"));
            assertTrue(first.contains("距离结束："));
            assertFalse(first.contains("TestToken"));
            JsonNode entities = telegramBody.path("entities");
            assertEquals(3, entities.size());
            assertTrue(telegramBody.path("link_preview_options").path("is_disabled").asBoolean());
            assertEquals("https://www.binance.com/zh-CN/trade/ZEC_USDC", entities.get(0).path("url").asText());
            assertEquals(first.indexOf("ZEC/USDC"), entities.get(0).path("offset").asInt());
            assertEquals("https://www.binance.com/zh-CN/trade/USUAL_USDT", entities.get(1).path("url").asText());
            assertEquals("https://www.binance.com/zh-CN/trade/MET_USDC", entities.get(2).path("url").asText());
            var squareRequest = square.takeRequest();
            assertEquals("test-posting-key", squareRequest.getHeader("X-Square-OpenAPI-Key"));
            JsonNode body = mapper.readTree(squareRequest.getBody().readUtf8());
            assertEquals(1, body.path("contentType").asInt());
            assertTrue(body.path("bodyTextOnly").asText().startsWith(first));
            assertTrue(body.path("bodyTextOnly").asText().contains(
                    "ZEC/USDC https://www.binance.com/zh-CN/trade/ZEC_USDC"));
            assertTrue(body.path("bodyTextOnly").asText().contains(
                    "USUAL/USDT https://www.binance.com/zh-CN/trade/USUAL_USDT"));
            assertTrue(body.path("bodyTextOnly").asText().contains(
                    "MET/USDC https://www.binance.com/zh-CN/trade/MET_USDC"));
            assertFalse(body.toString().contains("test-posting-key"));

            assertEquals(0, ScheduledLeaderboardReport.run(source, notifier, publisher, temp, 0, clock));
            assertEquals(44, calls.get()); // each scheduled run checks Binance again
            assertNotNull(telegram.takeRequest());
            assertEquals(1, square.getRequestCount()); // one public post per hour
        }
    }

    @Test void countdownStopsAtOfficialDeadline() {
        var campaign = new ScheduledLeaderboardReport.Campaign("MET", "100030286",
                List.of(new ScheduledLeaderboardReport.TradingPair("MET/USDC", "MET_USDC")),
                Instant.parse("2026-10-15T10:00:00Z"));
        var sections = List.of(new ScheduledLeaderboardReport.Section(campaign, null, "未抓取"));
        assertTrue(ScheduledLeaderboardReport.format(Instant.parse("2026-10-14T09:00:00Z"), sections)
                .contains("距离结束：1 天 1 小时 0 分钟"));
        assertTrue(ScheduledLeaderboardReport.format(campaign.endsAt(), sections)
                .contains("距离结束：已结束"));
    }
}
