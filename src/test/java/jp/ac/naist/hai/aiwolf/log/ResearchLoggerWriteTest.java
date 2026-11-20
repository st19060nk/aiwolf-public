package jp.ac.naist.hai.aiwolf.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.ac.naist.hai.aiwolf.demo.DemoMode;
import jp.ac.naist.hai.aiwolf.study.StudySession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 25 P0-1：非同期の書き込みが直列化され、停止時に何も失われないことの検証。
 *
 * <p>書き込みスレッドには<strong>複数スレッドのプール</strong>を使う。直列化していなければ、
 * 連続した flush の書き込みが別々のスレッドで同時に走り、ファイル内の順序が入れ替わる。</p>
 */
final class ResearchLoggerWriteTest {

    private static final Pattern SEQUENCE = Pattern.compile("\"logSequence\":(\\d+)");

    @TempDir
    Path directory;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        StudySession.reset();
        DemoMode.reset();
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        ResearchLogger.shutdown();
        ResearchLogger.resetExecutorForTest();
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    private List<Long> sequences(String file) throws IOException {
        Path path = directory.resolve(file);
        List<Long> values = new ArrayList<>();
        if (!Files.exists(path)) {
            return values;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Matcher matcher = SEQUENCE.matcher(line);
            assertTrue(matcher.find(), line);
            values.add(Long.parseLong(matcher.group(1)));
        }
        return values;
    }

    @Test
    @DisplayName("連続した flush を複数スレッドのプールで書いても、ファイル内の順序が保たれ、重複も欠落もない")
    void consecutiveFlushesKeepOrder() throws Exception {
        // 書き込みを遅らせて、直列化していなければ後の flush が追い越せるようにする。
        CountDownLatch slowFirst = new CountDownLatch(1);
        ExecutorService slow = Executors.newFixedThreadPool(8);
        ResearchLogger.startForTest(directory, task -> slow.execute(() -> {
            if (slowFirst.getCount() > 0) {
                slowFirst.countDown();
                sleep(200);
            }
            task.run();
        }), "run-test-order");
        int perFlush = 50;
        int flushes = 40;
        for (int f = 0; f < flushes; f++) {
            for (int i = 0; i < perFlush; i++) {
                ResearchLogger.logDecision("{}");
                ResearchLogger.logEvent("{}");
            }
            ResearchLogger.flush();
        }
        ResearchLogger.flush().get(30, TimeUnit.SECONDS);
        slow.shutdown();

        for (String file : List.of(ResearchLogger.DECISIONS_FILE, ResearchLogger.EVENTS_FILE)) {
            List<Long> values = sequences(file);
            assertEquals(perFlush * flushes, values.size(), file + "：欠落・重複なし");
            for (int i = 1; i < values.size(); i++) {
                assertTrue(values.get(i) > values.get(i - 1), file + "：順序が入れ替わった位置 " + i);
            }
            assertEquals(values.size(), new HashSet<>(values).size(), "同じ行を二重に書かない");
        }
    }

    @Test
    @DisplayName("停止直前に記録した行も、停止時にすべて書き出される")
    void shutdownWritesTheLastRecords() throws Exception {
        ResearchLogger.startForTest(directory, pool, "run-test-shutdown");
        for (int i = 0; i < 25; i++) {
            ResearchLogger.logPlayerSample("{}");
        }
        ResearchLogger.logSession("{\"event\":\"SESSION_STOP\"}");
        ResearchLogger.shutdown();

        assertEquals(25, sequences(ResearchLogger.PLAYER_SAMPLES_FILE).size());
        List<String> sessions = Files.readAllLines(directory.resolve(ResearchLogger.SESSIONS_FILE));
        assertEquals(1, sessions.size());
        assertTrue(sessions.get(0).contains("SESSION_STOP"));
        assertEquals(0, ResearchLogger.pendingBatchesForTest(), "停止の時点で未完了の書き込みがない");
    }

    @Test
    @DisplayName("書き込みの途中で停止しても、それまでの書き込みと停止直前の行がすべて残る")
    void shutdownDuringAWriteLosesNothing() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService blocking = Executors.newSingleThreadExecutor();
        ResearchLogger.startForTest(directory, task -> blocking.execute(() -> {
            writing.countDown();
            await(release);
            task.run();
        }), "run-test-midflight");
        for (int i = 0; i < 100; i++) {
            ResearchLogger.logDecision("{}");
        }
        ResearchLogger.flush();
        assertTrue(writing.await(10, TimeUnit.SECONDS), "最初の書き込みが始まった");
        for (int i = 0; i < 30; i++) {
            ResearchLogger.logDecision("{}");   // 書き込み中に記録した行
        }
        Thread releaser = new Thread(() -> {
            sleep(100);
            release.countDown();
        });
        releaser.start();
        ResearchLogger.shutdown();               // 書き込みの完了を待ってから片付ける
        releaser.join();
        blocking.shutdown();

        List<Long> values = sequences(ResearchLogger.DECISIONS_FILE);
        assertEquals(130, values.size());
        Set<Long> unique = new HashSet<>(values);
        assertEquals(130, unique.size());
        for (int i = 1; i < values.size(); i++) {
            assertTrue(values.get(i) > values.get(i - 1));
        }
    }

    @Test
    @DisplayName("書くものがない flush も、それまでの書き込みの完了を待てる")
    void emptyFlushWaitsForPendingWrites() throws Exception {
        ResearchLogger.startForTest(directory, pool, "run-test-empty");
        ResearchLogger.logEvent("{}");
        ResearchLogger.flush();
        ResearchLogger.flush().get(10, TimeUnit.SECONDS);
        assertEquals(1, sequences(ResearchLogger.EVENTS_FILE).size());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
