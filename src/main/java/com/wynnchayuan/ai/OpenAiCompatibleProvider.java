package com.wynnchayuan.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class OpenAiCompatibleProvider implements AiTranslationProvider {
    private final HttpClient client;
    private final AiTranslationConfig config;

    public OpenAiCompatibleProvider(HttpClient client, AiTranslationConfig config) {
        this.client = client;
        this.config = config;
    }

    static URI endpoint(String base) {
        URI uri = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid API base URL");
        }
        return uri;
    }

    @Override public CompletableFuture<String> translate(String source, TranslationContext context) {
        try {
            JsonArray messages = new JsonArray();
            messages.add(message("system", AiPromptBuilder.system()));
            messages.add(message("user", AiPromptBuilder.user(source, context)));
            JsonObject body = new JsonObject();
            body.addProperty("model", config.model());
            body.add("messages", messages);
            body.addProperty("stream", false);
            HttpRequest request = HttpRequest.newBuilder(endpoint(config.baseUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            // Never log the response body: providers can echo credentials/source.
                            throw new ProviderFailure(response.statusCode());
                        }
                        if (response.body().length() > 128 * 1024) {
                            throw new IllegalArgumentException("Oversized response");
                        }
                        var root = JsonParser.parseString(response.body()).getAsJsonObject();
                        var choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                                && !"stop".equals(choice.get("finish_reason").getAsString())) {
                            throw new IllegalArgumentException("Incomplete response");
                        }
                        var content = choice.getAsJsonObject("message").get("content");
                        if (content == null || !content.isJsonPrimitive()
                                || !content.getAsJsonPrimitive().isString()) {
                            throw new IllegalArgumentException("Missing translation");
                        }
                        return content.getAsString();
                    });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    public static final class ProviderFailure extends RuntimeException {
        public final int status;
        public ProviderFailure(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }
}
