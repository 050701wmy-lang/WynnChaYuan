package com.wynnchayuan.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.wynnchayuan.SafeFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Disk operations are called only on the service's I/O worker. */
public final class AiTranslationCache {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 10_000;
    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean dirty;

    public record Entry(String source, String translation, String language, String type,
                        String provider, String model, int promptVersion) {}

    public AiTranslationCache(Path file) { this.file = file; }

    public static String key(String source, TranslationContext context) {
        // Length prefixes avoid delimiter collisions; prompt changes invalidate old entries.
        String language = context.targetLanguage();
        String type = context.translationType();
        String input = AiPromptBuilder.VERSION + ":" + language.length() + ":" + language
                + type.length() + ":" + type + source.length() + ":" + source;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void load() {
        JsonObject root = SafeFiles.readObject(file, 64L * 1024 * 1024);
        if (root == null) return;
        for (var pair : root.entrySet()) {
            try {
                Entry entry = JSON.fromJson(pair.getValue(), Entry.class);
                if (entry == null || entry.promptVersion() != AiPromptBuilder.VERSION
                        || entry.language() == null || entry.type() == null
                        || !PlaceholderValidator.valid(entry.source(), entry.translation())) continue;
                var context = new TranslationContext(entry.language(), entry.type(), null, null, null);
                if (pair.getKey().equals(key(entry.source(), context))) {
                    synchronized (this) { entries.put(pair.getKey(), entry); }
                }
                if (entries.size() >= MAX_ENTRIES) break;
            } catch (RuntimeException ignored) {
                // A malformed entry must not lose other successful translations.
            }
        }
    }

    public synchronized String get(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : entry.translation();
    }

    public synchronized void put(String key, Entry entry) {
        entries.put(key, entry);
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        dirty = true;
    }

    public synchronized void clear() { entries.clear(); dirty = true; }

    public void flush() {
        Map<String, Entry> snapshot;
        synchronized (this) {
            if (!dirty) return;
            snapshot = new LinkedHashMap<>(entries);
            dirty = false;
        }
        try {
            SafeFiles.writeAtomically(file, JSON.toJson(snapshot));
        } catch (Exception e) {
            synchronized (this) { dirty = true; }
            AiTranslationService.LOG.warn("[AI Translation] Cannot persist cache; will retry");
        }
    }
}
