package com.example.binance;

import com.example.binance.web.LocalWebServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test void pageRunsConfiguredJobAndExportsResults() throws Exception {
        JsonNode fixture = mapper.readTree("""
                {"code":"000000","success":true,"data":{"list":[
                  {"rank":1,"userId":"masked","tradeVolume":"2"},
                  {"rank":2,"userId":"masked","tradeVolume":"1"}],
                  "total":2,"hasMore":false}}
                """);
        try (LocalWebServer server = new LocalWebServer(0, (id, page, size) -> fixture, temp)) {
            server.start();
            String base = server.url();
            var html = get(base);
            assertEquals(200, html.statusCode());
            assertTrue(html.body().contains("活动 resourceId"));
            assertFalse(html.body().contains("value=\"40000\""));

            String token = mapper.readTree(get(base + "api/config").body()).path("csrfToken").asText();
            String body = """
                    {"resourceId":"12","startRank":"2","pageSize":"2","delayMs":"0",
                     "rewardPool":"10","rewardUnit":"USDC","maxReward":"5","volumes":"1"}
                    """;
            assertEquals(403, post(base, body, "wrong", null).statusCode());
            assertEquals(403, post(base, body, token, "https://evil.example").statusCode());
            assertEquals(400, post(base, "{\"resourceId\":\"12\",\"rewardPool\":\"0\"}", token, null).statusCode());
            assertEquals(400, post(base, "{\"resourceId\":\"12\",\"outputDir\":\"relative-dir\"}", token, null).statusCode());
            assertEquals(202, post(base, body, token, null).statusCode());

            JsonNode status = null;
            for (int i = 0; i < 50; i++) {
                status = mapper.readTree(get(base + "api/status").body());
                if (!status.path("status").asText().equals("RUNNING")) break;
                Thread.sleep(50);
            }
            assertNotNull(status);
            assertEquals("COMPLETE", status.path("status").asText());
            JsonNode result = status.path("result");
            assertEquals(1, result.path("rankFilteredCount").asInt());
            assertEquals("1", result.path("totalVolume").asText());
            assertEquals("5", result.path("rewardEstimate").path("accounts").get(0).path("capped").asText());
            assertEquals(200, get(base + "api/result.json").statusCode());
            assertTrue(get(base + "api/result.csv").body().contains("rank,userId,volume"));
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String base, String body, String token, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "api/run"))
                .header("Content-Type", "application/json").header("X-CSRF-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) request.header("Origin", origin);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
