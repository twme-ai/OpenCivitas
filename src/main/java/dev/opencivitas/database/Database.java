package dev.opencivitas.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Database implements AutoCloseable {
    private final String jdbcUrl;
    private final ExecutorService executor;

    public Database(Path file) {
        jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
        executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("opencivitas-db").factory());
    }

    public void initialize(JavaPlugin plugin) throws SQLException, IOException {
        try (InputStream stream = plugin.getResource("schema.sql")) {
            if (stream == null) {
                throw new IOException("Missing schema.sql resource");
            }
            initialize(stream);
        }
    }

    public void initialize(InputStream schema) throws SQLException, IOException {
        String script = new String(schema.readAllBytes(), StandardCharsets.UTF_8);
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            executeScript(statement, script);
        }
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception exception) {
                throw new DatabaseException("Database operation failed", exception);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void executeScript(Statement statement, String script) throws SQLException {
        for (String command : script.split(";")) {
            if (!command.isBlank()) {
                statement.execute(command);
            }
        }
    }
}
