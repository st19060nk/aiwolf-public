package jp.ac.naist.hai.aiwolf.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.random.WolfRandomProvider;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * 本研究の中核仮説 H1 をコードレベルで検証する。
 *
 * <p>「同一アルゴリズム・同一初期値・同一行動集合であっても、異なるインタラクション履歴を
 * 経験した個体は異なる行動傾向（Behavioral Signature）を形成する」ことを、
 * Minecraft を起動せずに確認する。</p>
 *
 * <p>逆に「同じ履歴なら UUID（乱数シード）が違っても行動分布は同一」であることも確認し、
 * 個体差が乱数ではなく経験から生じていることを示す。</p>
 *
 * <p>本テストで用いるイベント影響値は仕様書の EventImpact 例に基づく。
 * 後続ステップで {@code EventImpactMatrix} として本体側へ移す予定。</p>
 */
class EmergentIndividualityTest {

    private static final UUID WOLF_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID WOLF_B = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final UUID WOLF_C = UUID.fromString("00000000-0000-4000-8000-0000000000c3");

    private final BehaviorScorer scorer = new BehaviorScorer(BehaviorWeights.createDefault());
    private final PolicySelector selector = new PolicySelector();

    /** 1 個体分の「育成結果」をまとめた入れ物。 */
    private record RearedWolf(WolfInternalState state, PreferenceVector preferences) {
    }

    /** 飼い主と一緒に過ごし、守られる経験を多く積んだ育成履歴。 */
    private RearedWolf rearWithCompanionship(int repetitions) {
        WolfInternalState state = WolfInternalState.createInitial();
        PreferenceVector preferences = PreferenceVector.createInitial();

        Map<BehaviorType, Double> protectedImpact = new EnumMap<>(BehaviorType.class);
        protectedImpact.put(BehaviorType.FOLLOW_CLOSE, 0.5);
        protectedImpact.put(BehaviorType.APPROACH_PLAYER, 0.4);
        protectedImpact.put(BehaviorType.EXPLORE_NEARBY, -0.1);

        for (int i = 0; i < repetitions; i++) {
            // PET / FEED 相当：関係性状態が少しずつ上がる。
            state.add(StateParameter.TRUST, 0.02);
            state.add(StateParameter.ATTACHMENT, 0.04);
            state.add(StateParameter.STRESS, -0.03);
            // PLAYER_PROTECTED_WOLF 相当。
            preferences.applyEvent(protectedImpact, 0.9,
                    AiWolfParameters.PREFERENCE_DECAY_LAMBDA,
                    AiWolfParameters.PREFERENCE_LEARNING_RATE);
        }
        return new RearedWolf(state, preferences);
    }

    /** 単独で安全に探索し、戦闘にも成功してきた育成履歴。 */
    private RearedWolf rearWithSoloExploration(int repetitions) {
        WolfInternalState state = WolfInternalState.createInitial();
        PreferenceVector preferences = PreferenceVector.createInitial();

        Map<BehaviorType, Double> soloImpact = new EnumMap<>(BehaviorType.class);
        soloImpact.put(BehaviorType.EXPLORE_NEARBY, 0.5);
        soloImpact.put(BehaviorType.FOLLOW_CLOSE, -0.1);

        Map<BehaviorType, Double> defendImpact = new EnumMap<>(BehaviorType.class);
        defendImpact.put(BehaviorType.DEFEND_PLAYER, 0.5);

        for (int i = 0; i < repetitions; i++) {
            // SAFE_SOLO_EXPLORATION 相当：自律性が上がり恐怖が下がる。
            state.add(StateParameter.AUTONOMY, 0.03);
            state.add(StateParameter.CURIOSITY, 0.02);
            state.add(StateParameter.FEAR, -0.02);
            preferences.applyEvent(soloImpact, 0.6,
                    AiWolfParameters.PREFERENCE_DECAY_LAMBDA,
                    AiWolfParameters.PREFERENCE_LEARNING_RATE);
            // DEFEND_SUCCESS 相当。
            preferences.applyEvent(defendImpact, 0.8,
                    AiWolfParameters.PREFERENCE_DECAY_LAMBDA,
                    AiWolfParameters.PREFERENCE_LEARNING_RATE);
        }
        return new RearedWolf(state, preferences);
    }

    /** 同一のテスト文脈における行動確率分布（Behavioral Signature）を求める。 */
    private Map<BehaviorType, Double> behaviouralSignature(RearedWolf wolf, UUID uuid) {
        WolfContextSnapshot testContext = WolfContextSnapshot.builder()
                .ownerDistance(6.0)
                .ownerMoving(true)
                .build();
        Set<BehaviorType> eligible = BehaviorEligibility.evaluate(testContext);
        Map<BehaviorType, BehaviorScore> scores = scorer.score(wolf.state(), testContext,
                MemorySummary.empty(), wolf.preferences(), null, eligible,
                ExperimentalCondition.ADAPTIVE);
        return selector.select(scores,
                WolfRandomProvider.forWolf(AiWolfParameters.DEFAULT_EXPERIMENT_SEED, uuid))
                .probabilities();
    }

