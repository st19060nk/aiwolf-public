package jp.ac.naist.hai.aiwolf.core;

import java.util.Locale;
import java.util.Optional;

/**
 * 実験条件。
 *
 * <p><strong>全条件が同一の行動集合・同一の BehaviorScorer・同一の Softmax 選択機構を用いる。</strong>
 * STATIC 条件をバニラのオオカミにしてはならない。そうすると「行動レパートリの豊かさ」と
 * 「経験への適応」を分離できなくなる。</p>
 *
 * <p>条件間の違いは {@link AdaptiveFeatures}（どの学習・記憶機構を有効にするか）だけである。
 * これにより ablation として「単純な内部数値」が効いたのか「エピソード記憶」「空間記憶」
 * 「選好学習」が効いたのかを分離して検証できる。</p>
 *
 * <pre>
 * 条件               state  episodic  spatial  preference
 * STATIC              -       -         -         -       Score = Base + Context + TemporaryState
 * STATE_ONLY          o       -         -         -       Score = Base + Context + State
 * STATE_EPISODIC      o       o         -         -
 * STATE_SPATIAL       o       -         o         -
 * STATE_MEMORY        o       o         o         -       Score = ... + State + Memory
 * STATE_PREFERENCE    o       -         -         o       Score = ... + State + LearnedPreference
 * ADAPTIVE (= FULL)   o       o         o         o       Score = ... + State + Memory + LearnedPreference
 * </pre>
 *
 * <p>STATIC・STATE_ONLY・ADAPTIVE の 3 つは Phase 16 以前からあり、挙動は変えていない
 * （これまで 1 つのフラグだったエピソード記憶と空間記憶を 2 つに分けたが、3 条件では両者が常に同じ値）。
 * ID も永続化・ログとの互換のために変えない。ADAPTIVE が研究計画上の FULL_ADAPTIVE にあたる
 * （{@link #byId} は {@code full_adaptive} / {@code full} も ADAPTIVE として受け付ける）。</p>
 *
 * <p>新しい値は末尾に追加する（序数を変えないため）。</p>
 */
public enum ExperimentalCondition {

    /** 対照条件。持続的な関係性状態更新・エピソード記憶・空間記憶・選好学習をすべて行わない。 */
    STATIC("static", new AdaptiveFeatures(false, false, false, false)),

    /** Ablation 条件。内部状態は更新するが、記憶と選好学習を行わない。 */
    STATE_ONLY("state_only", new AdaptiveFeatures(true, false, false, false)),

    /** 完全適応条件（FULL_ADAPTIVE）。内部状態・エピソード記憶・空間記憶・学習済み選好をすべて用いる。 */
    ADAPTIVE("adaptive", new AdaptiveFeatures(true, true, true, true)),

    /** Ablation 条件。内部状態と記憶（エピソード + 空間）を用い、選好学習を行わない。 */
    STATE_MEMORY("state_memory", new AdaptiveFeatures(true, true, true, false)),

    /** Ablation 条件。内部状態と選好学習を用い、記憶を使わない。 */
    STATE_PREFERENCE("state_preference", new AdaptiveFeatures(true, false, false, true)),

    /** 記憶の追加分解。内部状態とエピソード記憶だけ。 */
    STATE_EPISODIC("state_episodic", new AdaptiveFeatures(true, true, false, false)),

    /** 記憶の追加分解。内部状態と空間記憶だけ。 */
    STATE_SPATIAL("state_spatial", new AdaptiveFeatures(true, false, true, false));

    private final String id;
    private final AdaptiveFeatures features;

    ExperimentalCondition(String id, AdaptiveFeatures features) {
        this.id = id;
        this.features = features;
    }

    /**
     * 永続化・ログ・コマンド引数で使用する安定 ID を返す。
     *
     * @return 小文字の ID
     */
    public String id() {
        return id;
    }

    /** @return この条件で有効な適応機構 */
    public AdaptiveFeatures features() {
        return features;
    }

    /**
     * 内部状態の持続的更新（経験による trust / fear 等の変化）を行うか。
     *
     * @return 行う場合 true
     */
    public boolean usesPersistentStateUpdate() {
        return features.stateLearning();
    }

    /**
     * エピソード記憶を蓄え、行動スコアへ反映するか。
     *
     * @return 反映する場合 true
     */
    public boolean usesEpisodicMemory() {
        return features.episodicMemory();
    }

    /**
     * 空間記憶（場所の恐怖値）を蓄え、文脈として行動スコアへ反映するか。
     *
     * @return 反映する場合 true
     */
    public boolean usesSpatialMemory() {
        return features.spatialMemory();
    }

    /**
     * 学習済み行動選好を行動スコアへ反映するか。
     *
     * @return 反映する場合 true
     */
    public boolean usesLearnedPreference() {
        return features.preferenceLearning();
    }

    /**
     * ID から条件を解決する（大文字小文字は無視）。
     *
     * @param id 条件 ID（{@code full_adaptive} / {@code full} は ADAPTIVE の別名）
     * @return 一致する条件。存在しなければ {@link Optional#empty()}
     */
    public static Optional<ExperimentalCondition> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("full_adaptive") || normalized.equals("full")) {
            return Optional.of(ADAPTIVE);
        }
        for (ExperimentalCondition condition : values()) {
            if (condition.id.equals(normalized)) {
                return Optional.of(condition);
            }
        }
        return Optional.empty();
    }
}
