package jp.ac.naist.hai.aiwolf.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;

/** {@link MemoryStore} の単体テスト。 */
class MemoryStoreTest {

    private static WolfMemory memory(WolfEventType type, long worldTime, String id) {
        return WolfMemory.fromEvent(WolfEvent.of(type, worldTime, "minecraft:overworld",
                "minecraft:plains", 0.0, 64.0, 0.0, "subject-1", 1.0, 1.0), id);
    }

    @Test
    @DisplayName("容量を超えたら件数が上限に保たれる")
    void capacityIsEnforced() {
        MemoryStore store = new MemoryStore(10);
        for (int i = 0; i < 50; i++) {
            store.add(memory(WolfEventType.NEW_BIOME, i * 100L, "m" + i), i * 100L);
        }
        assertEquals(10, store.size());
    }

    @Test
    @DisplayName("重要な記憶は些細な記憶より残りやすい")
    void salientMemoriesSurvivePruning() {
        MemoryStore store = new MemoryStore(5);
        store.add(memory(WolfEventType.NEAR_DEATH, 0L, "critical"), 0L);
        for (int i = 1; i <= 20; i++) {
            store.add(memory(WolfEventType.SLEEP_NEAR_PLAYER, i * 50L, "trivial" + i), i * 50L);
        }
        assertEquals(5, store.size());
        assertTrue(store.memories().stream().anyMatch(m -> m.memoryId().equals("critical")),
                "salience の高い記憶が残るべき");
    }

    @Test
    @DisplayName("古くても salience が高ければ保持スコアが高い")
    void retentionScoreBalancesSalienceAndRecency() {
        WolfMemory old = memory(WolfEventType.NEAR_DEATH, 0L, "old");
        WolfMemory recent = memory(WolfEventType.SLEEP_NEAR_PLAYER, 100_000L, "recent");
        long now = 100_000L;
        assertTrue(MemoryStore.retentionScore(old, now) > MemoryStore.retentionScore(recent, now));
    }

    @Test
    @DisplayName("容量内なら何も削除されない")
    void nothingIsPrunedBelowCapacity() {
        MemoryStore store = new MemoryStore(AiWolfParameters.MAX_MEMORIES_PER_WOLF);
        for (int i = 0; i < 100; i++) {
            store.add(memory(WolfEventType.PET, i * 100L, "m" + i), i * 100L);
        }
        assertEquals(100, store.size());
    }

    @Test
    @DisplayName("clear で全削除される")
    void clearRemovesEverything() {
        MemoryStore store = new MemoryStore(10);
        store.add(memory(WolfEventType.PET, 0L, "m0"), 0L);
        store.clear();
        assertEquals(0, store.size());
    }
}
