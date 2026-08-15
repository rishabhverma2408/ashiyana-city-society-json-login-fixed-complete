package com.society.management.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class JsonStorageService {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path dataDir = Paths.get("data").toAbsolutePath().normalize();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonStorageService() {
        mapper.findAndRegisterModules();
        try {
            Files.createDirectories(dataDir);
            ensureFile("users.json");
            ensureFile("members.json");
            ensureFile("payments.json");
            ensureFile("expenses.json");
        } catch (IOException e) {
            throw new RuntimeException("Unable to initialize JSON storage: " + dataDir, e);
        }
    }

    private void ensureFile(String name) throws IOException {
        Path file = dataDir.resolve(name);
        if (!Files.exists(file)) {
            Files.writeString(file, "[]", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    public List<Map<String, Object>> read(String file) {
        lock.readLock().lock();
        try {
            Path path = dataDir.resolve(file);
            if (!Files.exists(path)) {
                // Reading a missing file should not fail the application.
                return new ArrayList<>();
            }
            String json = Files.readString(path);
            if (json.isBlank()) return new ArrayList<>();
            List<Map<String, Object>> result = mapper.readValue(
                    json,
                    new TypeReference<List<Map<String, Object>>>() {}
            );
            return result == null ? new ArrayList<>() : result;
        } catch (Exception e) {
            throw new RuntimeException("Unable to read " + file + " from " + dataDir, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void write(String file, List<Map<String, Object>> data) {
        lock.writeLock().lock();
        try {
            Files.createDirectories(dataDir);
            Path target = dataDir.resolve(file);
            Path temp = dataDir.resolve(file + ".tmp");

            mapper.writeValue(temp.toFile(), data == null ? new ArrayList<>() : data);

            try {
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileSystemException e) {
                // Windows can reject a replace/move when a file is temporarily locked.
                Files.deleteIfExists(target);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to write " + file + " to " + dataDir + ": " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
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
}
