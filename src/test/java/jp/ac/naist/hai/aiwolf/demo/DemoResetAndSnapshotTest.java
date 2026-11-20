package jp.ac.naist.hai.aiwolf.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import net.minecraft.nbt.NbtCompound;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.persistence.WolfAiNbt;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** デモ用リセットとスナップショットの検証。 */
final class DemoResetAndSnapshotTest {

    private static WolfAiController experiencedController() {
        WolfAiController controller = new WolfAiController(DemoFixtures.newAgent());
        DemoFixtures.experienceDangerAndRescue(controller.agent());
        DemoFixtures.decide(controller.agent(), 0, 12);
        controller.restoreDecisionTiming(30_000L, 29_960L);
        controller.observer().combatTracker().restoreCombatSequence(4L);
        return controller;
    }

    @Test
    @DisplayName("demo reset で全研究状態が研究初期値へ戻る")
    void resetRestoresTheResearchInitialState() {
        WolfAiController before = experiencedController();
        WolfAiAgent old = before.agent();
        assertFalse(old.memoryStore().memories().isEmpty());

        WolfAiController fresh = DemoReset.freshController(before);
        WolfAiAgent agent = fresh.agent();

        assertEquals(WolfInternalState.createInitial(), agent.state());
        assertEquals(PreferenceVector.createInitial(), agent.preferences());
        assertEquals(0, agent.memoryStore().size());
        assertEquals(0, agent.spatialMemory().size());
        assertNull(agent.currentBehavior());
        assertTrue(agent.lastRelevantMemoryIds().isEmpty());
        assertEquals(0L, agent.random().decisionIndex());
        assertEquals(0L, agent.maintenanceCarryTicks());
        assertEquals(Long.MIN_VALUE, fresh.lastDecisionTick());
        assertEquals(Long.MIN_VALUE, fresh.behaviourStartTick());
        assertNull(fresh.lastDecision());
        assertEquals(0.0, fresh.recentCombat());
        assertEquals(0, fresh.observer().combatTracker().openEpisodeCount());
        assertTrue(fresh.observer().exportState().visitedBiomes().isEmpty());
        assertEquals(Long.MIN_VALUE, fresh.observer().exportState().lastPetTick());

        // 個体の同一性と、ログ上の ID 衝突を避けるための採番だけは引き継ぐ。
        assertEquals(old.wolfId(), agent.wolfId());
        assertEquals(old.random().seed(), agent.random().seed());
        assertEquals(old.condition(), agent.condition());
        assertEquals(old.ownerResearchId(), agent.ownerResearchId());
        assertEquals(old.memorySequence(), agent.memorySequence());
        assertEquals(4L, fresh.observer().combatTracker().combatSequence());
    }

    @Test
    @DisplayName("リセットした個体は新規の個体と同じ行動列をたどる（デモ用の性格値はない）")
    void resetBehavesExactlyLikeANewWolf() {
        WolfAiAgent reset = DemoReset.freshController(experiencedController()).agent();
        WolfAiAgent brandNew = DemoFixtures.newAgent();

        List<BehaviorDecision> a = DemoFixtures.decide(reset, 0, 40);
        List<BehaviorDecision> b = DemoFixtures.decide(brandNew, 0, 40);
        for (int i = 0; i < a.size(); i++) {
            assertEquals(b.get(i).selected(), a.get(i).selected());
            assertEquals(b.get(i).scores(), a.get(i).scores());
        }
    }

    @Test
    @DisplayName("Snapshot → 変化 → restore で保存時点の研究状態へ完全に戻る")
    void snapshotRestoreReturnsToTheSavedState(@TempDir Path directory) {
        WolfAiController controller = experiencedController();
        DemoSnapshotStore store = new DemoSnapshotStore(directory);
        NbtCompound expected = WolfAiNbt.write(controller);
        store.save(controller, "before");

        // 保存後に経験と決定を重ねて、すべての項目を動かす。
        DemoFixtures.experienceDangerAndRescue(controller.agent());
        DemoFixtures.decide(controller.agent(), 12, 30);
        controller.agent().onEvent(DemoFixtures.eventAtDanger(WolfEventType.SAFE_SOLO_EXPLORATION, 90_000L));
        assertNotEquals(expected, WolfAiNbt.write(controller));

        WolfAiController restored = store.restore(DemoFixtures.WOLF, "before").orElseThrow();
        assertEquals(expected, WolfAiNbt.write(restored));
    }

    @Test
    @DisplayName("復元した個体は保存時点から同じ行動列を再現する（乱数・継続ボーナスも戻る）")
    void restoredWolfReplaysTheSameFuture() {
        WolfAiController controller = experiencedController();
        DemoSnapshotStore store = new DemoSnapshotStore(null);
        store.save(controller, "before");

        List<BehaviorType> original = DemoFixtures.decide(controller.agent(), 12, 40).stream()
                .map(BehaviorDecision::selected).toList();
        WolfAiAgent restored = store.restore(DemoFixtures.WOLF, "before").orElseThrow().agent();
        List<BehaviorType> replay = DemoFixtures.decide(restored, 12, 40).stream()
                .map(BehaviorDecision::selected).toList();
        assertEquals(original, replay);
    }

    @Test
    @DisplayName("Snapshot はディスクから読み戻せ、通常のワールド保存とは別の印を持つ")
    void snapshotSurvivesARestart(@TempDir Path directory) {
        WolfAiController controller = experiencedController();
        NbtCompound expected = WolfAiNbt.write(controller);
        new DemoSnapshotStore(directory).save(controller, "after");

        DemoSnapshotStore reopened = new DemoSnapshotStore(directory);
        assertTrue(reopened.list(DemoFixtures.WOLF).containsKey("after"));
        assertEquals(expected, WolfAiNbt.write(reopened.restore(DemoFixtures.WOLF, "after").orElseThrow()));
    }

    @Test
    @DisplayName("別の個体の Snapshot は復元できず、保存しても元の個体は変わらない")
    void snapshotsAreScopedToOneWolf() {
        WolfAiController controller = experiencedController();
        NbtCompound before = WolfAiNbt.write(controller);
        DemoSnapshotStore store = new DemoSnapshotStore(null);
        store.save(controller, "before");

        assertEquals(before, WolfAiNbt.write(controller));
        assertTrue(store.restore(DemoFixtures.OTHER_WOLF, "before").isEmpty());
        assertTrue(store.restore(DemoFixtures.WOLF, "missing").isEmpty());
        assertTrue(store.restore(DemoFixtures.WOLF, "../escape").isEmpty());
        assertFalse(DemoSnapshotStore.isValidName("../escape"));
    }

    @Test
    @DisplayName("Snapshot は実験条件も保存する")
    void snapshotKeepsTheCondition() {
        WolfAiAgent agent = new WolfAiAgent(DemoFixtures.WOLF, ExperimentalCondition.STATE_ONLY, 1L);
        DemoSnapshotStore store = new DemoSnapshotStore(null);
        store.save(new WolfAiController(agent), "s");
        assertEquals(ExperimentalCondition.STATE_ONLY,
                store.restore(DemoFixtures.WOLF, "s").orElseThrow().agent().condition());
    }
}
