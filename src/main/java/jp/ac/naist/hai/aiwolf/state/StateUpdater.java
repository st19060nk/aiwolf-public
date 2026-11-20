package jp.ac.naist.hai.aiwolf.state;

import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;

/**
 * 出来事および時間経過による内部状態の更新。
 *
 * <p>Minecraft に依存しないため、長期的な状態変化のダイナミクスを
 * ゲーム外のシミュレーションで検証できる。</p>
 *
 * <h2>実験条件による違い</h2>
 * <ul>
 *   <li>{@code STATIC}：一時的な感情状態（fear / stress）のみ変化し、ベースラインへ戻る。
 *       関係性状態と動機付け状態は更新しないため履歴が蓄積しない。</li>
 *   <li>{@code STATE_ONLY} / {@code ADAPTIVE}：すべての分類を更新する。</li>
 * </ul>
 *
 * <p>STATIC でも同じ行動集合・同じスコアラを用いる点は変わらない。
 * 差は「経験が残るかどうか」だけである。</p>
 */
public final class StateUpdater {

    private StateUpdater() {
        throw new AssertionError("utility class");
    }

    /**
     * 出来事による状態変化を適用する。増分は salience で重み付けされる。
     *
     * @param state     更新対象の内部状態
     * @param event     出来事
     * @param condition 実験条件
     */
    public static void applyEvent(WolfInternalState state, WolfEvent event, ExperimentalCondition condition) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(condition, "condition");

        double salience = event.salience();
        for (Map.Entry<StateParameter, Double> entry : StateImpactMatrix.deltasFor(event.type()).entrySet()) {
            StateParameter parameter = entry.getKey();
            if (!condition.usesPersistentStateUpdate() && parameter.category() != StateCategory.EMOTIONAL) {
                // STATIC 条件では一時的な感情状態しか動かさない。
                continue;
            }
            state.add(parameter, entry.getValue() * salience);
        }
    }

    /**
     * 感情状態をベースラインへ 1 ステップ回帰させる。一定周期で呼ぶこと。
     *
     * @param state 更新対象の内部状態
     */
    public static void relaxEmotionalStates(WolfInternalState state) {
        Objects.requireNonNull(state, "state");
        state.relaxEmotionalStatesTowardsBaseline(AiWolfParameters.EMOTIONAL_RELAXATION_RATE);
    }

    /**
     * 実行中の行動に応じて energy を増減させる。一定周期で呼ぶこと。
     *
     * <p>探索や戦闘では消耗し、休息では回復する。energy は動機付け状態なので、
     * STATIC 条件でも一時的な変動として適用される（永続的な履歴にはならない）。</p>
     *
     * @param state           更新対象の内部状態
     * @param currentBehavior 現在実行中の行動（なければ {@code null}）
     */
    public static void applyEnergyDynamics(WolfInternalState state, BehaviorType currentBehavior) {
        Objects.requireNonNull(state, "state");
        if (currentBehavior == null) {
            state.add(StateParameter.ENERGY, AiWolfParameters.ENERGY_IDLE_DELTA);
            return;
        }
        double delta = switch (currentBehavior) {
            case EXPLORE_NEARBY, DEFEND_PLAYER, RETREAT, AVOID_LOCATION -> AiWolfParameters.ENERGY_ACTIVE_DELTA;
            case REST_NEAR_PLAYER -> AiWolfParameters.ENERGY_REST_DELTA;
            default -> AiWolfParameters.ENERGY_IDLE_DELTA;
        };
        state.add(StateParameter.ENERGY, delta);
    }
}
