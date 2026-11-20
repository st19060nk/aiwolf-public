package jp.ac.naist.hai.aiwolf.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorEligibility;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScore;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScorer;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorWeights;
import jp.ac.naist.hai.aiwolf.behavior.PolicySelector;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.learning.EventImpactMatrix;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.MemoryImpactCalculator;
import jp.ac.naist.hai.aiwolf.memory.MemoryQuery;
import jp.ac.naist.hai.aiwolf.memory.MemoryRetriever;
import jp.ac.naist.hai.aiwolf.memory.MemoryStore;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.memory.ScoredMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;
import jp.ac.naist.hai.aiwolf.random.WolfRandomProvider;
import jp.ac.naist.hai.aiwolf.state.StateUpdater;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * 1 個体分の AI ランタイム。<strong>Minecraft に依存しない。</strong>
 *
 * <p>内部状態・エピソード記憶・空間記憶・学習済み選好・乱数供給器をまとめて保持し、
 * 「出来事を受け取る」「行動を決める」という 2 つの入口だけを外へ公開する。
 * Minecraft 側は文脈スナップショットを作って {@link #decide} を呼び、
 * 返ってきた行動を実行するだけでよい。</p>
 *
 * <p>本クラスは薄いオーケストレータであり、アルゴリズムそのものは持たない。
 * 計算は {@code StateUpdater} / {@code MemoryRetriever} / {@code BehaviorScorer} /
 * {@code PolicySelector} へ委譲している（God class 化の回避）。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class WolfAiAgent {

    private static final BehaviorScorer DEFAULT_SCORER = new BehaviorScorer(BehaviorWeights.createDefault());

    private final String wolfId;
    private final WolfInternalState state;
    private final PreferenceVector preferences;
    private final MemoryStore memoryStore;
    private final SpatialMemory spatialMemory;
    private final WolfRandomProvider random;
    private final BehaviorScorer scorer;
    private final PolicySelector selector;

    private ExperimentalCondition condition;
    private BehaviorType currentBehavior;
    private long memorySequence;
    private List<String> lastRelevantMemoryIds = List.of();
    private List<ScoredMemory> lastRetrievedMemories = List.of();
    private WolfContextSnapshot lastContext;
    private String ownerResearchId;
    private long createdAtEpochMillis = System.currentTimeMillis();
    private long lastUpdatedEpochMillis = System.currentTimeMillis();
    private long maintenanceCarryTicks;

    /**
     * 評価モード（デモ用）で学習を凍結しているか。永続化しない。
     *
     * <p>凍結中も文脈の取得・記憶の検索・空間記憶の参照・スコア計算・Softmax 選択は
     * そのまま動く。止まるのは「経験からの学習」だけである。</p>
     */
    private boolean learningFrozen;

    /**
     * 既定のスコアラ・セレクタで新しい個体を生成する。すべての個体が同一の初期値から始まる。
     *
     * @param wolfId         個体 ID（UUID 文字列）
     * @param condition      実験条件
     * @param experimentSeed 実験シード
     */
    public WolfAiAgent(String wolfId, ExperimentalCondition condition, long experimentSeed) {
        this(wolfId, condition,
                WolfRandomProvider.restore(
                        AiWolfParameters.deriveIndividualSeed(experimentSeed, java.util.UUID.fromString(wolfId)), 0L),
                DEFAULT_SCORER, new PolicySelector());
    }

    /**
     * 依存を明示して個体を生成する（感度分析・テスト用）。
     *
     * @param wolfId    個体 ID
     * @param condition 実験条件
     * @param random    乱数供給器
     * @param scorer    行動スコアラ
     * @param selector  行動選択器
     */
    public WolfAiAgent(String wolfId,
                       ExperimentalCondition condition,
                       WolfRandomProvider random,
                       BehaviorScorer scorer,
                       PolicySelector selector) {
        this.wolfId = Objects.requireNonNull(wolfId, "wolfId");
        this.condition = Objects.requireNonNull(condition, "condition");
        this.random = Objects.requireNonNull(random, "random");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.state = WolfInternalState.createInitial();
        this.preferences = PreferenceVector.createInitial();
        this.memoryStore = new MemoryStore();
        this.spatialMemory = new SpatialMemory();
    }

    /**
     * 永続化から個体を復元する。
     *
     * <p>{@code decisionIndex} まで戻すことで、保存前と同じ乱数列が続く。
     * これがないとセーブ／ロードのたびに行動列が変わり、再現性が失われる。</p>
     *
     * @param wolfId        個体 ID
     * @param condition     実験条件
     * @param seed          個体固有シード
     * @param decisionIndex 次に使用する決定インデックス
     * @return 復元した個体（状態・記憶の中身は呼び出し側が詰め直す）
     */
    public static WolfAiAgent restore(String wolfId, ExperimentalCondition condition,
                                      long seed, long decisionIndex) {
        return new WolfAiAgent(wolfId, condition,
                WolfRandomProvider.restore(seed, decisionIndex),
                DEFAULT_SCORER, new PolicySelector());
    }

    /** @return 飼い主の匿名化済み研究 ID（未設定なら {@code null}） */
    public String ownerResearchId() {
        return ownerResearchId;
    }

    /**
     * 飼い主の匿名化済み研究 ID を設定する。プレイヤー名を入れてはならない。
     *
     * @param ownerResearchId 研究 ID
     */
    public void setOwnerResearchId(String ownerResearchId) {
        this.ownerResearchId = ownerResearchId;
    }

    /** @return 個体が生成された時刻（epoch ミリ秒） */
    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    /** @return 最後に更新された時刻（epoch ミリ秒） */
    public long lastUpdatedEpochMillis() {
        return lastUpdatedEpochMillis;
    }

    /**
     * 生成・更新時刻を復元する（永続化からの読み戻し専用）。
     *
     * @param createdAtEpochMillis   生成時刻
     * @param lastUpdatedEpochMillis 最終更新時刻
     */
    public void restoreTimestamps(long createdAtEpochMillis, long lastUpdatedEpochMillis) {
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.lastUpdatedEpochMillis = lastUpdatedEpochMillis;
    }

    /** @return 次に発行される記憶 ID の連番 */
    public long memorySequence() {
        return memorySequence;
    }

    /**
     * 記憶 ID の連番を復元する（永続化からの読み戻し専用）。
     *
     * <p>復元しないと保存前と同じ ID を再発行してしまい、研究ログ上で
     * 別々の出来事が同一視される。</p>
     *
     * @param memorySequence 連番
     */
    public void restoreMemorySequence(long memorySequence) {
        this.memorySequence = memorySequence;
    }

    /**
     * 出来事を受け取り、内部状態・記憶・選好を更新する。
     *
     * <p>更新の範囲は実験条件によって変わる。STATIC では一時的な感情状態しか動かず、
     * 記憶も選好も蓄積しないため履歴が残らない。</p>
     *
     * @param event 出来事
     */
    public EventOutcome onEvent(WolfEvent event) {
        Objects.requireNonNull(event, "event");

        // 適用前の値を控えておく。研究ログに差分を残せないと、飽和しかけた個体で
        // 「同じ出来事なのに実際にはほとんど動いていない」ことを見逃す。
        WolfInternalState stateBefore = state.copy();
        PreferenceVector preferenceBefore = preferences.copy();

        // 評価モード（学習凍結）中は、STATIC 条件と同じ規則で一時的な感情状態だけを動かし、
        // 長期の内部状態・記憶・空間記憶・選好は更新しない。凍結していなければ
        // 以下は従来とまったく同じ経路を通る。
        boolean frozen = learningFrozen;
        StateUpdater.applyEvent(state, event, frozen ? ExperimentalCondition.STATIC : condition);

        String createdMemoryId = null;
        // エピソード記憶と空間記憶は別々の機能フラグで ON / OFF する（Phase 16 のアブレーション）。
        // 既存の 3 条件では両者が常に同じ値なので、従来と同じ順序・同じ更新になる。
        if (!frozen && condition.usesEpisodicMemory()) {
            createdMemoryId = nextMemoryId();
            memoryStore.add(WolfMemory.fromEvent(event, createdMemoryId), event.worldTime());
        }
        if (!frozen && condition.usesSpatialMemory()) {
            spatialMemory.recordEvent(event);
        }
        if (!frozen && condition.usesLearnedPreference()) {
            preferences.applyEvent(EventImpactMatrix.impactsFor(event.type()), event.salience(),
                    AiWolfParameters.PREFERENCE_DECAY_LAMBDA,
                    AiWolfParameters.PREFERENCE_LEARNING_RATE);
        }
        lastUpdatedEpochMillis = System.currentTimeMillis();
        return EventOutcome.between(stateBefore, state, preferenceBefore, preferences,
                createdMemoryId);
    }

    /**
     * 現在の文脈で次の行動を決定する。
     *
     * @param context   文脈スナップショット
     * @param region    現在の量子化領域（記憶検索に使用。{@code null} 可）
     * @param actorId   現在の飼い主の匿名 ID（{@code null} 可）
     * @param worldTime 現在のワールド時刻 [ticks]
     * @return 決定記録（全候補のスコア内訳と確率を含む）
     */
    public BehaviorDecision decide(WolfContextSnapshot context,
                                   SpatialRegion region,
                                   String actorId,
                                   long worldTime) {
        Objects.requireNonNull(context, "context");

        MemorySummary memory = MemorySummary.empty();
        List<ScoredMemory> retrieved = List.of();
        if (condition.usesEpisodicMemory()) {
            retrieved = MemoryRetriever.retrieve(memoryStore,
                    MemoryQuery.atLocation(region, actorId, worldTime));
            memory = MemoryImpactCalculator.summarise(retrieved);
        }
        // 研究ログ用に、決定の入力（文脈と検索された記憶）をそのまま控えておく。計算には使わない。
        lastRetrievedMemories = List.copyOf(retrieved);
        lastContext = context;
        // どの記憶が効いたかは研究ログの中核なので、決定ごとに保持しておく。
        lastRelevantMemoryIds = memory.relevantMemoryIds();

        Set<BehaviorType> eligible = BehaviorEligibility.evaluate(context);
        Map<BehaviorType, BehaviorScore> scores = scorer.score(state, context, memory, preferences,
                currentBehavior, eligible, condition);
        BehaviorDecision decision = selector.select(scores, random);
        currentBehavior = decision.selected();
        return decision;
    }

    /**
     * 低頻度で呼ぶ定期メンテナンス。感情状態のベースライン回帰と energy の増減を行う。
     *
     * <p>毎 tick 呼んではならない。{@code AiWolfParameters.DECISION_INTERVAL_TICKS} 程度の
     * 周期で十分である。</p>
     */
    public void tickMaintenance() {
        StateUpdater.relaxEmotionalStates(state);
        StateUpdater.applyEnergyDynamics(state, currentBehavior);
    }

    /**
     * 経過時間ぶんの定期メンテナンスを行う。
     *
     * <p><strong>これがシミュレーションと実機の時間を揃える唯一の入口である。</strong>
     * {@link #tickMaintenance()} を「呼ばれた回数」で数えると、1 イベントごとに
     * 600 tick 進むシミュレーションと 40 tick ごとに決定する実機とで
     * 感情の回復速度が 15 倍ずれる。ここでは経過 tick を
     * {@code MAINTENANCE_INTERVAL_TICKS} で割った回数だけ更新し、
     * 余りを次回へ持ち越すので、<strong>同じ経過 tick なら同じ回数だけ更新される</strong>。</p>
     *
     * <p>持ち越し量は永続化する。しないとセーブのたびに端数が切り捨てられ、
     * 長期実験で感情の回復が実際より遅くなる。</p>
     *
     * @param elapsedTicks 前回からの経過 tick 数。負の値は 0 として扱う
     * @return 実際に行った更新回数
     */
    public int advanceTime(long elapsedTicks) {
        if (elapsedTicks <= 0L) {
            return 0;
        }
        maintenanceCarryTicks += elapsedTicks;
        long steps = maintenanceCarryTicks / AiWolfParameters.MAINTENANCE_INTERVAL_TICKS;
        if (steps <= 0L) {
            return 0;
        }
        maintenanceCarryTicks -= steps * AiWolfParameters.MAINTENANCE_INTERVAL_TICKS;

        // ワールド時刻が大きく飛んだ場合の暴走を防ぐ。上限に当たっても
        // 感情がベースラインへ近づくだけで、値が壊れることはない。
        int applied = (int) Math.min(steps, AiWolfParameters.MAX_MAINTENANCE_STEPS_PER_CALL);
        for (int i = 0; i < applied; i++) {
            tickMaintenance();
        }
        return applied;
    }

    /** @return まだ更新に使われていない経過 tick の端数（永続化用） */
    public long maintenanceCarryTicks() {
        return maintenanceCarryTicks;
    }

    /**
     * メンテナンスの端数を復元する（永続化からの読み戻し専用）。
     *
     * @param carryTicks 端数 [ticks]
     */
    public void restoreMaintenanceCarryTicks(long carryTicks) {
        this.maintenanceCarryTicks = Math.max(0L, carryTicks);
    }

    /**
     * 領域の実効恐怖値を返す。{@code ContextBuilder} が
     * {@code WolfContextSnapshot.regionFear} を埋めるために使う。
     *
     * @param region 領域（{@code null} 可）
     * @return [0,1] の実効恐怖値
     */
    public double regionFear(SpatialRegion region) {
        // 空間記憶を使わない条件では、行動に場所の恐怖を一切渡さない。
        // 空間記憶を使わない条件ではそもそも記録されないので、既存条件の値は変わらない。
        return condition.usesSpatialMemory() ? spatialMemory.fearAt(region) : 0.0;
    }

    /** @return 個体 ID */
    public String wolfId() {
        return wolfId;
    }

    /** @return 内部状態（変更可能） */
    public WolfInternalState state() {
        return state;
    }

    /** @return 学習済み選好（変更可能） */
    public PreferenceVector preferences() {
        return preferences;
    }

    /** @return エピソード記憶の保管庫 */
    public MemoryStore memoryStore() {
        return memoryStore;
    }

    /** @return 空間記憶 */
    public SpatialMemory spatialMemory() {
        return spatialMemory;
    }

    /** @return 乱数供給器 */
    public WolfRandomProvider random() {
        return random;
    }

    /**
     * 直近の決定で参照された記憶の ID を返す。
     *
     * <p>研究ログの {@code relevantMemoryIds} として書き出し、
     * {@code memories.jsonl} と突き合わせることで「どの記憶がどの決定に効いたか」を復元できる。</p>
     *
     * @return 記憶 ID の不変リスト
     */
    public List<String> lastRelevantMemoryIds() {
        return lastRelevantMemoryIds;
    }

    /** @return 直近の決定で検索された記憶（検索スコア順。記憶を使わない条件では空） */
    public List<ScoredMemory> lastRetrievedMemories() {
        return lastRetrievedMemories;
    }

    /** @return 直近の決定の文脈（未決定なら {@code null}） */
    public WolfContextSnapshot lastContext() {
        return lastContext;
    }

    /** @return 現在実行中の行動（未決定なら {@code null}） */
    public BehaviorType currentBehavior() {
        return currentBehavior;
    }

    /**
     * 現在実行中の行動を復元する（永続化からの読み戻し専用）。
     *
     * <p>BehaviorScorer は現在行動へ継続ボーナスを加える。これを復元しないと、
     * セーブ・再起動を挟んだだけで次の行動スコアが変わってしまい、
     * 「同一イベント列 + 同一シード ⇒ 同一行動列」という再現性の前提が崩れる。</p>
     *
     * @param behavior 復元する行動（不明なら {@code null}）
     */
    public void restoreCurrentBehavior(BehaviorType behavior) {
        this.currentBehavior = behavior;
    }

    /** @return 評価モードで学習が凍結されていれば true */
    public boolean isLearningFrozen() {
        return learningFrozen;
    }

    /**
     * 学習の凍結を切り替える（デモ用の評価モード専用）。
     *
     * <p>凍結中の {@link #onEvent} は、一時的な感情状態（fear / stress など EMOTIONAL 区分）
     * だけを STATIC 条件と同じ規則で更新し、関係性・動機づけの状態、エピソード記憶、
     * 空間記憶、学習済み選好は変更しない。{@link #decide} は凍結の影響を受けない。</p>
     *
     * @param frozen 凍結するなら true
     */
    public void setLearningFrozen(boolean frozen) {
        this.learningFrozen = frozen;
    }

    /** @return 実験条件 */
    public ExperimentalCondition condition() {
        return condition;
    }

    /**
     * 実験条件を切り替える（{@code /wolfai mode} 用）。
     *
     * @param condition 実験条件
     */
    public void setCondition(ExperimentalCondition condition) {
        this.condition = Objects.requireNonNull(condition, "condition");
    }

    /**
     * 決定論的な記憶 ID を生成する。
     *
     * <p>{@link java.util.UUID#randomUUID()} を使うと同じ実験を再実行しても ID が変わり、
     * ログの突き合わせができなくなる。個体 ID と連番から作ることで再現性を保つ。</p>
     *
     * @return 記憶 ID
     */
    private String nextMemoryId() {
        return wolfId + "#m" + (memorySequence++);
    }
}
