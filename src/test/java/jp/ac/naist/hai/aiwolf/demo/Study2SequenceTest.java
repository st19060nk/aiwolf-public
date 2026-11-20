package jp.ac.naist.hai.aiwolf.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.nbt.NbtCompound;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.persistence.WolfAiNbt;
import jp.ac.naist.hai.aiwolf.study.MemoryCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 25 P1-3：Study 2 の手順（Snapshot → 記憶 ON / OFF の比較 → 復元）を通しで確かめる。
 *
 * <p>個々の性質（復元の完全性・記憶 OFF で寄与が 0・評価中の凍結）は {@code DemoResetAndSnapshotTest} /
 * {@code HumanStudyP1Test} / {@code EvaluationModeTest} にある。ここでは、それらを実験の順序でつないだときに、
 * 記憶 OFF の区間や評価の区間が Snapshot の中身を汚さないことを確かめる。</p>
 */
final class Study2SequenceTest {

    private static WolfAiController trained() {
        WolfAiController controller = new WolfAiController(DemoFixtures.newAgent());
        DemoFixtures.experienceDangerAndRescue(controller.agent());
        DemoFixtures.decide(controller.agent(), 0, 12);
        controller.restoreDecisionTiming(30_000L, 29_960L);
        return controller;
    }

    @Test
    @DisplayName("Snapshot → 記憶 OFF で観察 → 復元 → 記憶 ON で観察：両条件が同じ保存時点から始まる")
    void memoryOnAndOffStartFromTheSameSnapshot() {
        WolfAiController controller = trained();
        ExperimentalCondition original = controller.agent().condition();
        DemoSnapshotStore store = new DemoSnapshotStore(null);
        NbtCompound saved = WolfAiNbt.write(controller);
        store.save(controller, "after-training");

        // 記憶 OFF で観察（学習は続く設定のまま決定を重ねる）。
        controller.agent().setCondition(MemoryCondition.withMemory(original, false).orElseThrow());
        assertFalse(controller.agent().condition().features().anyMemory());
        List<BehaviorType> offRun = DemoFixtures.decide(controller.agent(), 12, 30).stream()
                .map(BehaviorDecision::selected).toList();
        assertNotEquals(saved, WolfAiNbt.write(controller), "観察で個体の状態は進む（復元の前提）");

        // 復元すると、状態・選好・記憶・空間記憶・タイマー・条件のすべてが保存時点に戻る（記憶 OFF の痕跡は残らない）。
        WolfAiController restored = store.restore(DemoFixtures.WOLF, "after-training").orElseThrow();
        assertEquals(saved, WolfAiNbt.write(restored));
        assertEquals(original, restored.agent().condition(), "条件も保存時点（記憶 ON）に戻る");

        // 記憶 ON で同じ時点から観察すると、保存時点からの行動列が再現される（2 回目の復元と比べる）。
        List<BehaviorType> onRun = DemoFixtures.decide(restored.agent(), 12, 30).stream()
                .map(BehaviorDecision::selected).toList();
        WolfAiController again = store.restore(DemoFixtures.WOLF, "after-training").orElseThrow();
        List<BehaviorType> onReplay = DemoFixtures.decide(again.agent(), 12, 30).stream()
                .map(BehaviorDecision::selected).toList();
        assertEquals(onRun, onReplay, "同じ Snapshot から同じ条件なら同じ行動列");
        assertEquals(offRun.size(), onRun.size());
    }

    @Test
    @DisplayName("評価（学習の凍結）中に記憶 OFF で観察しても、凍結を解いて復元すれば保存時点と一致する")
    void evaluationAndMemoryOffLeaveTheSnapshotIntact() {
        WolfAiController controller = trained();
        DemoSnapshotStore store = new DemoSnapshotStore(null);
        NbtCompound saved = WolfAiNbt.write(controller);
        store.save(controller, "s");

        controller.agent().setLearningFrozen(true);
        controller.agent().setCondition(MemoryCondition.withMemory(controller.agent().condition(), false).orElseThrow());
        int memories = controller.agent().memoryStore().size();
        DemoFixtures.experienceDangerAndRescue(controller.agent());
        DemoFixtures.decide(controller.agent(), 12, 30);
        assertEquals(memories, controller.agent().memoryStore().size(), "凍結中は新しい記憶を作らない");
        assertTrue(controller.agent().isLearningFrozen());

        controller.agent().setLearningFrozen(false);
        WolfAiController restored = store.restore(DemoFixtures.WOLF, "s").orElseThrow();
        assertEquals(saved, WolfAiNbt.write(restored));
    }
}
