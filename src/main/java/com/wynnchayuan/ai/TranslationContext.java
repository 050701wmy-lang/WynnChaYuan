package com.wynnchayuan.ai;

import java.util.Map;

public record TranslationContext(String targetLanguage, String translationType,
                                 String speaker, String questName,
                                 Map<String, String> relevantGlossary) {
    public TranslationContext {
        relevantGlossary = relevantGlossary == null ? Map.of() : Map.copyOf(relevantGlossary);
    }
}
