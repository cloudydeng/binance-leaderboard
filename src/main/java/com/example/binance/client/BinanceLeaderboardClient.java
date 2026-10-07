package com.example.binance.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import okhttp3.*;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class BinanceLeaderboardClient {
    public static final String ENDPOINT = "https://www.binance.com/bapi/growth/v1/friendly/growth-paas/resource/summary/list";
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final HttpUrl url;
    private final Map<String, String> credentials;

    public BinanceLeaderboardClient() {
        this(HttpUrl.get(ENDPOINT), System.getenv(), new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(30)).followRedirects(false).build());
    }

    // 注入 URL 仅供 MockWebServer 测试；命令行始终使用上方固定的 Binance 官方地址。
    public BinanceLeaderboardClient(HttpUrl url, Map<String, String> credentials, OkHttpClient http) {
        this.url = url;
        this.credentials = credentials;
        this.http = http;
    }

    public JsonNode fetchPage(String resourceId, int pageIndex, int pageSize) throws IOException, InterruptedException {
        byte[] payload = mapper.writeValueAsBytes(Map.of("resourceId", Long.parseLong(resourceId),
                "leaderboardType", "USER", "pageIndex", pageIndex, "pageSize", pageSize));
        Request.Builder builder = new Request.Builder().url(url).post(RequestBody.create(payload, MediaType.get("application/json")))
                .header("Accept", "*/*").header("lang", "zh-CN").header("clienttype", "web")
                .header("User-Agent", "Mozilla/5.0 (compatible; BinanceLeaderboardStat/1.0)");
        addSecret(builder, "Cookie", "BINANCE_COOKIE");
        addSecret(builder, "csrftoken", "BINANCE_CSRF_TOKEN");
        addSecret(builder, "bnc-uuid", "BINANCE_UUID");
        Request request = builder.build();
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response response = http.newCall(request).execute()) {
                int status = response.code();
                if (status == 401 || status == 403) throw new IOException("HTTP " + status + "：身份验证或访问权限失败；请检查 BINANCE_COOKIE 等环境变量");
                if (status == 429 || status >= 500) {
                    if (attempt == 3) throw new IOException("HTTP " + status + "：重试 3 次后仍失败");
                    waitBeforeRetry(response.header("Retry-After"), attempt);
                    continue;
                }
                if (!response.isSuccessful()) throw new IOException("HTTP " + status + "：请求失败");
                if (response.body() == null) throw new IOException("空 HTTP 响应");
                String body = response.body().string();
                JsonNode root;
                try { root = mapper.readTree(body); }
                catch (Exception e) { throw new IOException("响应不是有效 JSON（可能是访问限制页面）", e); }
                if (root == null || !root.isObject()) throw new IOException("响应 JSON 顶层不是对象");
                JsonNode success = root.get("success");
                if (success != null && success.isBoolean() && !success.booleanValue())
                    throw new IOException("Binance 业务请求失败，code=" + safeCode(root));
                JsonNode code = root.get("code");
                if (code != null && !code.isNull() && !code.asText().equals("000000") && !code.asText().equals("0") && !code.asText().equals("200"))
                    throw new IOException("Binance 业务码异常，code=" + safeCode(root));
                return root;
            } catch (IOException e) {
                if (e.getMessage() != null && (e.getMessage().startsWith("HTTP ") || e.getMessage().startsWith("Binance ") || e.getMessage().startsWith("响应"))) throw e;
                last = e;
                if (attempt == 3) break;
                Thread.sleep(400L * attempt);
            }
        }
        throw new IOException("网络请求重试 3 次后失败：" + (last == null ? "未知错误" : last.getClass().getSimpleName()));
    }

    private String safeCode(JsonNode root) {
        String code = root.path("code").asText("unknown");
        return code.matches("[A-Za-z0-9_-]{1,24}") ? code : "unknown";
    }
    private void addSecret(Request.Builder builder, String header, String environment) {
        String value = credentials.get(environment);
        if (value != null && !value.isBlank()) builder.header(header, value);
    }
    private void waitBeforeRetry(String retryAfter, int attempt) throws InterruptedException, IOException {
        long millis = 500L * attempt;
        if (retryAfter != null) {
            try {
                if (retryAfter.matches("[0-9]+")) millis = Math.multiplyExact(Long.parseLong(retryAfter), 1000L);
                else millis = Math.max(0, Duration.between(Instant.now(),
                        ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
            } catch (Exception ignored) { /* 无效 Retry-After 使用有限退避。 */ }
        }
        if (millis > 60_000L) throw new IOException("HTTP 429：Retry-After 超过 60 秒，停止请求以尊重服务端限流");
        Thread.sleep(millis);
    }
}
