package com.wynnchayuan.ai;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface AiTranslationProvider {
    CompletableFuture<String> translate(String source, TranslationContext context);
}
