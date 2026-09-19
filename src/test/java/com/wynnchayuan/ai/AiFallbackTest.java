package com.wynnchayuan.ai;

import com.wynnchayuan.WynnChaYuan;
import com.wynnchayuan.translate.LineTranslator;
import com.wynnchayuan.translate.TranslationStore;
import com.wynntils.core.text.StyledText;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Exercises the actual official lookup -> AI -> Component reconstruction path. */
public final class AiFallbackTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path dir = Files.createTempDirectory("wcy-ai-fallback");
        Path corpus = Files.createDirectories(dir.resolve("official"));
        Files.writeString(corpus.resolve("test.json"), "{\"Welcome home!\":\"正式欢迎！\"}");
        TranslationStore store = new TranslationStore();
        store.loadAll(corpus);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> submitted = new AtomicReference<>();
        CompletableFuture<String> answer = new CompletableFuture<>();
        var config = new AiTranslationConfig(true, "https://example.invalid", "test", "test", "zh_cn");
        var field = WynnChaYuan.class.getDeclaredField("ai");
        field.setAccessible(true);
        Object before = field.get(null);
        try (var ai = new AiTranslationService(dir, config, cfg -> (source, context) -> {
            calls.incrementAndGet(); submitted.set(source); return answer;
        }, System::currentTimeMillis)) {
            field.set(null, ai);
            ai.sessionChanged(true);
            await(ai::loaded);
            var official = LineTranslator.translate(StyledText.fromString("Welcome home!"), store);
            check(official != null && official.getString().equals("正式欢迎！"), "official output");
            check(calls.get() == 0, "official hit never calls provider");
            var source = StyledText.fromString("The mysterious clock chimes 42 times.");
            for (int i = 0; i < 100; i++) check(LineTranslator.translate(source, store) == null, "original pending");
            await(() -> calls.get() == 1);
            check(submitted.get().equals("The mysterious clock chimes {~} times."), "normalized input, no dynamic number");
            answer.complete("神秘时钟响了 {~} 次。");
            await(() -> LineTranslator.translate(source, store) != null);
            check(LineTranslator.translate(source, store).getString().equals("神秘时钟响了 42 次。"), "reuses native placeholder reconstruction");
            check(!store.hasTranslation(submitted.get()), "AI does not conceal official corpus gaps");
            check(calls.get() == 1, "cache hit avoids HTTP");
            Files.writeString(corpus.resolve("test.json"), "{\"The mysterious clock chimes {~} times.\":\"正式时钟响 {~} 次。\"}");
            store.loadAll(corpus);
            check(LineTranslator.translate(source, store).getString().equals("正式时钟响 42 次。"), "new official translation supersedes AI cache");
            check(calls.get() == 1, "official upgrade does not call provider");
            ai.configure(new AiTranslationConfig(false, config.baseUrl(), config.apiKey(), config.model(), ""));
            check(LineTranslator.translate(StyledText.fromString("A previously unseen sentence."), store) == null, "disabled fallback");
            check(calls.get() == 1, "disabled AI never sends requests");
        } finally { field.set(null, before); }
        tooltipRegression(field, before);
        System.out.println("AI fallback integration checks passed");
    }

    private static void tooltipRegression(java.lang.reflect.Field field, Object before) throws Exception {
        TranslationStore store = new TranslationStore();
        store.loadAll(Path.of("src/main/resources/assets/wynnchayuan/translations/zh_cn"));
        store.setTranslateNames(true);
        var ownBuild = com.wynnchayuan.capture.OwnOutputs.class.getDeclaredMethod("build");
        ownBuild.setAccessible(true);
        ownBuild.invoke(null);
        var config = new AiTranslationConfig(true, "https://example.invalid", "test", "test", "zh_cn");
        try (var ai = new AiTranslationService(Files.createTempDirectory("wcy-tooltip"), config,
                cfg -> (source, context) -> CompletableFuture.completedFuture(
                        source.replace("Unidentified Dagger", "未鉴定匕首")
                                .replace("Rubble", "碎石").replace("Junk Item", "垃圾物品")),
                System::currentTimeMillis)) {
            field.set(null, ai);
            ai.sessionChanged(true);
            await(ai::loaded);
            var tooltip = java.util.List.of(net.minecraft.network.chat.Component.literal("Rubble"),
                    net.minecraft.network.chat.Component.literal("Junk Item"));
            await(() -> com.wynnchayuan.render.TooltipPanel.translateInPlace(
                    new java.util.ArrayList<net.minecraft.network.chat.Component>(tooltip), store)
                    .stream().anyMatch(c -> c.getString().contains("碎石")));
            await(() -> com.wynnchayuan.render.TooltipPanel.translateInPlace(
                    new java.util.ArrayList<net.minecraft.network.chat.Component>(tooltip), store)
                    .stream().anyMatch(c -> c.getString().contains("垃圾物品")));
            String dagger = "{#}{#}{#}{#}{#}{#}{#}Unidentified Dagger";
            var context = new TranslationContext("zh_cn", "TEXT", null, null, null);
            await(() -> ai.lookupOrRequest(dagger, context) != null);
            check(ai.lookupOrRequest(dagger, context).equals(
                    "{#}{#}{#}{#}{#}{#}{#}未鉴定匕首"), "generic item label preserves seven glyph slots");
        } finally { field.set(null, before); }
    }

    private static void await(BooleanSupplier ready) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > limit) throw new AssertionError("Timed out");
            Thread.sleep(5);
        }
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
