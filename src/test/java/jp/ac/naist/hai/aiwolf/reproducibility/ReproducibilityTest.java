package jp.ac.naist.hai.aiwolf.reproducibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 再現性テスト A / B / C。
 *
 * <p>この研究の主張は「個体差は固定パラメータではなく相互作用履歴から生まれる」である。
 * それを示すには、<strong>同じ履歴を与えれば同じ振る舞いになる</strong>ことが前提になる。
 * 履歴以外の何か（実行のタイミング、セーブ・ロード、JVM の気分）で行動が変わるなら、
 * 観測された個体差を履歴に帰属できない。ここはその前提の検証である。</p>
 *
 * <dl>
 *   <dt>Test A</dt><dd>同一シード・同一イベント列 ⇒ 同一行動列</dd>
 *   <dt>Test B</dt><dd>途中でセーブ・復元しても、切らずに続けた場合と同一行動列</dd>
 *   <dt>Test C</dt><dd>個体シードが違えば分岐しうる（＝履歴以外の分岐源が存在しない確認の対偶）</dd>
 * </dl>
 */
final class ReproducibilityTest {

    private static final long EXPERIMENT_SEED = 20_260_919L;
    private static final String WOLF_A = "11111111-1111-4111-8111-111111111111";
    private static final String WOLF_B = "22222222-2222-4222-8222-222222222222";
    private static final int TOTAL_STEPS = 50;
    private static final int CUT_AT = 23;

    private static WolfAiAgent freshAgent(String wolfId) {
        return new WolfAiAgent(wolfId, ExperimentalCondition.ADAPTIVE, EXPERIMENT_SEED);
    }

    // ---------------------------------------------------------------- Test A

    @Test
    @DisplayName("Test A: 同一シード・同一イベント列は同一行動列になる")
    void sameSeedAndSameHistoryProduceSameBehaviourSequence() {
        List<BehaviorType> first = ExperimentDriver.run(freshAgent(WOLF_A), 0, TOTAL_STEPS);
        List<BehaviorType> second = ExperimentDriver.run(freshAgent(WOLF_A), 0, TOTAL_STEPS);

        assertEquals(first, second);
        assertEquals(TOTAL_STEPS, first.size());
    }

    @Test
    @DisplayName("Test A: 内部状態・選好も完全に一致する")
    void sameHistoryProducesSameInternalState() {
        WolfAiAgent first = freshAgent(WOLF_A);
        WolfAiAgent second = freshAgent(WOLF_A);
        ExperimentDriver.run(first, 0, TOTAL_STEPS);
        ExperimentDriver.run(second, 0, TOTAL_STEPS);

        for (StateParameter parameter : StateParameter.values()) {
            assertEquals(first.state().get(parameter), second.state().get(parameter), 0.0,
                    "state " + parameter.key() + " が一致しない");
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            assertEquals(first.preferences().get(behavior), second.preferences().get(behavior), 0.0,
                    "preference " + behavior.id() + " が一致しない");
        }
        assertEquals(first.random().decisionIndex(), second.random().decisionIndex());
        assertEquals(first.memoryStore().size(), second.memoryStore().size());
    }

    @Test
    @DisplayName("Test A: 決定インデックスは決定回数と一致し、巻き戻らない")
    void decisionIndexAdvancesMonotonically() {
        WolfAiAgent agent = freshAgent(WOLF_A);
        long before = agent.random().decisionIndex();
        ExperimentDriver.run(agent, 0, TOTAL_STEPS);

        assertEquals(0L, before);
        assertEquals(TOTAL_STEPS, agent.random().decisionIndex());
    }

    // ---------------------------------------------------------------- Test B

