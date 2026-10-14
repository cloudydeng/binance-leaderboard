package com.example.binance.telegram;

import com.example.binance.model.LeaderboardStatistics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Optional Telegram Bot API delivery; credentials come only from process environment. */
public class TelegramNotifier {
    private static final DateTimeFormatter BEIJING = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI apiRoot;
    private final String token;
    private final String chatId;

    public TelegramNotifier(URI apiRoot, String token, String chatId, HttpClient http) {
        if (token == null || !token.matches("[0-9]+:[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN 格式无效");
        if (chatId != null && !chatId.matches("-?[0-9]+|@[A-Za-z0-9_]{5,32}"))
            throw new IllegalArgumentException("TELEGRAM_CHAT_ID 格式无效");
        this.apiRoot = apiRoot;
        this.token = token;
        this.chatId = chatId;
        this.http = http;
    }

    public static TelegramNotifier fromEnvironment() {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        String chatId = System.getenv("TELEGRAM_CHAT_ID");
        boolean hasToken = token != null && !token.isBlank();
        boolean hasChat = chatId != null && !chatId.isBlank();
        if (!hasToken && !hasChat) return null;
        if (!hasToken || !hasChat) throw new IllegalArgumentException("需同时设置 TELEGRAM_BOT_TOKEN 和 TELEGRAM_CHAT_ID");
        return new TelegramNotifier(URI.create("https://api.telegram.org/"), token, chatId,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public static Set<String> chatIdsFromEnvironment() throws IOException, InterruptedException {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("请先设置 TELEGRAM_BOT_TOKEN 环境变量");
        return new TelegramNotifier(URI.create("https://api.telegram.org/"), token, null,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()).chatIds();
    }

    public Set<String> chatIds() throws IOException, InterruptedException {
        JsonNode response = request("getUpdates", null);
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode update : response.path("result")) {
            JsonNode chat = update.path("message").path("chat");
            if (chat.isMissingNode()) chat = update.path("channel_post").path("chat");
            if (chat.has("id")) ids.add(chat.path("id").asText());
        }
        return ids;
    }

    public void send(LeaderboardStatistics result) throws IOException, InterruptedException {
        sendText(message(result));
    }

    public void sendText(String text) throws IOException, InterruptedException {
        if (chatId == null) throw new IllegalStateException("TELEGRAM_CHAT_ID 未配置");
        if (text == null || text.isBlank() || text.length() > 4096)
            throw new IllegalArgumentException("Telegram 消息长度无效");
        request("sendMessage", Map.of("chat_id", chatId, "text", text));
    }

    private JsonNode request(String method, Object body) throws IOException, InterruptedException {
        URI url = apiRoot.resolve("./bot" + token + "/" + method);
        HttpRequest.Builder builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(12));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("Telegram HTTP " + response.statusCode());
        JsonNode json;
        try { json = mapper.readTree(response.body()); }
        catch (IOException e) { throw new IOException("Telegram 响应无法解析"); }
        if (!json.path("ok").asBoolean(false)) throw new IOException("Telegram API 返回失败");
        return json;
    }

    public static String message(LeaderboardStatistics result) {
        StringBuilder out = new StringBuilder("Binance 榜单统计\n")
                .append("活动 ID：").append(result.resourceId()).append('\n')
                .append("状态：").append(result.completeness()).append('\n')
                .append("榜单更新时间：").append(beijing(result.leaderboardUpdatedAt())).append('\n')
                .append("数据来源：").append("CACHE".equals(result.dataSource()) ? "本地缓存" : "Binance 接口").append('\n')
                .append("排名范围：").append(result.startRank()).append("–")
                .append(result.maxRank() == null ? "未知" : result.maxRank()).append('\n')
                .append("范围人数：").append(result.rankFilteredCount()).append('\n')
                .append("交易量合计：").append(decimal(result.totalVolume())).append('\n');
        if (!"COMPLETE".equals(result.completeness()))
            out.append("注意：抓取未完成，交易量不可作最终统计。\n");
        for (int i = 0; i < result.volumeRanks().size(); i++) {
            var item = result.volumeRanks().get(i);
            String line = "账户 " + (i + 1) + "：" + decimal(item.volume()) + "，约第 " + item.rank() + " 名\n";
            if (out.length() + line.length() > 3800) { out.append("更多账户排名请查看页面。\n"); break; }
            out.append(line);
        }
        return out.toString();
    }

    private static String beijing(String iso) {
        if (iso == null) return "接口未提供";
        try { return BEIJING.format(Instant.parse(iso)) + "（北京时间）"; }
        catch (RuntimeException e) { return "时间格式无效"; }
    }
    private static String decimal(BigDecimal value) {
        if (value == null) return "—";
        String plain = value.toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        return whole.replaceAll("\\B(?=(\\d{3})+(?!\\d))", ",") + (dot < 0 ? "" : plain.substring(dot));
    }
}
