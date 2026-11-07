package com.vedashree.game.analytics;

import com.vedashree.game.AppContext;
import com.vedashree.game.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Background housekeeping on one daemon thread: ends idle visits (every minute), applies the
 * retention policy (hourly), purges rate-limit memory, and writes rolling database backups.
 * Each task catches everything, because an uncaught exception would silently cancel the schedule.
 */
public final class MaintenanceScheduler implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(MaintenanceScheduler.class.getName());
    private static final String BACKUP_PREFIX = "neon-backup-";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "neon-maintenance");
        thread.setDaemon(true);
        return thread;
    });

    public MaintenanceScheduler(AppContext app) {
        executor.scheduleWithFixedDelay(safe("sweep", () -> sweep(app)), 60, 60, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(safe("retention", () -> retention(app)), 2, 60, TimeUnit.MINUTES);
        int hours = app.config().backupIntervalHours();
        if (hours > 0 && app.database() != null) {
            executor.scheduleWithFixedDelay(safe("backup", () -> backup(app)), 10, hours * 60L, TimeUnit.MINUTES);
        }
    }

    private static Runnable safe(String name, ThrowingRunnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Maintenance task '" + name + "' failed", t);
            }
        };
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void sweep(AppContext app) throws Exception {
        if (app.analytics() != null) {
            int closed = app.analytics().closeIdleVisits();
            if (closed > 0) {
                LOG.fine("Closed " + closed + " idle visits");
            }
        }
        app.eventLimiter().purgeExpired();
        app.newVisitorLimiter().purgeExpired();
        app.consentLimiter().purgeExpired();
        app.adminLimiter().purgeExpired();
        app.loginThrottle().purgeExpired();
    }

    private static void retention(AppContext app) throws Exception {
        if (app.analytics() != null) {
            int deleted = app.analytics().enforceRetention();
            LOG.info("Retention job removed " + deleted + " expired analytics rows");
        }
    }

    private static void backup(AppContext app) throws Exception {
        Database database = app.database();
        String configured = app.config().backupDir();
        Path dir = configured != null ? Paths.get(configured) : Database.defaultDirectory().resolve("backups");
        Files.createDirectories(dir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now(ZoneOffset.UTC));
        database.backupTo(dir.resolve(BACKUP_PREFIX + stamp + ".zip"));
        pruneBackups(dir, app.config().backupKeep());
        LOG.info("Database backup written to " + dir);
    }

    static void pruneBackups(Path dir, int keep) throws IOException {
        List<Path> backups;
        try (Stream<Path> files = Files.list(dir)) {
            backups = files.filter(p -> p.getFileName().toString().startsWith(BACKUP_PREFIX)
                    && p.getFileName().toString().endsWith(".zip")).sorted().toList();
        }
        for (int i = 0; i < backups.size() - keep; i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
