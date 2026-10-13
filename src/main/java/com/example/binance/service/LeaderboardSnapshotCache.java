package com.example.binance.service;

import com.example.binance.model.LeaderboardEntry;
import com.example.binance.model.LeaderboardStatistics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Complete, full-rank snapshots, keyed by resource ID and Binance's updatedTime. */
public class LeaderboardSnapshotCache {
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    public LeaderboardSnapshotCache(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public Optional<LeaderboardStatistics> latest(String resourceId) throws IOException {
        Path directory = directory(resourceId);
        if (!Files.isDirectory(directory)) return Optional.empty();
        Path newest;
        try (Stream<Path> files = Files.list(directory)) {
            newest = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("[0-9]{1,18}\\.json"))
                    .max(Comparator.comparingLong(path -> Long.parseLong(path.getFileName().toString().replace(".json", ""))))
                    .orElse(null);
        }
        if (newest == null) return Optional.empty();
        try {
            LeaderboardStatistics full = mapper.readValue(newest.toFile(), LeaderboardStatistics.class);
            long filenameTime = Long.parseLong(newest.getFileName().toString().replace(".json", ""));
            if (!valid(full, resourceId, filenameTime)) throw new IOException("缓存校验失败");
            return Optional.of(full);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("缓存读取失败", e);
        }
    }

    public void save(LeaderboardStatistics full) throws IOException {
        if (full.leaderboardUpdatedAt() == null) throw new IOException("接口未提供榜单更新时间");
        long updated = Instant.parse(full.leaderboardUpdatedAt()).toEpochMilli();
        if (!valid(full, full.resourceId(), updated)) throw new IOException("仅允许缓存完整的全榜数据");
        Path directory = directory(full.resourceId());
        Files.createDirectories(directory);
        restrict(root, true);
        restrict(directory, true);
        Path temporary = Files.createTempFile(directory, ".snapshot-", ".tmp");
        try {
            restrict(temporary, false);
            mapper.writeValue(temporary.toFile(), full);
            Path destination = directory.resolve(updated + ".json");
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            restrict(destination, false);
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path directory(String resourceId) {
        if (resourceId == null || !resourceId.matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("缓存 resourceId 无效");
        return root.resolve(resourceId);
    }

    private static boolean valid(LeaderboardStatistics full, String resourceId, long updated) {
        if (full == null || !resourceId.equals(full.resourceId()) || full.startRank() != 1 ||
                !"COMPLETE".equals(full.completeness()) || full.leaderboardUpdatedAt() == null ||
                full.entries() == null || full.entries().isEmpty() || full.totalVolume() == null ||
                full.rankFilteredCount() != full.entries().size()) return false;
        try {
            if (Instant.parse(full.leaderboardUpdatedAt()).toEpochMilli() != updated) return false;
        } catch (RuntimeException e) { return false; }
        BigDecimal sum = BigDecimal.ZERO;
        long position = 0;
        LeaderboardEntry previous = null;
        for (LeaderboardEntry entry : full.entries()) {
            position++;
            if (entry == null || entry.volume() == null || entry.volume().signum() < 0 || entry.rank() < 1 ||
                    (previous != null && (entry.volume().compareTo(previous.volume()) > 0 ||
                    (entry.rank() != position && !(entry.volume().compareTo(previous.volume()) == 0 && entry.rank() == previous.rank())))))
                return false;
            if (previous == null && entry.rank() != 1) return false;
            sum = sum.add(entry.volume());
            previous = entry;
        }
        return sum.compareTo(full.totalVolume()) == 0 &&
                (full.serverEligibleUserCount() == null || full.serverEligibleUserCount() == full.entries().size()) &&
                (full.serverEligibleTradingVolume() == null || sum.compareTo(full.serverEligibleTradingVolume()) == 0);
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) { /* Non-POSIX filesystem. */ }
    }
}
