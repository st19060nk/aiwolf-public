package jp.ac.naist.hai.aiwolf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.memory.RegionMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * セーブ・ロードを挟んだ意思決定の連続性のテスト（Phase 13.1 の最重要項目）。
 *
 * <p>比べるのは 2 つの個体である。</p>
 * <ul>
 *   <li>Agent A：同じワールド時刻の列をそのまま走り続ける</li>
 *   <li>Agent B：途中で保存し、新しい個体へ復元し、同じ続きを走る</li>
 * </ul>
 *
 * <p>decisionIndex・決定時刻・行動列・確率分布・乱数値・選ばれた行動の
 * すべてが一致しなければならない。ここがずれると、観測された個体差を
 * 相互作用履歴に帰属できなくなる。「再起動したから違う振る舞いになった」
 * という説明を排除できないからである。</p>
 *
 * <p>Phase 13 までの実装は、ロード直後に必ず 1 回余分に決定していた。
 * 復元されたかどうかを、セーブされない一時オブジェクトの有無で判断していたためである。</p>
 */
final class SaveLoadContinuityTest {

    private static final String WOLF = "55555555-5555-4555-8555-555555555555";
    private static final long START = 1_000L;
    // 決定の境界からわざと外した時刻で保存する。ちょうど決定が来る tick で
    // 切ると、タイミングを失った個体でも偶然スケジュールが一致してしまう。
    private static final long SAVE_AT = 3_010L;
    private static final long END = 6_000L;

