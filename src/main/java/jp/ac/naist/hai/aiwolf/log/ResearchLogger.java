package jp.ac.naist.hai.aiwolf.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.demo.DemoMode;
import jp.ac.naist.hai.aiwolf.study.StudySession;

/**
 * 研究ログの JSONL 書き出し。
 *
 * <p><strong>サーバスレッドを長時間ブロックしない。</strong>行はメモリ上のバッファへ積み、
 * 一定間隔（既定 30 秒）またはバッファが一杯になったときだけ、IO ワーカスレッドで
 * まとめて追記する。書き込み中もサーバは止まらない。</p>
 *
 * <p>出力先はワールド内の {@code aiwolf-research/} である。ワールドと一緒に残るので、
 * セーブデータを渡せば研究データも付いてくる。</p>
 *
 * <p>すべての行の先頭には共通項目（{@link LogEnvelope}：{@code logEnvelopeVersion} / {@code runId} /
 * {@code sessionId} / {@code phaseId}）をここで 1 か所だけ付ける。セッションとフェーズは
 * <strong>記録した時点</strong>のもので、書き出しの前に変わっても混ざらない。</p>
 */
public final class ResearchLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("AIWolfResearch");

    /** 決定ログのファイル名。 */
    public static final String DECISIONS_FILE = "decisions.jsonl";

    /** 出来事ログのファイル名。 */
    public static final String EVENTS_FILE = "events.jsonl";

    /** 行動指紋ログのファイル名。 */
    public static final String SIGNATURES_FILE = "signatures.jsonl";

    /**
     * 被弾 1 発ごとの記録。学習用の出来事ログとは別物で、
     * 決定ごとの集約の前に何が起きていたかを検証するために残す。
     */
    public static final String DAMAGE_FILE = "damage.jsonl";

    /** セッションの開始・終了・フェーズ変更（Phase 20）。 */
    public static final String SESSIONS_FILE = "sessions.jsonl";

    /** プレイヤーの行動 1 件ごと（Phase 21）。 */
    public static final String PLAYER_EVENTS_FILE = "player-events.jsonl";

    /** プレイヤーとオオカミの関係の定期サンプル（Phase 21、既定 1 秒ごと）。 */
    public static final String PLAYER_SAMPLES_FILE = "player-samples.jsonl";

    /** AI が決定できなかった区間の開始・終了（Phase 22）。 */
    public static final String AI_AVAILABILITY_FILE = "ai-availability.jsonl";

    /** 本実験用のバッファ（ファイル名 → 行）。 */
    private static final Map<String, List<String>> RESEARCH_BUFFERS = new LinkedHashMap<>();

    /**
     * Demo Mode 中に記録された行。ファイル名 → 行。本実験のバッファとは完全に分け、
     * {@code aiwolf-research/demo/} へ書き出す。振り分けは記録した時点の
     * Demo Mode で決まるので、書き出しの前にモードを切り替えても混ざらない。
     */
    private static final Map<String, List<String>> DEMO_BUFFERS = new LinkedHashMap<>();

    private static Path directory;

    /** この実行（サーバ起動）の ID。すべての行の先頭に付ける。 */
    private static String runId;

    /**
     * 実行内の通し番号（Phase 25B）。本実験・Demo Mode・すべてのファイルで共有し、記録した順に 1 から振る。
     * サーバ起動ごと（新しい runId）に 0 へ戻す。
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /**
     * バッファと通し番号を守るロック。研究ログは基本的にサーバスレッドから書かれるが、
     * 番号の採番とバッファへの追加を 1 つの操作にして、ファイル内の行の順序と番号の順序が必ず一致するようにする。
     */
    private static final Object LOCK = new Object();

    /**
     * 書き込みの連鎖の末尾（Phase 25 P0-1）。flush のたびに「前の書き込みが終わってから次を書く」形でつなぐので、
     * 書き込みが同時に走ったり、順序が入れ替わったりしない。{@link #LOCK} の中でだけ読み書きする。
     */
    private static CompletableFuture<Void> pendingWrite = CompletableFuture.completedFuture(null);

    /** まだ終わっていない書き込みの数（停止時に未完了を報告するため）。 */
    private static final AtomicInteger PENDING_BATCHES = new AtomicInteger();

    /** 書き込みを実行するスレッド。{@code null} なら Minecraft の IO ワーカ（テストでは差し替える）。 */
    private static Executor writeExecutor;

    /** 停止時に書き込みの完了を待つ上限 [秒]。超えたら未完了をサーバのログに明記する（書き込み自体は止めない）。 */
    static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;
    private static long lastFlushTick;

    private ResearchLogger() {
        throw new AssertionError("static logger");
    }

    /**
     * 出力先を用意する。サーバ開始時に呼ぶ。
     *
     * @param server サーバ
     */
    public static void start(MinecraftServer server) {
        try {
            runId = LogEnvelope.newRunId(java.time.ZonedDateTime.now());
            synchronized (LOCK) {
                SEQUENCE.set(0L);
                pendingWrite = CompletableFuture.completedFuture(null);
            }
            directory = server.getSavePath(WorldSavePath.ROOT).resolve(AiWolfParameters.RESEARCH_DIRECTORY);
            Files.createDirectories(directory);
            LOGGER.info("Research logs will be written to {} (runId {})", directory, runId);
        } catch (IOException exception) {
            directory = null;
            LOGGER.error("Could not create the research log directory; logging is disabled", exception);
        }
    }

    /** @return この実行の ID。未初期化なら {@code null} */
    public static String runId() {
        return runId;
    }

    /** @return 研究データの出力先。未初期化なら {@code null} */
    public static Path directory() {
        return directory;
    }

    /** @param jsonLine 決定 1 件の JSON 1 行 */
    public static void logDecision(String jsonLine) {
        route(DECISIONS_FILE, jsonLine);
    }

    /** @param jsonLine 出来事 1 件の JSON 1 行 */
    public static void logEvent(String jsonLine) {
        route(EVENTS_FILE, jsonLine);
    }

    /**
     * 被弾 1 発を記録する。
     *
     * <p>学習用の出来事とは別の系統で、内部状態も選好もここからは動かない。
     * 集約前に何が起きていたかを後から検証するためだけに残す。</p>
     *
     * @param jsonLine 1 行の JSON
     */
    public static void logDamage(String jsonLine) {
        route(DAMAGE_FILE, jsonLine);
    }

    /** @param jsonLine 行動指紋 1 件の JSON 1 行 */
    public static void logSignature(String jsonLine) {
        route(SIGNATURES_FILE, jsonLine);
    }

    /** @param jsonLine セッションの出来事 1 件の JSON 1 行 */
    public static void logSession(String jsonLine) {
        route(SESSIONS_FILE, jsonLine);
    }

    /** @param jsonLine プレイヤーの行動 1 件の JSON 1 行 */
    public static void logPlayerEvent(String jsonLine) {
        route(PLAYER_EVENTS_FILE, jsonLine);
    }

    /** @param jsonLine プレイヤーとオオカミの関係のサンプル 1 件 */
    public static void logPlayerSample(String jsonLine) {
        route(PLAYER_SAMPLES_FILE, jsonLine);
    }

    /** @param jsonLine AI の停止・再開 1 件 */
    public static void logAiAvailability(String jsonLine) {
        route(AI_AVAILABILITY_FILE, jsonLine);
    }

    /**
     * サーバ tick ごとに呼ばれ、間隔に達していれば書き出す。
     *
     * @param server サーバ
     */
    public static void tick(MinecraftServer server) {
        long ticks = server.getTicks();
        if (ticks - lastFlushTick < AiWolfParameters.RESEARCH_LOG_FLUSH_INTERVAL_TICKS) {
            return;
        }
        lastFlushTick = ticks;
        flush();
    }

    /**
     * 溜まっている行をすべて書き出す。サーバ停止時とエクスポート時に呼ぶ。
     *
     * @return 書き込み完了を表す future
     */
    public static CompletableFuture<Void> flush() {
        synchronized (LOCK) {
            if (directory == null || (RESEARCH_BUFFERS.isEmpty() && DEMO_BUFFERS.isEmpty())) {
                // 書くものがなくても、それまでに投げた書き込みの完了は待てるように連鎖の末尾を返す。
                return pendingWrite;
            }
            // サーバスレッド上でバッファを取り出してから、書き込みだけを別スレッドへ渡す。
            Map<String, List<String>> research = drain(RESEARCH_BUFFERS);
            Map<String, List<String>> demo = drain(DEMO_BUFFERS);
            Path target = directory;
            Executor executor = writeExecutor != null ? writeExecutor : Util.getIoWorkerExecutor();
            PENDING_BATCHES.incrementAndGet();
            // 取り出しと連鎖への追加を同じロックの中で行うので、バッファから取り出した順に書かれる。
            // 前の書き込みが失敗しても連鎖は止めない（handle で例外を握りつぶし、次の書き込みへ進む）。
            pendingWrite = pendingWrite.handle((ignored, error) -> null).thenRunAsync(() -> {
                try {
                    write(target, research, demo);
                } finally {
                    PENDING_BATCHES.decrementAndGet();
                }
            }, executor);
            return pendingWrite;
        }
    }

    private static void write(Path target, Map<String, List<String>> research, Map<String, List<String>> demo) {
        research.forEach((file, lines) -> append(target.resolve(file), lines));
        if (!demo.isEmpty()) {
            Path demoDirectory = target.resolve(DemoMode.LOG_SUBDIRECTORY);
            try {
                Files.createDirectories(demoDirectory);
            } catch (IOException exception) {
                LOGGER.error("Could not create the demo log directory {}; {} demo file(s) not written",
                        demoDirectory, demo.size(), exception);
                return;
            }
            demo.forEach((file, lines) -> append(demoDirectory.resolve(file), lines));
        }
    }

    /**
     * 出力先と内部状態を破棄する。ワールドを閉じるときに呼ぶ。
     *
     * <p>順序：最後のバッファを書き出しに回す → それまでに投げた書き込みがすべて終わるのを待つ → 片付け。
     * 待ちが {@link #SHUTDOWN_TIMEOUT_SECONDS} 秒を超えたら、時間切れであることと未完了の書き込みの数をサーバのログに出す。
     * その場合も書き込みは取り消さない（IO スレッドで続き、出力先は書き込みごとに保持しているので片付けの影響を受けない）。</p>
     */
    public static void shutdown() {
        CompletableFuture<Void> all = flush();
        try {
            all.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            LOGGER.error("Research log writes did not finish within {} s at shutdown; {} write batch(es) were still "
                    + "incomplete and continue in the background. Check the logs of run {} for missing lines.",
                    SHUTDOWN_TIMEOUT_SECONDS, PENDING_BATCHES.get(), runId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while waiting for research log writes at shutdown; {} write batch(es) incomplete",
                    PENDING_BATCHES.get());
        } catch (ExecutionException exception) {
            LOGGER.error("A research log write failed at shutdown", exception.getCause());
        }
        directory = null;
        runId = null;
        lastFlushTick = 0L;
        synchronized (LOCK) {
            RESEARCH_BUFFERS.clear();
            DEMO_BUFFERS.clear();
            SEQUENCE.set(0L);
        }
    }

    /**
     * テスト用：Minecraft のサーバなしで出力先と書き込みスレッドを設定する（サーバ起動時の {@link #start} と同じ初期化）。
     *
     * @param target   出力先
     * @param executor 書き込みを実行するスレッド
     * @param run      実行 ID
     */
    static void startForTest(Path target, Executor executor, String run) {
        synchronized (LOCK) {
            directory = target;
            writeExecutor = executor;
            runId = run;
            SEQUENCE.set(0L);
            RESEARCH_BUFFERS.clear();
            DEMO_BUFFERS.clear();
            pendingWrite = CompletableFuture.completedFuture(null);
        }
    }

    /** テスト用：書き込みスレッドを Minecraft の IO ワーカへ戻す。 */
    static void resetExecutorForTest() {
        writeExecutor = null;
    }

    /** @return まだ終わっていない書き込みの数（テスト用） */
    static int pendingBatchesForTest() {
        return PENDING_BATCHES.get();
    }

    /**
     * 書き出し前の行を取り出して空にする（テスト用。出力先が未初期化でも使える）。
     *
     * @param fileName ファイル名
     * @param demo     Demo Mode 用のバッファなら true
     * @return 行（記録順）
     */
    static List<String> drainForTest(String fileName, boolean demo) {
        synchronized (LOCK) {
            List<String> lines = (demo ? DEMO_BUFFERS : RESEARCH_BUFFERS).remove(fileName);
            return lines == null ? List.of() : lines;
        }
    }

    /**
     * 1 行に共通項目を付け、記録時点の Demo Mode に応じて本実験用またはデモ用のバッファへ積む。
     *
     * <p>Demo Mode off のときは、共通項目のあとは従来とまったく同じ行が入る。</p>
     */
    private static void route(String fileName, String rawLine) {
        boolean full;
        synchronized (LOCK) {
            String jsonLine = LogEnvelope.envelope(rawLine, runId == null ? "run-unknown" : runId,
                    StudySession.current(), SEQUENCE.incrementAndGet());
            if (DemoMode.isEnabled()) {
                DEMO_BUFFERS.computeIfAbsent(fileName, key -> new ArrayList<>())
                        .add(DemoMode.tagLogLine(jsonLine, DemoMode.isEvaluationActive()));
            } else {
                RESEARCH_BUFFERS.computeIfAbsent(fileName, key -> new ArrayList<>()).add(jsonLine);
            }
            full = bufferedLines() >= AiWolfParameters.RESEARCH_LOG_BUFFER_LIMIT;
        }
        if (full) {
            flush();
        }
    }

    /** @return 次に振る通し番号の 1 つ前（テスト用） */
    static long lastSequenceForTest() {
        return SEQUENCE.get();
    }

    /** 通し番号を 0 に戻す（テスト用。サーバ起動時の動作と同じ）。 */
    static void resetSequenceForTest() {
        SEQUENCE.set(0L);
    }

    private static Map<String, List<String>> drain(Map<String, List<String>> buffers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        buffers.forEach((file, lines) -> copy.put(file, List.copyOf(lines)));
        buffers.clear();
        return copy;
    }

    private static int bufferedLines() {
        int lines = 0;
        for (List<String> buffer : RESEARCH_BUFFERS.values()) {
            lines += buffer.size();
        }
        for (List<String> buffer : DEMO_BUFFERS.values()) {
            lines += buffer.size();
        }
        return lines;
    }

    private static void append(Path file, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        try {
            Files.write(file, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            LOGGER.error("Failed to append {} line(s) to {}", lines.size(), file, exception);
        }
    }
}
