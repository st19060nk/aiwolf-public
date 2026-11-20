package jp.ac.naist.hai.aiwolf.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.world.World;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.log.MemoryLogJson;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.mc.WolfAiRegistry;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;

/**
 * 研究データのスナップショット出力（{@code /wolfai export}）。
 *
 * <p>実行ごとにタイムスタンプ付きのディレクトリを作り、その時点の全個体を書き出す。
 * 既存のファイルを上書きしないので、実験の途中経過を何度でも保存できる。</p>
 *
 * <p>スナップショットの収集はサーバスレッドで行い、ファイル書き込みだけを
 * IO ワーカスレッドへ回す。収集中に個体が変化しない状態を保ちつつ、
 * ディスク待ちでサーバを止めないためである。</p>
 */
public final class ResearchExportService {

    /** エクスポート先ディレクトリ名の形式。 */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private ResearchExportService() {
        throw new AssertionError("utility class");
    }

    /**
     * 現時点の研究データを書き出す。
     *
     * @param server サーバ
     * @return 書き出し先ディレクトリ
     * @throws IOException 出力先を用意できなかった場合
     */
    public static Path export(MinecraftServer server) throws IOException {
        Path root = ResearchLogger.directory();
        if (root == null) {
            throw new IOException("research log directory is not initialised");
        }
        Path target = root.resolve("export-" + TIMESTAMP.format(Instant.now()));
        Files.createDirectories(target);

        // 収集はサーバスレッドで。ここで一貫したスナップショットを作る。
        List<WolfAiAgent> agents = new ArrayList<>();
        for (WolfAiController controller : WolfAiRegistry.all().values()) {
            agents.add(controller.agent());
        }

        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        long worldTime = overworld == null ? 0L : overworld.getTime();
        String wolvesJson = WolfRecordJson.toWolvesJson(agents, worldTime,
                System.currentTimeMillis(), WolfAiRegistry.experimentSeed());

        List<String> memoryLines = new ArrayList<>();
        for (WolfAiAgent agent : agents) {
            for (WolfMemory memory : agent.memoryStore().memories()) {
                memoryLines.add(MemoryLogJson.toJsonLine(agent.wolfId(), memory));
            }
        }

        // 追記中のログも取り込めるよう、先に書き出しを促す。
        ResearchLogger.flush();

        CompletableFuture.runAsync(() -> {
            try {
                Files.writeString(target.resolve("wolves.json"), wolvesJson, StandardCharsets.UTF_8);
                Files.write(target.resolve("memories.jsonl"), memoryLines, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }, Util.getIoWorkerExecutor());

        return target;
    }
}