    private static WolfAiAgent agent() {
        return new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 20_260_923L);
    }

    /** NBT の保存項目と同じ集合を写し取って復元する。 */
    private static WolfAiAgent saveAndLoad(WolfAiAgent source) {
        WolfAiAgent restored = WolfAiAgent.restore(source.wolfId(), source.condition(),
                source.random().seed(), source.random().decisionIndex());
        restored.restoreMemorySequence(source.memorySequence());
        restored.restoreMaintenanceCarryTicks(source.maintenanceCarryTicks());
        restored.restoreCurrentBehavior(source.currentBehavior());
        for (StateParameter parameter : StateParameter.values()) {
            restored.state().set(parameter, source.state().get(parameter));
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            restored.preferences().set(behavior, source.preferences().get(behavior));
        }
        for (WolfMemory memory : source.memoryStore().memories()) {
            restored.memoryStore().add(memory, memory.worldTime());
        }
        for (Map.Entry<SpatialRegion, RegionMemory> entry
                : source.spatialMemory().regions().entrySet()) {
            RegionMemory value = entry.getValue();
            restored.spatialMemory().restoreRegion(entry.getKey(), new RegionMemory(
                    value.fearValue(), value.positiveValue(),
                    value.visitCount(), value.lastVisitedWorldTime()));
        }
        return restored;
    }

    private record Continuation(List<RuntimeLoop.Step> steps, WolfAiAgent wolf) { }

    /** 切らずに走り続けた個体の後半。 */
    private static Continuation uninterrupted() {
        ScriptedWorld world = new ScriptedWorld();
        WolfAiAgent wolf = agent();
        RuntimeLoop loop = new RuntimeLoop(wolf, world);
        loop.run(START, SAVE_AT);
        return new Continuation(loop.run(SAVE_AT, END), wolf);
    }

    /** 途中で保存・復元した個体の後半。 */
    private static Continuation acrossSaveAndLoad() {
        ScriptedWorld world = new ScriptedWorld();
        WolfAiAgent wolf = agent();
        RuntimeLoop loop = new RuntimeLoop(wolf, world);
        loop.run(START, SAVE_AT);

        WolfAiAgent restored = saveAndLoad(wolf);
        RuntimeLoop restoredLoop = new RuntimeLoop(restored, new ScriptedWorld());
        restoredLoop.restoreTiming(loop.lastDecisionTick(), loop.behaviourStartTick());
        return new Continuation(restoredLoop.run(SAVE_AT, END), restored);
    }

    @Test
    @DisplayName("復元した個体はロード直後に余分な決定をしない")
    void noExtraDecisionRightAfterLoading() {
        Continuation expected = uninterrupted();
        Continuation actual = acrossSaveAndLoad();

        assertFalse(expected.steps().isEmpty());
        assertEquals(expected.steps().size(), actual.steps().size(),
                "決定の回数が変われば Softmax のサンプリング回数もずれる");
        assertEquals(expected.steps().get(0).worldTime(), actual.steps().get(0).worldTime(),
                "ロード後の最初の決定が、切らずに続けた場合と同じ時刻に来ること");
    }

    @Test
    @DisplayName("決定時刻・decisionIndex・行動列・確率・乱数値がすべて一致する")
    void everythingMatchesAcrossSaveAndLoad() {
        List<RuntimeLoop.Step> expected = uninterrupted().steps();
        List<RuntimeLoop.Step> actual = acrossSaveAndLoad().steps();

        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            RuntimeLoop.Step a = expected.get(i);
            RuntimeLoop.Step b = actual.get(i);
            assertEquals(a.worldTime(), b.worldTime(), "決定 " + i + " の時刻");
            assertEquals(a.decisionIndexAfter(), b.decisionIndexAfter(),
                    "決定 " + i + " の decisionIndex");
            assertEquals(a.decision().selected(), b.decision().selected(),
                    "決定 " + i + " の行動");
            assertEquals(a.decision().randomValue(), b.decision().randomValue(), 0.0,
                    "決定 " + i + " の乱数値");
            for (BehaviorType behavior : a.decision().probabilities().keySet()) {
                assertEquals(a.decision().probabilities().get(behavior),
                        b.decision().probabilities().get(behavior), 0.0,
                        "決定 " + i + " の " + behavior.id() + " の確率");
            }
        }
    }

    @Test
    @DisplayName("復元後の最終的な内部状態・選好・記憶が一致する")
    void finalStateMatchesAcrossSaveAndLoad() {
        WolfAiAgent expected = uninterrupted().wolf();
        WolfAiAgent actual = acrossSaveAndLoad().wolf();

        for (StateParameter parameter : StateParameter.values()) {
            assertEquals(expected.state().get(parameter), actual.state().get(parameter), 0.0,
                    "state " + parameter.key());
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            assertEquals(expected.preferences().get(behavior),
                    actual.preferences().get(behavior), 0.0, "preference " + behavior.id());
        }
        assertEquals(expected.memoryStore().size(), actual.memoryStore().size());
        assertEquals(expected.random().decisionIndex(), actual.random().decisionIndex());
        assertEquals(expected.maintenanceCarryTicks(), actual.maintenanceCarryTicks());
    }

    @Test
    @DisplayName("行動分布がセーブ・ロードで変わらない")
    void behaviourDistributionIsUnchanged() {
        Map<BehaviorType, Integer> expected = countBehaviours(uninterrupted().steps());
        Map<BehaviorType, Integer> actual = countBehaviours(acrossSaveAndLoad().steps());

        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("ロード直後に強制決定すると連続性が壊れる（修正が必要だった理由）")
    void forcingADecisionOnLoadBreaksContinuity() {
        List<RuntimeLoop.Step> expected = uninterrupted().steps();

        // Phase 13 までの挙動：復元直後に 1 回決定してしまう
        ScriptedWorld world = new ScriptedWorld();
        WolfAiAgent wolf = agent();
        RuntimeLoop loop = new RuntimeLoop(wolf, world);
        loop.run(START, SAVE_AT);

        WolfAiAgent restored = saveAndLoad(wolf);
        RuntimeLoop broken = new RuntimeLoop(restored, new ScriptedWorld());
        // タイミングを復元しない = lastDecisionTick が MIN_VALUE のまま
        List<RuntimeLoop.Step> brokenSteps = broken.run(SAVE_AT, END);

        List<Long> expectedTicks = expected.stream().map(RuntimeLoop.Step::worldTime).toList();
        List<Long> brokenTicks = brokenSteps.stream().map(RuntimeLoop.Step::worldTime).toList();

        assertNotEquals(expectedTicks, brokenTicks,
                "決定タイミングを復元しなければ、決定が来る時刻が継続実行とずれる");
        assertEquals(Long.valueOf(SAVE_AT), brokenTicks.get(0),
                "タイミングを失った個体はロード直後に必ず 1 回決定してしまう");
        assertTrue(expectedTicks.get(0) > SAVE_AT,
                "本来はまだ決定の時刻ではない");
    }

    @Test
    @DisplayName("2 回セーブ・ロードを挟んでも行動列は変わらない")
    void repeatedSaveAndLoadIsStillIdentical() {
        List<BehaviorType> expected = uninterrupted().steps().stream()
                .map(step -> step.decision().selected()).toList();

        ScriptedWorld world = new ScriptedWorld();
        WolfAiAgent wolf = agent();
        RuntimeLoop loop = new RuntimeLoop(wolf, world);
        loop.run(START, SAVE_AT);

        long[] cuts = {SAVE_AT, 4_010L, 5_010L, END};
        java.util.List<BehaviorType> actual = new java.util.ArrayList<>();
        for (int i = 0; i < cuts.length - 1; i++) {
            WolfAiAgent restored = saveAndLoad(wolf);
            RuntimeLoop restoredLoop = new RuntimeLoop(restored, new ScriptedWorld());
            restoredLoop.restoreTiming(loop.lastDecisionTick(), loop.behaviourStartTick());
            restoredLoop.run(cuts[i], cuts[i + 1]).forEach(
                    step -> actual.add(step.decision().selected()));
            wolf = restored;
            loop = restoredLoop;
        }

        assertEquals(expected, actual);
    }

    private static Map<BehaviorType, Integer> countBehaviours(List<RuntimeLoop.Step> steps) {
        Map<BehaviorType, Integer> counts = new EnumMap<>(BehaviorType.class);
        steps.forEach(step -> counts.merge(step.decision().selected(), 1, Integer::sum));
        return counts;
    }
}