    @Test
    @DisplayName("Test B: 途中でセーブ・復元しても切らずに続けた場合と同一行動列になる")
    void saveAndRestoreContinuesTheSameBehaviourSequence() {
        // 切らずに通した個体
        WolfAiAgent continuous = freshAgent(WOLF_A);
        ExperimentDriver.run(continuous, 0, CUT_AT);
        List<BehaviorType> expectedTail = ExperimentDriver.run(continuous, CUT_AT, TOTAL_STEPS);

        // 同じ地点でセーブして復元した個体
        WolfAiAgent saved = freshAgent(WOLF_A);
        ExperimentDriver.run(saved, 0, CUT_AT);
        WolfAiAgent restored = AgentSnapshot.save(saved).restore();
        List<BehaviorType> actualTail = ExperimentDriver.run(restored, CUT_AT, TOTAL_STEPS);

        assertEquals(expectedTail, actualTail,
                "セーブ・ロードを挟むと行動列が変わるなら、長期実験の連続性が壊れている");
    }

    @Test
    @DisplayName("Test B: 復元後の内部状態・選好・記憶が保存前と一致する")
    void restoredAgentMatchesSavedAgentExactly() {
        WolfAiAgent saved = freshAgent(WOLF_A);
        ExperimentDriver.run(saved, 0, CUT_AT);
        WolfAiAgent restored = AgentSnapshot.save(saved).restore();

        for (StateParameter parameter : StateParameter.values()) {
            assertEquals(saved.state().get(parameter), restored.state().get(parameter), 0.0,
                    "state " + parameter.key());
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            assertEquals(saved.preferences().get(behavior), restored.preferences().get(behavior), 0.0,
                    "preference " + behavior.id());
        }
        assertEquals(saved.memoryStore().size(), restored.memoryStore().size());
        assertEquals(saved.spatialMemory().size(), restored.spatialMemory().size());
        assertEquals(saved.random().decisionIndex(), restored.random().decisionIndex());
        assertEquals(saved.currentBehavior(), restored.currentBehavior());
        assertEquals(saved.memorySequence(), restored.memorySequence());
    }

