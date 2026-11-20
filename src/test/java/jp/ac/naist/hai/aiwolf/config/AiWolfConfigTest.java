package jp.ac.naist.hai.aiwolf.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.AnonymousIdMapper;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 実験設定の読み書きのテスト。
 *
 * <p>salt が読み書きで保たれないと、同じ参加者が別の匿名 ID になり、
 * 長期実験の履歴が途中で切れる。壊れた設定ファイルで MOD が起動できなくなるのも
 * 実験セッションを丸ごと失う事故なので、フォールバックを検証している。</p>
 */
final class AiWolfConfigTest {

    private static AiWolfConfig roundTrip(AiWolfConfig source) throws IOException {
        StringWriter writer = new StringWriter();
        source.store(writer);
        return AiWolfConfig.load(new StringReader(writer.toString()));
    }

    @Test
    @DisplayName("書き出して読み直すと同じ設定になる")
    void storeThenLoadPreservesEverything() throws IOException {
        AiWolfConfig source = new AiWolfConfig(-8123456789012345678L, 42L,
                ExperimentalCondition.STATE_ONLY);

        assertEquals(source, roundTrip(source));
    }

    @Test
    @DisplayName("負の salt も欠落なく往復する")
    void negativeSaltSurvivesRoundTrip() throws IOException {
        AiWolfConfig source = new AiWolfConfig(Long.MIN_VALUE, Long.MAX_VALUE,
                ExperimentalCondition.STATIC);

        AiWolfConfig loaded = roundTrip(source);
        assertEquals(Long.MIN_VALUE, loaded.experimentSalt());
        assertEquals(Long.MAX_VALUE, loaded.experimentSeed());
    }

    @Test
    @DisplayName("salt が保たれれば同じ参加者は同じ匿名 ID になる")
    void sameSaltYieldsSameSubjectId() throws IOException {
        AiWolfConfig source = new AiWolfConfig(987654321L, 7L, ExperimentalCondition.ADAPTIVE);
        AiWolfConfig loaded = roundTrip(source);

        String before = AnonymousIdMapper.withSalt(source.experimentSalt())
                .toSubjectId(java.util.UUID.nameUUIDFromBytes("participant".getBytes()));
        String after = AnonymousIdMapper.withSalt(loaded.experimentSalt())
                .toSubjectId(java.util.UUID.nameUUIDFromBytes("participant".getBytes()));

        assertEquals(before, after, "salt が往復で変わると参加者の履歴が切れる");
    }

    @Test
    @DisplayName("salt が変わると別の匿名 ID になる")
    void differentSaltYieldsDifferentSubjectId() {
        java.util.UUID player = java.util.UUID.nameUUIDFromBytes("participant".getBytes());

        assertNotEquals(AnonymousIdMapper.withSalt(1L).toSubjectId(player),
                AnonymousIdMapper.withSalt(2L).toSubjectId(player));
    }

    @Test
    @DisplayName("値が壊れていても既定値で起動できる")
    void brokenValuesFallBackToDefaults() throws IOException {
        String broken = """
                experimentSalt=not-a-number
                experimentSeed=
                defaultCondition=BRAVE
                """;

        AiWolfConfig loaded = AiWolfConfig.load(new StringReader(broken));

        assertEquals(AiWolfParameters.DEFAULT_EXPERIMENT_SEED, loaded.experimentSeed());
        assertEquals(AiWolfParameters.DEFAULT_EXPERIMENTAL_CONDITION, loaded.defaultCondition(),
                "未知の条件名は既定条件へ落とす。性格ラベルは受け付けない");
    }

    @Test
    @DisplayName("空のファイルでも既定値で起動できる")
    void emptyFileFallsBackToDefaults() throws IOException {
        AiWolfConfig loaded = AiWolfConfig.load(new StringReader(""));

        assertEquals(AiWolfParameters.DEFAULT_EXPERIMENT_SEED, loaded.experimentSeed());
        assertEquals(AiWolfParameters.DEFAULT_EXPERIMENTAL_CONDITION, loaded.defaultCondition());
    }

    @Test
    @DisplayName("一部のキーだけ書かれていても残りは既定値になる")
    void partialFileKeepsWrittenKeys() throws IOException {
        AiWolfConfig loaded = AiWolfConfig.load(new StringReader("experimentSeed=123456\n"));

        assertEquals(123456L, loaded.experimentSeed());
        assertEquals(AiWolfParameters.DEFAULT_EXPERIMENTAL_CONDITION, loaded.defaultCondition());
    }

    @Test
    @DisplayName("条件名は大文字小文字を問わず読める")
    void conditionIdIsCaseInsensitive() throws IOException {
        assertEquals(ExperimentalCondition.STATE_ONLY,
                AiWolfConfig.load(new StringReader("defaultCondition=STATE_ONLY\n"))
                        .defaultCondition());
        assertEquals(ExperimentalCondition.STATIC,
                AiWolfConfig.load(new StringReader("defaultCondition= static \n"))
                        .defaultCondition());
    }

    @Test
    @DisplayName("既定設定の salt はランダムで、Git に固定値を置かない")
    void defaultSaltIsGenerated() {
        long first = AiWolfConfig.createDefault().experimentSalt();
        long second = AiWolfConfig.createDefault().experimentSalt();

        assertTrue(first != second, "固定 salt をソースへ埋め込んではならない");
    }

    @Test
    @DisplayName("書き出したファイルには salt の運用注意が残る")
    void storedFileDocumentsSaltPolicy() throws IOException {
        StringWriter writer = new StringWriter();
        new AiWolfConfig(1L, 2L, ExperimentalCondition.ADAPTIVE).store(writer);

        assertTrue(writer.toString().contains("experimentSalt"));
        assertTrue(writer.toString().contains("Keep this value for the whole experiment"));
    }
}
