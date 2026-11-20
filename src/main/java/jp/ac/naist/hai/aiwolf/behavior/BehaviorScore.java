package jp.ac.naist.hai.aiwolf.behavior;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1 行動分のスコアと、その内訳。<strong>スコアの唯一の表現（single source of truth）</strong>。
 *
 * <p>「選ばれた行動」だけを記録してはならない。<strong>なぜその行動になったのか</strong>を
 * 後から説明するために、成分を分解したまま研究ログへ保存する。</p>
 *
 * <pre>
 * finalScore = base + state + context + memory + preference + persistence - penalty
 * </pre>
 *
 * <p>{@code context} は文脈特徴量の重み付き和の<strong>全体</strong>で、次の 2 つを内訳として含む
 * （Phase 14A で内訳を取り出せるようにした。値の定義・計算順序は変えていない）。</p>
 * <ul>
 *   <li>{@code spatialMemory} — 空間記憶由来の項（文脈特徴 LOCATION_FEAR × 重み）。
 *       空間記憶の恐怖値は文脈の一部としてスコアへ入るため、独立した項ではなく context の内訳になる</li>
 *   <li>{@code emergency} — 緊急退避の項（文脈特徴 EMERGENCY_LOW_HEALTH × EMERGENCY_RETREAT_BONUS）</li>
 * </ul>
 * <p>成分の和は {@link #componentSum()} で、{@code finalScore} と浮動小数点誤差の範囲で一致する。
 * {@code finalScore} 自体は分解導入前と同じ式・同じ加算順序で計算しているので、ビット単位で変わらない。</p>
 *
 * @param behavior      対象行動
 * @param base          基礎選好 beta_b（全個体共通）
 * @param state         内部状態由来の寄与（z = 2x - 1 に重みを掛けた総和）
 * @param context       現在文脈由来の寄与の全体（spatialMemory と emergency を含む）
 * @param memory        エピソード記憶由来の寄与
 * @param preference    学習済み行動選好由来の寄与
 * @param persistence   現在行動の継続ボーナス
 * @param penalty       ペナルティ（正の値で減点される）
 * @param finalScore    合計スコア
 * @param spatialMemory context のうち空間記憶（LOCATION_FEAR）由来の部分
 * @param emergency     context のうち緊急退避（EMERGENCY_LOW_HEALTH）由来の部分
 */
public record BehaviorScore(
        BehaviorType behavior,
        double base,
        double state,
        double context,
        double memory,
        double preference,
        double persistence,
        double penalty,
        double finalScore,
        double spatialMemory,
        double emergency) {

    /**
     * 成分から合計スコアを計算して生成する（文脈の内訳なし。テスト・感度分析用）。
     *
     * @param behavior    対象行動
     * @param base        基礎選好
     * @param state       状態寄与
     * @param context     文脈寄与
     * @param memory      記憶寄与
     * @param preference  選好寄与
     * @param persistence 継続ボーナス
     * @param penalty     ペナルティ
     * @return スコア
     */
    public static BehaviorScore of(BehaviorType behavior,
                                   double base,
                                   double state,
                                   double context,
                                   double memory,
                                   double preference,
                                   double persistence,
                                   double penalty) {
        return of(behavior, base, state, context, memory, preference, persistence, penalty, 0.0, 0.0);
    }

    /**
     * 成分から合計スコアを計算して生成する。
     *
     * @param behavior      対象行動
     * @param base          基礎選好
     * @param state         状態寄与
     * @param context       文脈寄与の全体
     * @param memory        エピソード記憶寄与
     * @param preference    選好寄与
     * @param persistence   継続ボーナス
     * @param penalty       ペナルティ
     * @param spatialMemory context のうち空間記憶由来の部分
     * @param emergency     context のうち緊急退避由来の部分
     * @return スコア
     */
    public static BehaviorScore of(BehaviorType behavior,
                                   double base,
                                   double state,
                                   double context,
                                   double memory,
                                   double preference,
                                   double persistence,
                                   double penalty,
                                   double spatialMemory,
                                   double emergency) {
        // 分解導入前と同じ式・同じ加算順序。ここを変えるとビット単位の再現性が壊れる。
        double total = base + state + context + memory + preference + persistence - penalty;
        return new BehaviorScore(behavior, base, state, context, memory, preference, persistence, penalty, total,
                spatialMemory, emergency);
    }

    /** @return エピソード記憶由来の寄与（{@link #memory()} と同じ） */
    public double episodicMemory() {
        return memory;
    }

    /** @return 文脈寄与のうち、空間記憶と緊急退避を除いた部分 */
    public double contextExcludingMemoryAndEmergency() {
        return context - spatialMemory - emergency;
    }

    /**
     * 成分の和。{@link #finalScore()} と浮動小数点誤差の範囲で一致する。
     *
     * @return base + state + 文脈(その他) + 空間記憶 + 緊急 + エピソード記憶 + 選好 + 継続 − ペナルティ
     */
    public double componentSum() {
        return base + state + contextExcludingMemoryAndEmergency() + spatialMemory + emergency
                + memory + preference + persistence - penalty;
    }

    /**
     * 成分を名前付きで返す（ログ・解析用）。値の和は {@link #componentSum()}。
     *
     * @return 成分名 → 値（ペナルティは減点として負の値で入る）
     */
    public Map<String, Double> components() {
        Map<String, Double> components = new LinkedHashMap<>();
        components.put("base", base);
        components.put("state", state);
        components.put("context", contextExcludingMemoryAndEmergency());
        components.put("episodicMemory", memory);
        components.put("spatialMemory", spatialMemory);
        components.put("preference", preference);
        components.put("persistence", persistence);
        components.put("penalty", -penalty);
        components.put("emergency", emergency);
        return Collections.unmodifiableMap(components);
    }
}