    @Test
    @DisplayName("Test B: 2 回セーブ・復元を挟んでも行動列は変わらない")
    void repeatedSaveRestoreIsStillIdentical() {
        WolfAiAgent continuous = freshAgent(WOLF_A);
        List<BehaviorType> expected = ExperimentDriver.run(continuous, 0, TOTAL_STEPS);

        WolfAiAgent agent = freshAgent(WOLF_A);
        List<BehaviorType> actual = new java.util.ArrayList<>();
        int[] cuts = {0, 11, 23, 37, TOTAL_STEPS};
        for (int i = 0; i < cuts.length - 1; i++) {
            actual.addAll(ExperimentDriver.run(agent, cuts[i], cuts[i + 1]));
            agent = AgentSnapshot.save(agent).restore();
        }

        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Test B: decisionIndex を保存しないと行動列が変わる（保存が必要な理由）")
    void droppingDecisionIndexBreaksContinuity() {
        WolfAiAgent continuous = freshAgent(WOLF_A);
        ExperimentDriver.run(continuous, 0, CUT_AT);
        List<BehaviorType> expectedTail = ExperimentDriver.run(continuous, CUT_AT, TOTAL_STEPS);

        WolfAiAgent saved = freshAgent(WOLF_A);
        ExperimentDriver.run(saved, 0, CUT_AT);
        AgentSnapshot snapshot = AgentSnapshot.save(saved);
        List<BehaviorType> brokenTail =
                ExperimentDriver.run(snapshot.restoreWithoutDecisionIndex(), CUT_AT, TOTAL_STEPS);

        assertEquals(CUT_AT, snapshot.decisionIndex());
        assertNotEquals(expectedTail, brokenTail,
                "decisionIndex は乱数列の位置そのものなので、落とすと必ず行動列が変わる");
    }

    @Test
    @DisplayName("Test B: currentBehavior を欠いた旧形式セーブでも例外を出さずに動く")
    void oldSaveWithoutCurrentBehaviourStillRuns() {
        WolfAiAgent saved = freshAgent(WOLF_A);
        ExperimentDriver.run(saved, 0, CUT_AT);
        WolfAiAgent legacy = AgentSnapshot.save(saved).restoreWithoutCurrentBehavior();

        List<BehaviorType> tail = ExperimentDriver.run(legacy, CUT_AT, TOTAL_STEPS);

        assertEquals(TOTAL_STEPS - CUT_AT, tail.size(),
                "旧形式のセーブを読んで落ちてはならない（実験データを失う）");
        assertTrue(tail.stream().allMatch(java.util.Objects::nonNull));
    }

    @Test
    @DisplayName("Test B: 未知の行動 ID は無視され、null として扱われる")
    void unknownBehaviourIdIsIgnored() {
        assertTrue(BehaviorType.byId("PANIC_BITE").isEmpty(),
                "知らない行動 ID でセーブを読んだとき、例外にせず無視する必要がある");
        assertTrue(BehaviorType.byId("").isEmpty());
        assertTrue(BehaviorType.byId(null).isEmpty());
        assertTrue(BehaviorType.byId("approach_player").isPresent());
    }

    // ---------------------------------------------------------------- Test C

    @Test
    @DisplayName("Test C: 個体シードが違えば同じ履歴でも行動列は分岐しうる")
    void differentIndividualSeedsCanDiverge() {
        List<BehaviorType> a = ExperimentDriver.run(freshAgent(WOLF_A), 0, TOTAL_STEPS);
        List<BehaviorType> b = ExperimentDriver.run(freshAgent(WOLF_B), 0, TOTAL_STEPS);

        assertNotEquals(AiWolfParameters.deriveIndividualSeed(EXPERIMENT_SEED, UUID.fromString(WOLF_A)),
                AiWolfParameters.deriveIndividualSeed(EXPERIMENT_SEED, UUID.fromString(WOLF_B)));
        assertNotEquals(a, b, "サンプリングの揺れが個体間に出ること自体は正しい");
    }

    @Test
    @DisplayName("Test C: 同じ履歴なら個体シードが違っても内部状態は同一になる")
    void sameHistoryYieldsSameInternalStateRegardlessOfSeed() {
        WolfAiAgent a = freshAgent(WOLF_A);
        WolfAiAgent b = freshAgent(WOLF_B);
        ExperimentDriver.run(a, 0, TOTAL_STEPS);
        ExperimentDriver.run(b, 0, TOTAL_STEPS);

        // 行動の選択は確率的なので energy のような行動依存の状態は揺れるが、
        // イベント由来の関係的状態は履歴だけで決まる。ここが一致しなければ
        // 「個体差は履歴から生まれる」という設計が守られていない。
        assertEquals(a.state().get(StateParameter.ATTACHMENT),
                b.state().get(StateParameter.ATTACHMENT), 0.0);
        assertEquals(a.state().get(StateParameter.TRUST),
                b.state().get(StateParameter.TRUST), 0.0);
        assertEquals(a.memoryStore().size(), b.memoryStore().size());
    }

    @Test
    @DisplayName("Test C: 個体シードは実験シードと UUID から決定論的に導出される")
    void individualSeedIsDeterministic() {
        UUID wolf = UUID.fromString(WOLF_A);

        assertEquals(AiWolfParameters.deriveIndividualSeed(EXPERIMENT_SEED, wolf),
                AiWolfParameters.deriveIndividualSeed(EXPERIMENT_SEED, wolf));
        assertNotEquals(AiWolfParameters.deriveIndividualSeed(EXPERIMENT_SEED, wolf),
                AiWolfParameters.deriveIndividualSeed(EXPERIMENT_SEED + 1L, wolf));
    }

    @Test
    @DisplayName("全個体が同一の初期状態から始まる（固定性格の不在）")
    void everyIndividualStartsFromTheSameInitialState() {
        WolfAiAgent a = freshAgent(WOLF_A);
        WolfAiAgent b = freshAgent(WOLF_B);

        for (StateParameter parameter : StateParameter.values()) {
            assertEquals(a.state().get(parameter), b.state().get(parameter), 0.0,
                    "初期状態が個体で違うなら、それは固定性格の割り当てである: " + parameter.key());
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            assertEquals(a.preferences().get(behavior), b.preferences().get(behavior), 0.0,
                    "初期選好が個体で違ってはならない: " + behavior.id());
        }
    }
}
