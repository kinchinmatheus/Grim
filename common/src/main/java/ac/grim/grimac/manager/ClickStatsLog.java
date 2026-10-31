package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.init.ReloadableInitable;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.init.stop.StoppableInitable;
import ac.grim.grimac.utils.anticheat.LogUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

// Writes the click burst summaries from ClickStatsCollector to click-stats/<date>.jsonl, one JSON object per line.
// Records arrive from netty threads; only flush() touches the files. Files past retention are deleted even while
// recording is disabled, so turning it off never leaves player data behind for longer.
public final class ClickStatsLog implements StartableInitable, StoppableInitable, ReloadableInitable {
    private static final String DIRECTORY_NAME = "click-stats";
    private static final String FILE_EXTENSION = ".jsonl";
    // Decided 2026-10-31: a week is enough to download and analyze the files
    private static final int RETENTION_DAYS = 7;
    private static final long FLUSH_PERIOD_SECONDS = 10;

    private final Queue<String> pendingRecords = new ConcurrentLinkedQueue<>();
    private volatile boolean enabled;
    private LocalDate lastRetentionDate;

    public boolean isEnabled() {
        return enabled;
    }

    public void record(String record) {
        pendingRecords.add(record);
    }

    @Override
    public void start() {
        reload();
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(GrimAPI.INSTANCE.getGrimPlugin(), this::flush,
                FLUSH_PERIOD_SECONDS, FLUSH_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void reload() {
        enabled = GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("click-stats.enabled", false);
    }

    @Override
    public void stop() {
        flush();
    }

    private synchronized void flush() {
        final Path directory = GrimAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath().resolve(DIRECTORY_NAME);
        final LocalDate today = LocalDate.now();
        if (!today.equals(lastRetentionDate)) {
            deleteExpiredFiles(directory, today);
            lastRetentionDate = today;
        }

        final List<String> records = new ArrayList<>();
        for (String record = pendingRecords.poll(); record != null; record = pendingRecords.poll()) {
            records.add(record);
        }
        if (records.isEmpty()) return;

        final Path file = directory.resolve(today + FILE_EXTENSION);
        try {
            Files.createDirectories(directory);
            Files.write(file, records, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LogUtil.error("Failed to append " + records.size() + " click stats records to " + file, e);
        }
    }

    private static void deleteExpiredFiles(Path directory, LocalDate today) {
        if (!Files.isDirectory(directory)) return;

        final LocalDate newestExpired = today.minusDays(RETENTION_DAYS);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + FILE_EXTENSION)) {
            for (Path file : files) {
                final String name = file.getFileName().toString();
                final LocalDate date;
                try {
                    date = LocalDate.parse(name.substring(0, name.length() - FILE_EXTENSION.length()));
                } catch (DateTimeParseException e) {
                    // Not a file this class wrote
                    continue;
                }
                if (!date.isAfter(newestExpired)) {
                    Files.delete(file);
                }
            }
        } catch (IOException e) {
            LogUtil.error("Failed to delete click stats files older than " + RETENTION_DAYS + " days in " + directory, e);
        }
    }
}
