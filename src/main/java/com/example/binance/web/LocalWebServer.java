package com.example.binance.web;

import com.example.binance.config.Options;
import com.example.binance.model.LeaderboardStatistics;
import com.example.binance.service.LeaderboardStatisticsService;
import com.example.binance.util.ResultWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 仅在 127.0.0.1 提供配置页；不会在浏览器接收或保存 Binance 凭据。 */
public class LocalWebServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final Set<String> FIELDS = Set.of("resourceId", "startRank", "pageSize", "maxPages", "delayMs",
            "rewardPool", "rewardUnit", "maxReward", "volumes", "entriesPath", "rankField",
            "volumeField", "userIdField", "outputDir");
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final LeaderboardStatisticsService.PageSource source;
    private final Path defaultOutputDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String csrfToken;
    private final String allowedOrigin;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot("IDLE", "等待开始", 0, 0, null, null));

    private record Snapshot(String status, String message, int pagesRequested, int recordsRead,
                            LeaderboardStatistics result, Path outputDir) {}

    public LocalWebServer(int port, LeaderboardStatisticsService.PageSource source, Path defaultOutputDir) throws IOException {
        this(port, source, defaultOutputDir, null);
    }

    public LocalWebServer(int port, LeaderboardStatisticsService.PageSource source, Path defaultOutputDir,
                          String allowedOrigin) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("webPort 必须在 0–65535 之间");
        if (allowedOrigin != null) {
            URI origin;
            try { origin = URI.create(allowedOrigin); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("webOrigin 必须是有效的 HTTP(S) 来源地址"); }
            if (!("https".equals(origin.getScheme()) || "http".equals(origin.getScheme())) ||
                    origin.getHost() == null || origin.getRawUserInfo() != null || origin.getRawQuery() != null ||
                    origin.getRawFragment() != null || (origin.getRawPath() != null && !origin.getRawPath().isEmpty()))
                throw new IllegalArgumentException("webOrigin 必须是无路径的 HTTP(S) 来源地址");
        }
        this.source = source;
        this.allowedOrigin = allowedOrigin;
        this.defaultOutputDir = defaultOutputDir.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(workers);
        byte[] token = new byte[24];
        new SecureRandom().nextBytes(token);
        this.csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public String url() { return "http://127.0.0.1:" + port() + "/"; }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!validHost(exchange)) { sendJson(exchange, 403, Map.of("error", "仅允许本机页面访问")); return; }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/")) { staticFile(exchange, "/web/index.html", "text/html; charset=utf-8"); return; }
            if (method.equals("GET") && path.equals("/style.css")) { staticFile(exchange, "/web/style.css", "text/css; charset=utf-8"); return; }
            if (method.equals("GET") && path.equals("/app.js")) { staticFile(exchange, "/web/app.js", "text/javascript; charset=utf-8"); return; }
            if (method.equals("GET") && path.equals("/api/config")) { sendJson(exchange, 200, Map.of("csrfToken", csrfToken)); return; }
            if (method.equals("GET") && path.equals("/api/status")) { sendJson(exchange, 200, statusPayload()); return; }
            if (method.equals("POST") && path.equals("/api/run")) { startRun(exchange); return; }
            if (method.equals("GET") && path.equals("/api/result.json")) { download(exchange, "json", "application/json; charset=utf-8"); return; }
            if (method.equals("GET") && path.equals("/api/result.csv")) { download(exchange, "csv", "text/csv; charset=utf-8"); return; }
            sendJson(exchange, 404, Map.of("error", "页面不存在"));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "本地服务处理失败"));
        } finally {
            exchange.close();
        }
    }

    private boolean validHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        return ("127.0.0.1:" + port()).equals(host) || ("localhost:" + port()).equals(host);
    }
    private boolean validOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equals("http://127.0.0.1:" + port()) ||
                origin.equals("http://localhost:" + port()) || origin.equals(allowedOrigin);
    }
    private boolean validCsrf(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        return supplied != null && MessageDigest.isEqual(csrfToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private void startRun(HttpExchange exchange) throws IOException {
        if (!validOrigin(exchange) || !validCsrf(exchange)) { sendJson(exchange, 403, Map.of("error", "页面请求验证失败，请刷新页面")); return; }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            sendJson(exchange, 415, Map.of("error", "请求必须使用 JSON")); return;
        }
        int declared = 0;
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try { declared = Integer.parseInt(length); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("请求长度无效"); }
            if (declared > MAX_REQUEST_BYTES) { sendJson(exchange, 413, Map.of("error", "配置内容过长")); return; }
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) { sendJson(exchange, 413, Map.of("error", "配置内容过长")); return; }
        JsonNode root;
        try { root = mapper.readTree(body); }
        catch (Exception e) { throw new IllegalArgumentException("配置 JSON 格式无效"); }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("配置必须是 JSON 对象");
        List<String> args = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!FIELDS.contains(field.getKey())) throw new IllegalArgumentException("未知配置项：" + field.getKey());
            JsonNode value = field.getValue();
            if (!value.isTextual() && !value.isNumber()) throw new IllegalArgumentException("配置值格式无效：" + field.getKey());
            String text = value.asText().trim();
            if (text.length() > 4096) throw new IllegalArgumentException("配置值过长：" + field.getKey());
            if (!text.isEmpty()) args.add("--" + field.getKey() + "=" + text);
        }
        Options options = Options.parse(args.toArray(String[]::new));
        boolean customOutputDir = !root.path("outputDir").asText("").isBlank();
        if (customOutputDir && !options.outputDir().isAbsolute()) throw new IllegalArgumentException("导出目录必须填写绝对路径");
        Path outputDir = customOutputDir ? options.outputDir().normalize() : defaultOutputDir;
        synchronized (this) {
            if (snapshot.get().status().equals("RUNNING")) { sendJson(exchange, 409, Map.of("error", "已有抓取任务正在运行")); return; }
            snapshot.set(new Snapshot("RUNNING", "正在抓取排行榜", 0, 0, null, null));
            workers.submit(() -> runJob(options, outputDir));
        }
        sendJson(exchange, 202, Map.of("status", "RUNNING"));
    }

    private void runJob(Options options, Path outputDir) {
        try {
            var service = new LeaderboardStatisticsService(source, progress -> snapshot.updateAndGet(current ->
                    current.status().equals("RUNNING") ? new Snapshot("RUNNING", "正在抓取排行榜",
                            progress.pagesRequested(), progress.recordsRead(), null, null) : current));
            LeaderboardStatistics result = service.collect(options);
            new ResultWriter().write(result, outputDir);
            snapshot.set(new Snapshot(result.completeness(), result.completeness().equals("COMPLETE") ? "抓取完成" : "抓取未完成，请查看原因",
                    result.pagesRequested(), result.rankFilteredCount() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result.rankFilteredCount(), result, outputDir));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            snapshot.set(new Snapshot("ERROR", "抓取被中断", 0, 0, null, null));
        } catch (Exception e) {
            snapshot.set(new Snapshot("ERROR", "执行失败：" + e.getMessage(), 0, 0, null, null));
        }
    }

    private Map<String, Object> statusPayload() {
        Snapshot current = snapshot.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", current.status());
        data.put("message", current.message());
        data.put("pagesRequested", current.pagesRequested());
        data.put("recordsRead", current.recordsRead());
        LeaderboardStatistics result = current.result();
        if (result != null) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("resourceId", result.resourceId());
            summary.put("startRank", result.startRank());
            summary.put("maxRank", result.maxRank());
            summary.put("fetchedAt", result.fetchedAt());
            summary.put("completeness", result.completeness());
            summary.put("issues", result.issues());
            summary.put("rankFilteredCount", result.rankFilteredCount());
            summary.put("totalVolume", decimal(result.totalVolume()));
            summary.put("averageVolume", displayDecimal(result.averageVolume()));
            summary.put("rank1000Volume", decimal(result.rank1000Volume()));
            summary.put("qualificationStatus", result.qualificationStatus());
            summary.put("serverEligibleUserCount", result.serverEligibleUserCount());
            summary.put("serverEligibleTradingVolume", decimal(result.serverEligibleTradingVolume()));
            var reward = result.rewardEstimate();
            if (reward != null) {
                Map<String, Object> estimate = new LinkedHashMap<>();
                estimate.put("rewardPool", decimal(reward.rewardPool()));
                estimate.put("rewardUnit", reward.rewardUnit());
                estimate.put("maxReward", decimal(reward.maxReward()));
                estimate.put("rewardPer1000", displayDecimal(reward.rewardPer1000()));
                estimate.put("rewardPer10000", displayDecimal(reward.rewardPer10000()));
                estimate.put("capVolume", displayDecimal(reward.capVolume()));
                estimate.put("accounts", reward.accounts().stream().map(account -> Map.of(
                        "volume", decimal(account.volume()), "raw", displayDecimal(account.raw()), "capped", displayDecimal(account.capped()))).toList());
                summary.put("rewardEstimate", estimate);
            }
            data.put("result", summary);
        }
        return data;
    }

    private void download(HttpExchange exchange, String extension, String type) throws IOException {
        Snapshot current = snapshot.get();
        if (current.result() == null || current.outputDir() == null) { sendJson(exchange, 404, Map.of("error", "暂无结果文件")); return; }
        String filename = "result-" + current.result().resourceId() + "." + extension;
        Path file = current.outputDir().resolve(filename);
        if (!Files.isRegularFile(file)) { sendJson(exchange, 404, Map.of("error", "结果文件不存在")); return; }
        byte[] bytes = Files.readAllBytes(file);
        headers(exchange, type);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private void staticFile(HttpExchange exchange, String resource, String type) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) { sendJson(exchange, 404, Map.of("error", "页面资源不存在")); return; }
            byte[] bytes = in.readAllBytes();
            headers(exchange, type);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
    private void sendJson(HttpExchange exchange, int code, Object object) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(object);
        headers(exchange, "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private void headers(HttpExchange exchange, String type) {
        var h = exchange.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'self'");
    }
    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private static String displayDecimal(BigDecimal value) {
        return value == null ? null : value.round(new MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString();
    }
}
