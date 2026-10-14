package com.example.binance.square;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Text-only Binance Square posting with a separate creator posting key. */
public final class BinanceSquarePublisher {
    public record Published(String id, String link) {}

    private static final URI POST_URL = URI.create(
            "https://www.binance.com/bapi/composite/v1/public/pgc/openApi/content/add");
    private final URI url;
    private final String key;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public BinanceSquarePublisher(URI url, String key, HttpClient http) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("币安广场 Posting API Key 为空");
        this.url = url;
        this.key = key;
        this.http = http;
    }

    public static BinanceSquarePublisher fromEnvironment() {
        String key = System.getenv("BINANCE_SQUARE_OPENAPI_KEY");
        if (key == null || key.isBlank()) return null;
        return new BinanceSquarePublisher(POST_URL, key.trim(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public Published publish(String text) throws IOException, InterruptedException {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("币安广场内容不能为空");
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(20))
                .header("X-Square-OpenAPI-Key", key)
                .header("Content-Type", "application/json")
                .header("clienttype", "binanceSkill")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("contentType", 1, "bodyTextOnly", text))))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // Binance's official Square client treats a 504 after submission as accepted with no post ID.
        if (response.statusCode() == 504) return new Published(null, null);
        if (response.statusCode() / 100 != 2)
            throw new IOException("HTTP " + response.statusCode());
        JsonNode json;
        try { json = mapper.readTree(response.body()); }
        catch (IOException e) { throw new IOException("响应无法解析"); }
        if (!"000000".equals(json.path("code").asText()))
            throw new IOException("接口错误码 " + json.path("code").asText("未知"));
        JsonNode data = json.path("data");
        return new Published(data.path("id").asText(null), data.path("shareLink").asText(null));
    }
}
