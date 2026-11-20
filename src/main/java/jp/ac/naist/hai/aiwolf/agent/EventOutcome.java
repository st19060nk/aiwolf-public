package jp.ac.naist.hai.aiwolf.agent;

import java.util.EnumMap;
import java.util.Collections;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * 1 件の出来事が個体へ及ぼした変化。Minecraft に依存しない。
 *
 * <p><strong>なぜ差分を持ち歩くのか。</strong>研究ログに適用後の値だけを残すと、
 * 「trust が 0.60 になった」ことは分かっても「0.52 から上がったのか 0.59 から上がったのか」は
 * 分からない。飽和しかけた個体では同じ出来事でも実際の変化量がまるで違うため、
 * 適用後の値だけでは「その出来事がどれだけ効いたか」を測れない。</p>
 *
 * <p>前後の値を両方残せば、解析側は差分を取り直せるうえ、
 * 記録漏れや二重適用もログ内で検算できる。</p>
 *
 * @param stateBefore       出来事を適用する直前の内部状態
 * @param stateAfter        適用直後の内部状態
 * @param stateDelta        内部状態の変化量（after − before）
 * @param preferenceBefore  適用直前の学習済み選好
 * @param preferenceAfter   適用直後の学習済み選好
 * @param preferenceDelta   選好の変化量（after − before）
 * @param createdMemoryId   この出来事から作られた記憶の ID。作られなかったなら {@code null}
 */
public record EventOutcome(Map<StateParameter, Double> stateBefore,
                           Map<StateParameter, Double> stateAfter,
                           Map<StateParameter, Double> stateDelta,
                           Map<BehaviorType, Double> preferenceBefore,
                           Map<BehaviorType, Double> preferenceAfter,
                           Map<BehaviorType, Double> preferenceDelta,
                           String createdMemoryId) {

    /** コンパクトコンストラクタ。マップを不変化する。 */
    public EventOutcome {
        stateBefore = Collections.unmodifiableMap(new EnumMap<>(stateBefore));
        stateAfter = Collections.unmodifiableMap(new EnumMap<>(stateAfter));
        stateDelta = Collections.unmodifiableMap(new EnumMap<>(stateDelta));
        preferenceBefore = Collections.unmodifiableMap(new EnumMap<>(preferenceBefore));
        preferenceAfter = Collections.unmodifiableMap(new EnumMap<>(preferenceAfter));
        preferenceDelta = Collections.unmodifiableMap(new EnumMap<>(preferenceDelta));
    }

    /**
     * 前後の状態と選好から変化を組み立てる。
     *
     * @param stateBefore      適用前の内部状態
     * @param stateAfter       適用後の内部状態
     * @param preferenceBefore 適用前の選好
     * @param preferenceAfter  適用後の選好
     * @param createdMemoryId  作られた記憶の ID（{@code null} 可）
     * @return 変化の記録
     */
    public static EventOutcome between(WolfInternalState stateBefore,
                                       WolfInternalState stateAfter,
                                       PreferenceVector preferenceBefore,
                                       PreferenceVector preferenceAfter,
                                       String createdMemoryId) {
        Map<StateParameter, Double> before = new EnumMap<>(StateParameter.class);
        Map<StateParameter, Double> after = new EnumMap<>(StateParameter.class);
        Map<StateParameter, Double> stateDelta = new EnumMap<>(StateParameter.class);
        for (StateParameter parameter : StateParameter.values()) {
            double from = stateBefore.get(parameter);
            double to = stateAfter.get(parameter);
            before.put(parameter, from);
            after.put(parameter, to);
            stateDelta.put(parameter, to - from);
        }

        Map<BehaviorType, Double> preferredBefore = new EnumMap<>(BehaviorType.class);
        Map<BehaviorType, Double> preferredAfter = new EnumMap<>(BehaviorType.class);
        Map<BehaviorType, Double> preferenceDelta = new EnumMap<>(BehaviorType.class);
        for (BehaviorType behavior : BehaviorType.values()) {
            double from = preferenceBefore.get(behavior);
            double to = preferenceAfter.get(behavior);
            preferredBefore.put(behavior, from);
            preferredAfter.put(behavior, to);
            preferenceDelta.put(behavior, to - from);
        }
        return new EventOutcome(before, after, stateDelta,
                preferredBefore, preferredAfter, preferenceDelta, createdMemoryId);
    }

    /**
     * 変化量の絶対値が最も大きかった内部状態量の変化を返す。
     *
     * <p>1 回の出来事が内部状態を極端に動かしていないかを検算するために使う。</p>
     *
     * @return 最大の変化量（絶対値）
     */
    public double largestStateChange() {
        return stateDelta.values().stream().mapToDouble(Math::abs).max().orElse(0.0);
    }

    /**
     * 変化量の絶対値が最も大きかった選好の変化を返す。
     *
     * @return 最大の変化量（絶対値）
     */
    public double largestPreferenceChange() {
        return preferenceDelta.values().stream().mapToDouble(Math::abs).max().orElse(0.0);
    }
}
