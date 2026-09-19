package com.wynnchayuan.ai;

import com.wynnchayuan.WynnChaYuan;
import com.wynnchayuan.capture.CurrentQuest;
import com.wynnchayuan.capture.GlyphSplitter;
import com.wynnchayuan.capture.OwnOutputs;
import com.wynnchayuan.capture.PlayerDataFilter;
import com.wynnchayuan.translate.TranslationStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Small bridge at final display misses. TranslationStore remains an official-only index. */
public final class AiTranslations {
    private static final AtomicLong officialRevision = new AtomicLong();
    private static final Map<String, Stable> dialogue = new LinkedHashMap<>();
    private record Stable(String source, long since) {}
    private AiTranslations() {}

    public static long revision() {
        var service = WynnChaYuan.ai();
        return officialRevision.get() + (service == null ? 0 : service.revision());
    }

    public static void officialChanged() { officialRevision.incrementAndGet(); }

    public static void sessionChanged(boolean active) {
        synchronized (dialogue) { dialogue.clear(); }
        CurrentQuest.set(null);
        if (WynnChaYuan.ai() != null) WynnChaYuan.ai().sessionChanged(active);
    }

    public static String fallback(String source, String type, TranslationStore store) {
        return fallback(source, type, store, true);
    }

    public static String fallback(String source, String type, TranslationStore store,
                                  boolean allowRequest) {
        var service = WynnChaYuan.ai();
        if (service == null || !service.config().enabled() || source == null) return null;
        source = source.strip();
        if (source.isBlank() || !GlyphSplitter.hasLetter(PlaceholderValidator.withoutTokens(source))
                || PlayerDataFilter.carriesPlayerData(source)
                || PlayerDataFilter.looksPlayerNamed(source)
                || OwnOutputs.isOwn(source) || store.isBareGearName(source)
                || store.hasTranslation(source)) return null;
        // Relevant runtime terms already follow the chosen language and its official fallback.
        String lang = service.config().language(WynnChaYuan.language());
        Map<String, String> glossary = new LinkedHashMap<>();
        for (int offset = 0; lang.equals(WynnChaYuan.language())
                && offset < source.length() && glossary.size() < 12;) {
            var term = store.findTerm(source, offset);
            if (term == null) break;
            glossary.put(source.substring(term.start(), term.end()), term.translation());
            offset = Math.max(offset + 1, term.end());
        }
        var context = new TranslationContext(lang, type, null,
                "DIALOGUE".equals(type) ? CurrentQuest.get() : null, glossary);
        return service.lookupOrRequest(source, context, allowRequest);
    }

    /** Time, not FPS, controls admission of previously unknown typewriter text. */
    public static boolean settled(String source, String slot) {
        long now = System.nanoTime();
        synchronized (dialogue) {
            Stable previous = dialogue.get(slot);
            if (previous == null || !previous.source().equals(source)) {
                if (dialogue.size() >= 32) dialogue.clear();
                dialogue.put(slot, new Stable(source, now));
                return false;
            }
            return now - previous.since() >= 800_000_000L;
        }
    }
}
