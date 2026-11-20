package jp.ac.naist.hai.aiwolf.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 設定ファイルの場所解決と読み書き。Minecraft 依存はパス解決だけに閉じている。
 *
 * <p>ファイルが無ければ、ランダムな salt を持つ設定を作って書き出す。
 * こうすることで、固定の participant identifier を Git に置かずに、
 * かつ実験を通じて安定した ID を得られる。</p>
 */
public final class AiWolfConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("AIWolfResearch");

    private AiWolfConfigLoader() {
        throw new AssertionError("utility class");
    }

    /**
     * 設定を読み込む。無ければ既定値を書き出して返す。
     *
     * @return 設定
     */
    public static AiWolfConfig loadOrCreate() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(AiWolfConfig.FILE_NAME);
        if (Files.exists(file)) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                AiWolfConfig config = AiWolfConfig.load(reader);
                LOGGER.info("Loaded experiment metadata from {}", file);
                return config;
            } catch (IOException exception) {
                LOGGER.error("Could not read {}; falling back to generated defaults", file, exception);
                return AiWolfConfig.createDefault();
            }
        }

        AiWolfConfig config = AiWolfConfig.createDefault();
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                config.store(writer);
            }
            LOGGER.info("Created {} with a fresh experiment salt. Keep this file for the whole study.",
                    file);
        } catch (IOException exception) {
            LOGGER.error("Could not write {}; using in-memory defaults for this run", file, exception);
        }
        return config;
    }
}
