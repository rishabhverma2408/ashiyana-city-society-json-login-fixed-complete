package com.society.management.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Stores JSON lists either on the local filesystem (local/dev) or in Postgres
 * when DATABASE_URL is set (Render/Neon). Local files on Render free are wiped
 * on restart, so production must use DATABASE_URL.
 */
@Service
public class JsonStorageService {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path dataDir = Paths.get(System.getenv().getOrDefault("DATA_DIR", "data")).toAbsolutePath().normalize();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final boolean useDatabase;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public JsonStorageService() {
        mapper.findAndRegisterModules();
        DbConfig db = resolveDatabase();
        this.useDatabase = db != null;
        this.jdbcUrl = db == null ? null : db.url;
        this.dbUser = db == null ? null : db.user;
        this.dbPassword = db == null ? null : db.password;

        try {
            if (useDatabase) {
                initDatabase();
            } else {
                Files.createDirectories(dataDir);
                ensureFile("users.json");
                ensureFile("members.json");
                ensureFile("payments.json");
                ensureFile("expenses.json");
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    useDatabase
                            ? "Unable to initialize database JSON storage"
                            : "Unable to initialize JSON storage: " + dataDir,
                    e);
        }
    }

    private void ensureFile(String name) throws IOException {
        Path file = dataDir.resolve(name);
        if (!Files.exists(file)) {
            Files.writeString(file, "[]", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    private void initDatabase() throws Exception {
        try (Connection c = openConnection()) {
            c.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS json_store (" +
                            "file_name VARCHAR(64) PRIMARY KEY, " +
                            "content TEXT NOT NULL" +
                            ")"
            );
        }
        seedDatabaseFile("users.json", defaultUsersJson());
        seedDatabaseFile("members.json", "[]");
        seedDatabaseFile("payments.json", "[]");
        seedDatabaseFile("expenses.json", "[]");
    }

    private void seedDatabaseFile(String file, String defaultJson) throws Exception {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO json_store(file_name, content) VALUES (?, ?) " +
                             "ON CONFLICT (file_name) DO NOTHING")) {
            ps.setString(1, file);
            ps.setString(2, defaultJson);
            ps.executeUpdate();
        }
    }

    private String defaultUsersJson() throws Exception {
        List<Map<String, Object>> users = new ArrayList<>();
        users.add(Map.of("id", 1, "username", "7007478334", "password", "123456", "role", "ADMIN"));
        users.add(Map.of("id", 2, "username", "8796854510", "password", "123456", "role", "SECRETARY"));
        return mapper.writeValueAsString(users);
    }

    public List<Map<String, Object>> read(String file) {
        lock.readLock().lock();
        try {
            String json = useDatabase ? readFromDatabase(file) : readFromDisk(file);
            if (json == null || json.isBlank()) return new ArrayList<>();
            List<Map<String, Object>> result = mapper.readValue(
                    json,
                    new TypeReference<List<Map<String, Object>>>() {}
            );
            return result == null ? new ArrayList<>() : result;
        } catch (Exception e) {
            throw new RuntimeException("Unable to read " + file, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void write(String file, List<Map<String, Object>> data) {
        lock.writeLock().lock();
        try {
            String json = mapper.writeValueAsString(data == null ? new ArrayList<>() : data);
            if (useDatabase) {
                writeToDatabase(file, json);
            } else {
                writeToDisk(file, json);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to write " + file + ": " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private String readFromDisk(String file) throws IOException {
        Path path = dataDir.resolve(file);
        if (!Files.exists(path)) return "[]";
        return Files.readString(path);
    }

    private void writeToDisk(String file, String json) throws IOException {
        Files.createDirectories(dataDir);
        Path target = dataDir.resolve(file);
        Path temp = dataDir.resolve(file + ".tmp");
        Files.writeString(temp, json);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException e) {
            Files.deleteIfExists(target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readFromDatabase(String file) throws Exception {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content FROM json_store WHERE file_name = ?")) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                return "[]";
            }
        }
    }

    private void writeToDatabase(String file, String json) throws Exception {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO json_store(file_name, content) VALUES (?, ?) " +
                             "ON CONFLICT (file_name) DO UPDATE SET content = EXCLUDED.content")) {
            ps.setString(1, file);
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
    }

    public synchronized long nextId(String file) {
        return read(file).stream()
                .mapToLong(x -> number(x.get("id")))
                .max()
                .orElse(0) + 1;
    }

    public static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static DbConfig resolveDatabase() {
        String raw = firstNonBlank(
                System.getenv("DATABASE_URL"),
                System.getenv("JDBC_DATABASE_URL"),
                System.getProperty("DATABASE_URL")
        );
        if (raw == null) return null;
        try {
            return parseDatabaseUrl(raw.trim());
        } catch (Exception e) {
            throw new RuntimeException("Invalid DATABASE_URL. Use a Neon/Render Postgres URL.", e);
        }
    }

    private static DbConfig parseDatabaseUrl(String raw) throws Exception {
        if (raw.startsWith("jdbc:postgresql://")) {
            return new DbConfig(raw, null, null);
        }

        // Neon/Render style: postgres://user:pass@host:port/db?sslmode=require
        String normalized = raw.replace("postgres://", "postgresql://");
        URI uri = URI.create(normalized);
        String userInfo = uri.getUserInfo();
        String user = null;
        String password = null;
        if (userInfo != null) {
            int idx = userInfo.indexOf(':');
            if (idx >= 0) {
                user = userInfo.substring(0, idx);
                password = userInfo.substring(idx + 1);
            } else {
                user = userInfo;
            }
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getQuery();
        String jdbc = "jdbc:postgresql://" + host + ":" + port + path;
        if (query != null && !query.isBlank()) {
            jdbc += "?" + query;
        } else if (!jdbc.contains("sslmode=")) {
            jdbc += "?sslmode=require";
        }
        return new DbConfig(jdbc, user, password);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private record DbConfig(String url, String user, String password) {}
}
