package jp.ac.naist.hai.aiwolf.simulation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;

/**
 * 実験前シミュレーションの回帰テスト。
 *
 * <p>ユーザ実験を始める前に「行動分布が本当に分離するか」を機械的に確認するのが目的。
 * 重みや学習率を変更してこのテストが落ちた場合、その変更は個体差の形成を壊している。</p>
 */
class PreExperimentSimulationTest {

    /** テスト時間を抑えるための小規模設定。 */
    private static PreExperimentSimulation.Settings smallSettings() {
        return new PreExperimentSimulation.Settings(6, 60, 120, 0.6, 20260919L);
    }

    @Test
    @DisplayName("ADAPTIVE 条件では育成履歴によって行動分布が分離する")
    void adaptiveConditionSeparatesGroups() {
        PreExperimentSimulation.Result result =
                PreExperimentSimulation.run(ExperimentalCondition.ADAPTIVE, smallSettings());

        assertTrue(result.separated(),
                "between(" + result.betweenDistance() + ") > within(" + result.withinDistance() + ") が必要");
        assertTrue(result.companionship().get(BehaviorType.APPROACH_PLAYER)
                > result.solo().get(BehaviorType.APPROACH_PLAYER));
        assertTrue(result.solo().get(BehaviorType.EXPLORE_NEARBY)
                > result.companionship().get(BehaviorType.EXPLORE_NEARBY));
        assertTrue(result.soloOwnerDistance() > result.companionshipOwnerDistance() + 5.0,
                "単独探索の個体は飼い主から離れて過ごすはず");
    }

    @Test
    @DisplayName("STATIC 条件（対照）では育成履歴によって分離しない")
    void staticConditionDoesNotSeparateGroups() {
        PreExperimentSimulation.Result result =
                PreExperimentSimulation.run(ExperimentalCondition.STATIC, smallSettings());

        assertTrue(!result.separated(),
                "対照条件で分離してはならない: between=" + result.betweenDistance()
                        + " within=" + result.withinDistance());
        assertTrue(Math.abs(result.companionshipOwnerDistance() - result.soloOwnerDistance()) < 3.0);
    }

    @Test
    @DisplayName("シミュレーションは決定論的である")
    void simulationIsDeterministic() {
        PreExperimentSimulation.Result first =
                PreExperimentSimulation.run(ExperimentalCondition.ADAPTIVE, smallSettings());
        PreExperimentSimulation.Result second =
                PreExperimentSimulation.run(ExperimentalCondition.ADAPTIVE, smallSettings());
        assertTrue(first.betweenDistance() == second.betweenDistance());
        assertTrue(first.companionshipOwnerDistance() == second.companionshipOwnerDistance());
    }
}