    @Test
    @DisplayName("H1: 同一アルゴリズム・同一初期値でも、異なる履歴が異なる行動傾向を生む")
    void differentHistoriesProduceDifferentBehaviouralSignatures() {
        Map<BehaviorType, Double> companionship = behaviouralSignature(rearWithCompanionship(60), WOLF_A);
        Map<BehaviorType, Double> solo = behaviouralSignature(rearWithSoloExploration(60), WOLF_B);

        assertTrue(companionship.get(BehaviorType.FOLLOW_CLOSE) > solo.get(BehaviorType.FOLLOW_CLOSE),
                "一緒に過ごした個体の方が FOLLOW_CLOSE を選びやすいはず");
        assertTrue(companionship.get(BehaviorType.APPROACH_PLAYER) > solo.get(BehaviorType.APPROACH_PLAYER),
                "一緒に過ごした個体の方が APPROACH_PLAYER を選びやすいはず");
        assertTrue(solo.get(BehaviorType.EXPLORE_NEARBY) > companionship.get(BehaviorType.EXPLORE_NEARBY),
                "単独探索の個体の方が EXPLORE_NEARBY を選びやすいはず");

        double totalVariation = 0.0;
        for (BehaviorType behavior : companionship.keySet()) {
            totalVariation += Math.abs(companionship.get(behavior) - solo.get(behavior));
        }
        assertTrue(totalVariation / 2.0 > 0.10,
                "行動分布の差が小さすぎる（total variation = " + (totalVariation / 2.0) + "）");
    }

    @Test
    @DisplayName("個体差は乱数シードではなく経験から生じる（同一履歴なら分布も同一）")
    void identicalHistoriesProduceIdenticalSignaturesRegardlessOfUuid() {
        Map<BehaviorType, Double> first = behaviouralSignature(rearWithCompanionship(60), WOLF_A);
        Map<BehaviorType, Double> second = behaviouralSignature(rearWithCompanionship(60), WOLF_C);
        for (BehaviorType behavior : first.keySet()) {
            assertEquals(first.get(behavior), second.get(behavior), 0.0,
                    "同一履歴なら UUID が違っても行動分布は完全に一致するはず");
        }
    }

    @Test
    @DisplayName("育成前は全個体の行動分布が完全に一致する")
    void untrainedWolvesAreIndistinguishable() {
        RearedWolf freshA = new RearedWolf(WolfInternalState.createInitial(), PreferenceVector.createInitial());
        RearedWolf freshB = new RearedWolf(WolfInternalState.createInitial(), PreferenceVector.createInitial());
        Map<BehaviorType, Double> a = behaviouralSignature(freshA, WOLF_A);
        Map<BehaviorType, Double> b = behaviouralSignature(freshB, WOLF_B);
        for (BehaviorType behavior : a.keySet()) {
            assertEquals(a.get(behavior), b.get(behavior), 0.0);
        }
    }

    @Test
    @DisplayName("STATIC 条件では履歴が行動分布を変えない（対照条件として成立する）")
    void staticConditionDoesNotDivergeWithHistory() {
        WolfContextSnapshot testContext = WolfContextSnapshot.builder()
                .ownerDistance(6.0).ownerMoving(true).build();
        Set<BehaviorType> eligible = BehaviorEligibility.evaluate(testContext);

        RearedWolf companionship = rearWithCompanionship(60);
        RearedWolf solo = rearWithSoloExploration(60);

        // STATIC では内部状態の持続更新も行われないため、初期状態で評価する。
        Map<BehaviorType, BehaviorScore> a = scorer.score(WolfInternalState.createInitial(), testContext,
                MemorySummary.empty(), companionship.preferences(), null, eligible,
                ExperimentalCondition.STATIC);
        Map<BehaviorType, BehaviorScore> b = scorer.score(WolfInternalState.createInitial(), testContext,
                MemorySummary.empty(), solo.preferences(), null, eligible,
                ExperimentalCondition.STATIC);

        for (BehaviorType behavior : a.keySet()) {
            assertEquals(a.get(behavior).finalScore(), b.get(behavior).finalScore(), 0.0,
                    "STATIC 条件では履歴によるスコア差が出てはならない");
        }
        assertEquals(a.keySet(), b.keySet());
        assertEquals(eligible.size(), a.size(), "STATIC もバニラ化せず ADAPTIVE と同じ行動集合を使う");
    }
}
