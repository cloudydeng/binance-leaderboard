package com.example.binance;

import com.example.binance.config.Options;
import com.example.binance.service.LeaderboardSnapshotCache;
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
import java.util.concurrent.atomic.AtomicLong;
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
        AtomicLong zecUpdated = new AtomicLong(1791853199000L);
        AtomicLong metUpdated = new AtomicLong(1791853199000L);
        AtomicInteger zecVolumeBump = new AtomicInteger();
        AtomicInteger metVolumeBump = new AtomicInteger();
        LeaderboardStatisticsService.PageSource source = (id, page, size) -> {
            assertTrue(id.equals("100030536") || id.equals("100030286"));
            calls.incrementAndGet();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode data = root.putObject("data");
            data.put("updatedTime", id.equals("100030536") ? zecUpdated.get() : metUpdated.get());
            ObjectNode list = data.putObject("resourceSummaryList");
            list.put("total", 1001);
            list.put("hasMore", page < 11);
            ArrayNode entries = list.putArray("data");
            for (int rank = (page - 1) * size + 1; rank <= Math.min(page * size, 1001); rank++) {
                entries.addObject().put("sequence", rank).put("tradingVolume", 2000 - rank +
                        (id.equals("100030536") ? zecVolumeBump.get() : metVolumeBump.get()));
            }
            return root;
        };
        try (MockWebServer telegram = new MockWebServer(); MockWebServer square = new MockWebServer()) {
            telegram.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":{}}"));
            telegram.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":{}}"));
            telegram.enqueue(new MockResponse().setBody("{\"ok\":true,\"result\":{}}"));
            square.enqueue(new MockResponse().setBody(
                    "{\"code\":\"000000\",\"data\":{\"id\":\"42\",\"shareLink\":\"https://www.binance.com/en/square/post/42\"}}"));
            square.enqueue(new MockResponse().setBody(
                    "{\"code\":\"000000\",\"data\":{\"id\":\"43\",\"shareLink\":\"https://www.binance.com/en/square/post/43\"}}"));
            TelegramNotifier notifier = new TelegramNotifier(telegram.url("/").uri(),
                    "123:TestToken", "456", HttpClient.newHttpClient());
            BinanceSquarePublisher publisher = new BinanceSquarePublisher(square.url("/content/add").uri(),
                    "test-posting-key", HttpClient.newHttpClient());
            Clock clock = Clock.fixed(Instant.parse("2026-10-14T04:00:00Z"), ZoneOffset.UTC);
            Clock previousHour = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC);

            assertEquals(0, ScheduledLeaderboardReport.run(source, notifier, publisher, temp, 0, previousHour));
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
            assertFalse(first.contains("MET/USDC"));
            assertTrue(first.contains("活动结束：2026-10-15 18:00:00（北京时间）"));
            assertTrue(first.contains("距离结束："));
            assertFalse(first.contains("较上次"));
            assertFalse(first.contains("TestToken"));
            JsonNode entities = telegramBody.path("entities");
            assertEquals(2, entities.size());
            assertTrue(telegramBody.path("link_preview_options").path("is_disabled").asBoolean());
            assertEquals("https://www.binance.com/zh-CN/trade/ZEC_USDC", entities.get(0).path("url").asText());
            assertEquals(first.indexOf("ZEC/USDC"), entities.get(0).path("offset").asInt());
            assertEquals("https://www.binance.com/zh-CN/trade/USUAL_USDT", entities.get(1).path("url").asText());
            assertFalse(telegramBody.toString().contains("https://www.binance.com/zh-CN/trade/MET_USDC"));
            var squareRequest = square.takeRequest();
            assertEquals("test-posting-key", squareRequest.getHeader("X-Square-OpenAPI-Key"));
            JsonNode body = mapper.readTree(squareRequest.getBody().readUtf8());
            assertEquals(1, body.path("contentType").asInt());
            String squareText = body.path("bodyTextOnly").asText();
            assertTrue(squareText.startsWith("币安现货榜单\n$ZEC/USDC · $USUAL/USDT\n\n【第 1000 名交易量】"));
            assertTrue(squareText.contains("\n主榜 100030536\n榜单更新："));
            assertTrue(squareText.contains("【第 1000 名交易量】\n1,000 USD"));
            assertTrue(squareText.contains("BNB 抵扣手续费约 0.75 USDT"));
            assertTrue(squareText.contains("\n1001 名后：1 人\n交易量合计：999 USD"));
            assertTrue(squareText.contains("\n榜单更新："));
            assertFalse(squareText.contains("手续费按普通用户"));
            assertTrue(squareText.length() < first.length());
            assertFalse(squareText.contains("https://"));
            assertFalse(squareText.contains("MET/USDC"));
            assertFalse(body.toString().contains("test-posting-key"));
            String metSquareText = mapper.readTree(square.takeRequest().getBody().readUtf8())
                    .path("bodyTextOnly").asText();
            assertTrue(metSquareText.startsWith("币安现货榜单\n$MET/USDC\n\n【第 1000 名交易量】"));
            assertTrue(metSquareText.contains("\n主榜 100030286\n榜单更新："));
            assertFalse(metSquareText.contains("https://"));
            assertFalse(metSquareText.contains("ZEC/USDC"));

            assertEquals(0, ScheduledLeaderboardReport.runIfUpdated(source, notifier, null, temp, 0, clock));
            assertEquals(23, calls.get()); // unchanged first page; no full fetch or duplicate post
            assertEquals(1, telegram.getRequestCount());
            zecUpdated.incrementAndGet();
            assertEquals(0, ScheduledLeaderboardReport.runIfUpdated(source, notifier, null, temp, 0, clock));
            assertEquals(25, calls.get()); // wait for the other active campaign too
            assertEquals(1, telegram.getRequestCount());
            metUpdated.incrementAndGet();
            zecVolumeBump.set(10);
            metVolumeBump.set(20);
            assertEquals(0, ScheduledLeaderboardReport.runIfUpdated(source, notifier, null, temp, 0, clock));
            assertEquals(49, calls.get()); // two freshness probes plus both complete leaderboards
            String second = mapper.readTree(telegram.takeRequest().getBody().readUtf8()).path("text").asText();
            assertTrue(second.contains("对比上次推送榜单："));
            assertTrue(second.contains("第 1000 名交易量：1,010 USD（较上次 +10 USD）"));
            assertTrue(second.contains("第 1000 名交易量：1,020 USD（较上次 +20 USD）"));
            assertTrue(second.contains("交易量合计：1,009 USD（较上次 +10 USD）"));
            assertTrue(second.contains("交易量合计：1,019 USD（较上次 +20 USD）"));
            assertTrue(second.contains("第 1000 名手续费估算（BNB 支付）：约 0.76 USDT（较上次 +0.01 USDT）"));
            var cache = new LeaderboardSnapshotCache(temp.resolve("cache"));
            var options = Options.parse(new String[]{"--resourceId=100030536", "--startRank=1001",
                    "--rewardPool=80", "--rewardUnit=BNB", "--maxReward=0.05"});
            var priorZec = LeaderboardStatisticsService.recalculate(options,
                    cache.at("100030536", Instant.ofEpochMilli(1791853199000L)).orElseThrow(), true);
            var currentZec = LeaderboardStatisticsService.recalculate(options,
                    cache.at("100030536", Instant.ofEpochMilli(zecUpdated.get())).orElseThrow(), true);
            var zecCampaign = new ScheduledLeaderboardReport.Campaign("ZEC", "100030536",
                    List.of(new ScheduledLeaderboardReport.TradingPair("ZEC/USDC", "ZEC_USDC")),
                    Instant.parse("2026-10-16T10:00:00Z"));
            String squarePreview = ScheduledLeaderboardReport.formatSquare(clock.instant(),
                    new ScheduledLeaderboardReport.Section(zecCampaign, currentZec, null), priorZec);
            assertTrue(squarePreview.contains("【第 1000 名交易量】\n1,010 USD\n较上次 +10 USD\n"));
            assertTrue(squarePreview.contains("交易量合计：1,009 USD\n较上次 +10 USD\n"));
            assertFalse(squarePreview.contains("https://"));
            assertEquals(2, telegram.getRequestCount());
            assertEquals(2, square.getRequestCount()); // one post per active campaign
            assertEquals(0, ScheduledLeaderboardReport.runIfUpdated(source, notifier, null, temp, 0, clock));
            assertEquals(49, calls.get()); // no more than one new report in the same Beijing hour
            zecUpdated.incrementAndGet();
            zecVolumeBump.set(5);
            Clock afterMetEnds = Clock.fixed(Instant.parse("2026-10-15T10:05:00Z"), ZoneOffset.UTC);
            assertEquals(0, ScheduledLeaderboardReport.runIfUpdated(source, notifier, null, temp, 0, afterMetEnds));
            assertEquals(61, calls.get()); // ended MET reuses its complete cache
            assertEquals(3, telegram.getRequestCount());
            String third = mapper.readTree(telegram.takeRequest().getBody().readUtf8()).path("text").asText();
            assertTrue(third.contains("第 1000 名交易量：1,005 USD（较上次 -5 USD）"));
            assertTrue(third.contains("第 1000 名交易量：1,020 USD（较上次 0 USD）"));
            Clock afterBothEnd = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);
            assertEquals(0, ScheduledLeaderboardReport.runIfUpdated(source, notifier, null, temp, 0, afterBothEnd));
            assertEquals(61, calls.get());
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
