package com.example.binance.util;

import com.example.binance.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ResultWriter {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(LeaderboardStatistics result, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path json = directory.resolve("result-" + result.resourceId() + ".json");
        Path csv = directory.resolve("result-" + result.resourceId() + ".csv");
        mapper.writeValue(json.toFile(), result);
        StringBuilder out = new StringBuilder();
        if (result.completeness().equals("INCOMPLETE")) out.append("# INCOMPLETE / 未完成，不可作为最终统计\n");
        out.append("rank,userId,volume\n");
        for (LeaderboardEntry entry : result.entries())
            out.append(entry.rank()).append(',').append(csvCell(entry.userId())).append(',')
                    .append(entry.volume().toPlainString()).append('\n');
        Files.writeString(csv, out, StandardCharsets.UTF_8);
    }

    private String csvCell(String value) {
        if (value == null) return "";
        // 防止电子表格把来自接口的用户标识当作公式执行。
        String safe = value.matches("^[=+@-].*") ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
