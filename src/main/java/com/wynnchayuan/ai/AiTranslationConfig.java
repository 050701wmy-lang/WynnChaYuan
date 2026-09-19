package com.wynnchayuan.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wynnchayuan.SafeFiles;
import java.nio.file.Path;

/** Immutable request snapshot. Never include credentials in diagnostics. */
public record AiTranslationConfig(boolean enabled, String baseUrl, String apiKey,
                                  String model, String targetLanguage) {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public AiTranslationConfig {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
        targetLanguage = targetLanguage == null ? "" : targetLanguage.strip();
    }

    public static AiTranslationConfig defaults() {
        return new AiTranslationConfig(false, "", "", "", "");
    }

    public static AiTranslationConfig load(Path file) {
        try {
            var object = SafeFiles.readObject(file, 64 * 1024);
            return object == null ? defaults() : JSON.fromJson(object, AiTranslationConfig.class);
        } catch (RuntimeException e) {
            AiTranslationService.LOG.warn("[AI Translation] Invalid configuration; disabled");
            return defaults();
        }
    }

    public void save(Path file) {
        try {
            SafeFiles.writeAtomically(file, JSON.toJson(this));
        } catch (Exception e) {
            AiTranslationService.LOG.warn("[AI Translation] Cannot save configuration");
        }
    }

    public String language(String currentLanguage) {
        return targetLanguage.isBlank() ? currentLanguage : targetLanguage;
    }

    public boolean ready() {
        return enabled && !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override public String toString() {
        return "AiTranslationConfig[enabled=" + enabled + ", credentials=REDACTED]";
    }
}
