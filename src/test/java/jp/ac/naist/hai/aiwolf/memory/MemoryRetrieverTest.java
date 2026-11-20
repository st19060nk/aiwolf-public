package jp.ac.naist.hai.aiwolf.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;

/** 記憶検索（{@link MemoryRetriever} / {@link MemoryImpactCalculator}）の単体テスト。 */
class MemoryRetrieverTest {

    private static final String DIM = "minecraft:overworld";
    private static final SpatialRegion CAVE = SpatialRegion.of(DIM, 0.0, 30.0, 0.0);
    private static final SpatialRegion FAR_AWAY = SpatialRegion.of(DIM, 5000.0, 64.0, 5000.0);

    private static WolfMemory memory(WolfEventType type, double x, double y, String actor,
                                     long worldTime, String id) {
        return WolfMemory.fromEvent(WolfEvent.of(type, worldTime, DIM, "minecraft:plains",
                x, y, 0.0, actor, 0.5, 1.0), id);
    }

    @Test
    @DisplayName("Top-K 件だけがスコア降順で返る")
    void retrievesTopKInDescendingOrder() {
        MemoryStore store = new MemoryStore();
        for (int i = 0; i < 30; i++) {
            store.add(memory(WolfEventType.NEW_BIOME, i * 100.0, 64.0, "subject-1", i * 100L, "m" + i),
                    i * 100L);
        }
        List<ScoredMemory> retrieved = MemoryRetriever.retrieve(store,
                MemoryQuery.atLocation(CAVE, "subject-1", 3000L));

        assertEquals(AiWolfParameters.MEMORY_RETRIEVAL_TOP_K, retrieved.size());
        for (int i = 1; i < retrieved.size(); i++) {
            assertTrue(retrieved.get(i - 1).score() >= retrieved.get(i).score());
        }
    }

    @Test
    @DisplayName("同じ場所・同じ相手・同じ出来事の記憶ほど relevance が高い")
    void relevanceCombinesEventRegionAndActor() {
        WolfMemory exact = memory(WolfEventType.WOLF_DAMAGED, 0.0, 30.0, "subject-1", 0L, "exact");
        WolfMemory sameRegionOnly = memory(WolfEventType.PET, 0.0, 30.0, "other", 0L, "region");
        WolfMemory unrelated = memory(WolfEventType.PET, 5000.0, 64.0, "other", 0L, "unrelated");

        MemoryQuery query = new MemoryQuery(WolfEventType.WOLF_DAMAGED, CAVE, "subject-1", 0L);

        double exactRelevance = MemoryRetriever.relevance(exact, query);
        double regionRelevance = MemoryRetriever.relevance(sameRegionOnly, query);
        double unrelatedRelevance = MemoryRetriever.relevance(unrelated, query);

        assertEquals(1.0, exactRelevance, 1.0e-9);
        assertEquals(AiWolfParameters.RELEVANCE_WEIGHT_REGION, regionRelevance, 1.0e-9);
        assertEquals(0.0, unrelatedRelevance, 1.0e-9);
    }

    @Test
    @DisplayName("隣接 region は割引された一致度になる")
    void adjacentRegionsPartiallyMatch() {
        WolfMemory adjacent = memory(WolfEventType.PET, 25.0, 30.0, "other", 0L, "adjacent");
        double relevance = MemoryRetriever.relevance(adjacent,
                MemoryQuery.atLocation(CAVE, null, 0L));
        assertEquals(AiWolfParameters.RELEVANCE_WEIGHT_REGION * AiWolfParameters.ADJACENT_REGION_MATCH,
                relevance, 1.0e-9);
    }

    @Test
    @DisplayName("検索結果は決定論的である")
    void retrievalIsDeterministic() {
        MemoryStore store = new MemoryStore();
        for (int i = 0; i < 20; i++) {
            store.add(memory(WolfEventType.PET, 0.0, 30.0, "subject-1", 0L, "m" + i), 0L);
        }
        MemoryQuery query = MemoryQuery.atLocation(CAVE, "subject-1", 1000L);
        List<String> first = MemoryRetriever.retrieve(store, query).stream()
                .map(scored -> scored.memory().memoryId()).toList();
        List<String> second = MemoryRetriever.retrieve(store, query).stream()
                .map(scored -> scored.memory().memoryId()).toList();
        assertEquals(first, second);
    }

    @Test
    @DisplayName("危険記憶は RETREAT と AVOID_LOCATION を押し上げ、EXPLORE を押し下げる")
    void dangerousMemoriesShiftBehaviourContributions() {
        MemoryStore store = new MemoryStore();
        for (int i = 0; i < 5; i++) {
            store.add(memory(WolfEventType.NEAR_DEATH, 0.0, 30.0, "subject-1", i * 100L, "danger" + i),
                    i * 100L);
        }
        MemorySummary summary = MemoryImpactCalculator.summarise(
                MemoryRetriever.retrieve(store, MemoryQuery.atLocation(CAVE, "subject-1", 500L)));

        assertTrue(summary.contributionFor(BehaviorType.RETREAT) > 0.0);
        assertTrue(summary.contributionFor(BehaviorType.AVOID_LOCATION) > 0.0);
        assertTrue(summary.contributionFor(BehaviorType.EXPLORE_NEARBY) < 0.0);
        assertEquals(5, summary.relevantMemoryIds().size());
    }

    @Test
    @DisplayName("記憶由来の寄与は [-1,+1] へクリップされる")
    void contributionsAreClipped() {
        MemoryStore store = new MemoryStore();
        for (int i = 0; i < 50; i++) {
            store.add(memory(WolfEventType.NEAR_DEATH, 0.0, 30.0, "subject-1", i * 10L, "d" + i), i * 10L);
        }
        MemorySummary summary = MemoryImpactCalculator.summarise(
                MemoryRetriever.retrieve(store, MemoryQuery.atLocation(CAVE, "subject-1", 500L), 50));
        for (BehaviorType behavior : BehaviorType.ALL) {
            double value = summary.contributionFor(behavior);
            assertTrue(value >= -1.0 && value <= 1.0, behavior.id() + " out of range: " + value);
        }
    }

    @Test
    @DisplayName("関係ない場所・相手の記憶は行動への影響が小さい")
    void irrelevantMemoriesHaveLittleEffect() {
        // 寄与は [-1,+1] で飽和するため、飽和しない 1 件で関連度の効果を比較する。
        MemoryStore store = new MemoryStore();
        store.add(memory(WolfEventType.NEAR_DEATH, 5000.0, 64.0, "someone-else", 0L, "far0"), 0L);

        MemorySummary matching = MemoryImpactCalculator.summarise(
                MemoryRetriever.retrieve(store, MemoryQuery.atLocation(FAR_AWAY, "someone-else", 500L)));
        MemorySummary unrelated = MemoryImpactCalculator.summarise(
                MemoryRetriever.retrieve(store, MemoryQuery.atLocation(CAVE, "subject-1", 500L)));

        assertTrue(matching.contributionFor(BehaviorType.AVOID_LOCATION)
                        > unrelated.contributionFor(BehaviorType.AVOID_LOCATION),
                "同じ場所・同じ相手の記憶の方が強く効くはず");
        assertTrue(unrelated.contributionFor(BehaviorType.AVOID_LOCATION) > 0.0,
                "関連が薄くても salience が高い記憶は多少は効く");
    }
}
