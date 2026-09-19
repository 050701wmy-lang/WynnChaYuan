package com.wynnchayuan.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** One bounded queue and one I/O worker; render callers never wait for disk or network. */
public final class AiTranslationService implements AutoCloseable {
    static final Logger LOG = LoggerFactory.getLogger("WynnChaYuan-AI");
    private static final int CONCURRENCY = 2;
    private static final int MAX_PENDING = 64;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wynnchayuan-ai");
        thread.setDaemon(true);
        return thread;
    });
    private final AiTranslationCache cache;
    private final Path configFile;
    private final Function<AiTranslationConfig, AiTranslationProvider> providers;
    private final LongSupplier now;
    private final AtomicLong sessionEpoch = new AtomicLong();
    private final AtomicLong revision = new AtomicLong();
    private final Map<String, Job> inFlight = new HashMap<>();
    private final ArrayDeque<Job> pending = new ArrayDeque<>();
    private final Map<String, Long> failures = new HashMap<>();
    private volatile AiTranslationConfig config;
    private volatile boolean loaded;
    private boolean closed;
    private boolean active;
    private int running;
    private long providerCooldown;

    private static final class Job {
        final String key, source;
        final TranslationContext context;
        final AiTranslationConfig config;
        final long epoch;
        Job(String key, String source, TranslationContext context,
            AiTranslationConfig config, long epoch) {
            this.key = key; this.source = source; this.context = context;
            this.config = config; this.epoch = epoch;
        }
    }

    public AiTranslationService(Path directory) {
        this(directory, AiTranslationConfig.load(directory.resolve("ai.json")),
                new Function<>() {
                    private HttpClient client;
                    @Override public AiTranslationProvider apply(AiTranslationConfig config) {
                        // Lazily created on the I/O worker, never on a render caller.
                        if (client == null) client = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(10))
                                .followRedirects(HttpClient.Redirect.NEVER).build();
                        return new OpenAiCompatibleProvider(client, config);
                    }
                }, System::currentTimeMillis);
    }

    /** Injectable provider/clock make concurrency, failure and session races testable offline. */
    AiTranslationService(Path directory, AiTranslationConfig config,
                         Function<AiTranslationConfig, AiTranslationProvider> providers,
                         LongSupplier now) {
        this.config = config;
        this.providers = providers;
        this.now = now;
        configFile = directory.resolve("ai.json");
        cache = new AiTranslationCache(directory.resolve("ai-cache/cache.json"));
        io.execute(() -> {
            try { cache.load(); }
            catch (RuntimeException e) { LOG.warn("[AI Translation] Cannot read cache"); }
            finally { loaded = true; revision.incrementAndGet(); }
        });
        io.scheduleWithFixedDelay(cache::flush, 1, 1, TimeUnit.SECONDS);
    }

    public AiTranslationConfig config() { return config; }
    public long revision() { return revision.get(); }
    public long epoch() { return sessionEpoch.get(); }
    public boolean loaded() { return loaded; }

    public synchronized void configure(AiTranslationConfig value) {
        if (closed || config.equals(value)) return;
        config = value;
        invalidate();
        io.execute(() -> value.save(configFile));
    }

    /** Called on join, disconnect, world/character change, and translation-language changes. */
    public synchronized void sessionChanged(boolean active) {
        this.active = active;
        invalidate();
    }

    private void invalidate() {
        sessionEpoch.incrementAndGet();
        revision.incrementAndGet();
        pending.clear();
        inFlight.clear();
        failures.clear();
        providerCooldown = 0;
        // Running transports still occupy their slots until their bounded timeout/completion.
        // Clearing a map must not create more than two simultaneous HTTP requests.
    }

    /** Null means the caller keeps its original fallback for this frame. */
    public synchronized String lookupOrRequest(String source, TranslationContext context) {
        return lookupOrRequest(source, context, true);
    }

    public synchronized String lookupOrRequest(String source, TranslationContext context,
                                               boolean allowRequest) {
        if (closed || !active || !loaded || !config.enabled()
                || source == null || source.isBlank() || source.length() > 4000) return null;
        String key = AiTranslationCache.key(source, context);
        String hit = cache.get(key);
        if (hit != null) return hit;
        if (!allowRequest || !config.ready() || inFlight.containsKey(key) || pending.size() >= MAX_PENDING) return null;
        long time = now.getAsLong();
        if (time < providerCooldown || time < failures.getOrDefault(key, 0L)) return null;
        failures.entrySet().removeIf(e -> e.getValue() <= time);
        Job job = new Job(key, source, context, config, sessionEpoch.get());
        inFlight.put(key, job);
        pending.add(job);
        LOG.debug("[AI Translation] Queued ({})", context.translationType());
        io.execute(this::pump);
        return null;
    }

    private void pump() {
        while (true) {
            Job job;
            synchronized (this) {
                if (closed || now.getAsLong() < providerCooldown
                        || running >= CONCURRENCY || pending.isEmpty()) return;
                job = pending.remove();
                if (job.epoch != sessionEpoch.get()) continue;
                running++;
            }
            CompletableFuture<String> future;
            try {
                future = providers.apply(job.config).translate(job.source, job.context);
            } catch (RuntimeException e) {
                future = CompletableFuture.failedFuture(e);
            }
            future.orTimeout(35, TimeUnit.SECONDS).whenCompleteAsync(
                    (translation, error) -> finish(job, translation, error), io);
        }
    }

    private synchronized void finish(Job job, String translation, Throwable error) {
        running--;
        inFlight.remove(job.key, job);
        if (closed) return;
        if (job.epoch != sessionEpoch.get()) { io.execute(this::pump); return; }
        String result = translation == null ? null : translation.strip();
        if (error == null && PlaceholderValidator.valid(job.source, result)) {
            cache.put(job.key, new AiTranslationCache.Entry(job.source, result,
                    job.context.targetLanguage(), job.context.translationType(),
                    "openai-compatible", job.config.model(), AiPromptBuilder.VERSION));
            revision.incrementAndGet();
            LOG.debug("[AI Translation] Success ({})", job.context.translationType());
        } else {
            long delay = 30_000;
            Throwable cause = error;
            while (cause != null && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof OpenAiCompatibleProvider.ProviderFailure failure) {
                delay = failure.status == 429 ? 120_000 : 30_000;
                if (failure.status == 401 || failure.status == 403) delay = 300_000;
                LOG.warn("[AI Translation] HTTP {}; cooling down", failure.status);
            } else {
                LOG.debug("[AI Translation] {}", error == null
                        ? "Placeholder/output validation failed" : "Request failed");
            }
            // Network/auth failures affect all texts, validation failures only this text.
            if (error != null) {
                providerCooldown = now.getAsLong() + delay;
                for (Job queued : pending) inFlight.remove(queued.key, queued);
                pending.clear();
            }
            if (failures.size() >= 1024) failures.clear();
            failures.put(job.key, now.getAsLong() + delay);
        }
        io.execute(this::pump);
    }

    public synchronized void clearCache() {
        if (closed) return;
        invalidate();
        loaded = false;
        cache.clear();
        io.execute(() -> {
            cache.clear();
            cache.flush();
            loaded = true;
            revision.incrementAndGet();
        });
    }

    /** All queued disk writes finish without blocking the Minecraft thread. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        invalidate();
        io.execute(cache::flush);
        io.shutdown();
    }
}
