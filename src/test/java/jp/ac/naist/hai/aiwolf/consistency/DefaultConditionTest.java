package jp.ac.naist.hai.aiwolf.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.config.AiWolfConfig;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 設定した既定条件が本当に新規個体へ適用されることのテスト。
 *
 * <p>Phase 13 までは設定ファイルを読んでログに出しておきながら、レジストリへ
 * 反映する 1 行がなかった。設定に {@code static} と書いても新規個体は既定条件
 * （adaptive）で作られる。実験条件の取り違えは、そのデータを丸ごと使えなくする
 * 種類の誤りで、しかもログを見ても「設定は static だった」としか分からないため
 * 気づきにくい。</p>
 *
 * <p>レジストリ自体は Minecraft に依存するため単体テストできない。ここでは
 * (1) 設定値が正しく読めること、(2) その条件で作られた個体が実際にその条件で
 * 動くこと、(3) 反映する呼び出しがソースに存在すること、の 3 つを固定する。</p>
 */
final class DefaultConditionTest {

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && candidate != null; i++) {
            if (Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("リポジトリのルートが見つからない");
    }

    private static ExperimentalCondition conditionFromConfig(String id) throws IOException {
        return AiWolfConfig.load(new StringReader("defaultCondition=" + id + "\n")).defaultCondition();
    }

    @Test
    @DisplayName("config = adaptive なら新規個体は ADAPTIVE で動く")
    void adaptiveConfigCreatesAdaptiveWolves() throws IOException {
        ExperimentalCondition condition = conditionFromConfig("adaptive");
        assertEquals(ExperimentalCondition.ADAPTIVE, condition);

        WolfAiAgent wolf = new WolfAiAgent("11111111-1111-4111-8111-111111111111", condition, 1L);
        assertEquals(ExperimentalCondition.ADAPTIVE, wolf.condition());
        assertTrue(wolf.condition().usesEpisodicMemory());
        assertTrue(wolf.condition().usesLearnedPreference());
    }

    @Test
    @DisplayName("config = static なら新規個体は STATIC で動く")
    void staticConfigCreatesStaticWolves() throws IOException {
        ExperimentalCondition condition = conditionFromConfig("static");
        assertEquals(ExperimentalCondition.STATIC, condition);

        WolfAiAgent wolf = new WolfAiAgent("22222222-2222-4222-8222-222222222222", condition, 1L);
        assertEquals(ExperimentalCondition.STATIC, wolf.condition());
        assertTrue(!wolf.condition().usesEpisodicMemory());
        assertTrue(!wolf.condition().usesLearnedPreference());
    }

    @Test
    @DisplayName("config = state_only なら新規個体は STATE_ONLY で動く")
    void stateOnlyConfigCreatesStateOnlyWolves() throws IOException {
        ExperimentalCondition condition = conditionFromConfig("state_only");
        assertEquals(ExperimentalCondition.STATE_ONLY, condition);

        WolfAiAgent wolf = new WolfAiAgent("33333333-3333-4333-8333-333333333333", condition, 1L);
        assertEquals(ExperimentalCondition.STATE_ONLY, wolf.condition());
        assertTrue(!wolf.condition().usesEpisodicMemory());
        assertTrue(!wolf.condition().usesLearnedPreference());
    }

    @Test
    @DisplayName("起動時に既定条件をレジストリへ反映している")
    void modInitialisationAppliesTheConfiguredCondition() throws IOException {
        String source = Files.readString(
                repositoryRoot().resolve("src/main/java/jp/ac/naist/hai/aiwolf/AiWolfMod.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("WolfAiRegistry.setDefaultCondition(config.defaultCondition())"),
                "設定を読むだけで反映しないと、実験条件が取り違えられる");
    }

    @Test
    @DisplayName("起動ログには設定値ではなく実際に適用された値を出す")
    void startupLogReportsTheAppliedValue() throws IOException {
        String source = Files.readString(
                repositoryRoot().resolve("src/main/java/jp/ac/naist/hai/aiwolf/AiWolfMod.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("WolfAiRegistry.defaultCondition().id()"),
                "config の値をそのまま出すと、反映漏れがログで見抜けない");
    }
}
