package jp.ac.naist.hai.aiwolf.core;

import java.util.UUID;

/**
 * 研究パラメータの単一変更点。
 *
 * <p>実験条件に影響する定数をすべて本クラスへ集約する。コード中にマジックナンバーを
 * 置いてはならない。値を変更した場合は実験条件が変わるため、
 * {@link #PARAMETER_SET_VERSION} を必ずインクリメントし、README と研究ログの
 * スキーマ記述を更新すること。</p>
 *
 * <p><strong>最重要原則：</strong>初期値・重み・行列はすべての個体で共通である。
 * 個体ごと、プレイヤーごとに値を変えてはならない。個体差は
 * 「何をどれだけ経験したか」からのみ生じなければならない。</p>
 */
public final class AiWolfParameters {

    private AiWolfParameters() {
        throw new AssertionError("constant holder");
    }

    /** パラメータセットのバージョン。定数を変更したら必ずインクリメントする。 */
    public static final int PARAMETER_SET_VERSION = 2;

    /**
     * 出来事ログのスキーマバージョン。
     *
     * <p>パラメータセットとは独立に上げる。ログの項目が増えても研究上の設定は変わらないので、
     * 同じ値を使い回すとパイロットの取り直しが必要かどうか判断できなくなる。</p>
     *
     * <p>3 = stateBefore / stateDelta / preferenceDelta / createdMemoryId を追加。
     * 4 = wolfSubjectId / experimentSeed / randomSeed / adaptiveFeatures を追加（Phase 19）。
     * すべての研究ログの行の先頭には ResearchLogger が runId を付ける。</p>
     */
    public static final int EVENT_LOG_SCHEMA_VERSION = 4;

    /**
     * 決定ログ（decisions.jsonl）のスキーマバージョン。
     *
     * <p>{@link #EVENT_LOG_SCHEMA_VERSION} と同じく、パラメータセットとは独立に上げる。
     * 研究上の設定は変わらないので {@link #PARAMETER_SET_VERSION} は上げない。
     * （決定ログの {@code schemaVersion} 項目は歴史的経緯でパラメータセットのバージョンを入れている。）</p>
     *
     * <p>1 = 導入時（項目名なし。この項目が無い行は 1 とみなす）。
     * 2 = 各候補に contextSpatialMemory / contextEmergency / contextOther / componentSum を追加
     * （context はこれまでどおり文脈寄与の全体で、前 2 者を内訳として含む）。
     * 3 = parameterSetVersion / adaptiveFeatures / wolfSubjectId / context / contextFeatures /
     * retrievedMemories / preferences を追加（Phase 19）。</p>
     */
    public static final int DECISION_LOG_SCHEMA_VERSION = 3;

    /**
     * 研究ログの全行に共通する先頭項目（封筒）の版。
     *
     * <p>各ログ（決定・出来事など）の中身の版とは独立に上げる。封筒の項目は
     * {@code ResearchLogger} が全行に一律で付けるもので、各レコードの意味を変えないため、
     * 封筒を変えても各ログのスキーマ版は上げない（Phase 19 の runId と同じ方針）。</p>
     *
     * <p>1 = {@code runId} のみ（Phase 19。この項目がない行は 1）。
     * 2 = {@code logEnvelopeVersion} / {@code sessionId} / {@code phaseId} を追加（Phase 20）。
     * 3 = {@code logSequence}（実行内でファイルをまたいで単調増加する通し番号）を追加（Phase 25B）。</p>
     */
    public static final int LOG_ENVELOPE_VERSION = 3;

    /**
     * プレイヤー側のログ（player-events / player-samples）のスキーマ版。
     *
     * <p>1 = 導入時（Phase 21）。
     * 2 = サンプルの {@code motion} に {@code hasBaseline} / {@code noBaselineReason} を追加。前回が別のセッション・
     * 長い空白・別のワールドなら移動量を計算しない（Phase 25 P0-4）。</p>
     */
    public static final int PLAYER_LOG_SCHEMA_VERSION = 2;

    /**
     * セッションの出来事ログ（sessions.jsonl）のスキーマ版。
     *
     * <p>1 = 導入時（Phase 20）。
     * 2 = SESSION_START に modVersion / buildRevision / buildDirty / protocolVersion / parameterSetVersion を追加（Phase 25A）。</p>
     */
    public static final int SESSION_LOG_SCHEMA_VERSION = 2;

    /**
     * Human Study の実験手順（プロトコル）の版の既定値（Phase 25A）。
     *
     * <p>AI のパラメータの版（{@link #PARAMETER_SET_VERSION}）とは別物で、フェーズの順序・長さ・教示などの
     * <strong>実験手順</strong>を変えたら上げる。研究条件ではないので、変えても PARAMETER_SET_VERSION は上げない。
     * 実験者は {@code config/aiwolf.properties} の {@code humanStudyProtocolVersion} で上書きできる。</p>
     */
    public static final int HUMAN_STUDY_PROTOCOL_VERSION = 1;

    /** AI の停止・再開ログ（ai-availability.jsonl）のスキーマ版（Phase 22）。 */
    public static final int AI_AVAILABILITY_LOG_SCHEMA_VERSION = 1;

    /**
     * プレイヤーとオオカミの関係をサンプルする間隔 [ticks]（20 = 1 秒）。研究オオカミ 1 匹につき 1 回。
     * 研究条件ではなく計測の設定なので、変えても PARAMETER_SET_VERSION は上げない。
     */
    public static final int PLAYER_SAMPLE_INTERVAL_TICKS = 20;

    // ==================================================================
    // 内部状態
    // ==================================================================

    /** 内部状態量の下限。 */
    public static final double STATE_MIN = 0.0;

    /** 内部状態量の上限。 */
    public static final double STATE_MAX = 1.0;

    /** 初期 trust（飼い主への信頼）。 */
    public static final double INITIAL_TRUST = 0.50;

    /** 初期 attachment（愛着）。 */
    public static final double INITIAL_ATTACHMENT = 0.50;

    /** 初期 fear（恐怖感受性）。 */
    public static final double INITIAL_FEAR = 0.30;

    /** 初期 curiosity（好奇心）。 */
    public static final double INITIAL_CURIOSITY = 0.50;

    /** 初期 autonomy（自律性）。 */
    public static final double INITIAL_AUTONOMY = 0.50;

    /** 初期 stress（ストレス）。 */
    public static final double INITIAL_STRESS = 0.20;

    /** 初期 energy（活力）。 */
    public static final double INITIAL_ENERGY = 1.00;

    /** fear が時間とともに回帰するベースライン。 */
    public static final double BASELINE_FEAR = 0.30;

    /** stress が時間とともに回帰するベースライン。 */
    public static final double BASELINE_STRESS = 0.20;

    /** 感情状態のベースライン回帰率（1 回の回帰処理あたり）。 */
    public static final double EMOTIONAL_RELAXATION_RATE = 0.02;

    /** 探索・戦闘などの活動時に 1 回の energy 更新で失う量。 */
    public static final double ENERGY_ACTIVE_DELTA = -0.010;

    /** 休息時に 1 回の energy 更新で回復する量。 */
    public static final double ENERGY_REST_DELTA = 0.020;

    /** 追従・待機など低負荷時の 1 回あたりの energy 増分。 */
    public static final double ENERGY_IDLE_DELTA = -0.002;

    // ==================================================================
    // Context の正規化
    // ==================================================================

    /**
     * 飼い主距離の減衰スケール [blocks]。
     * {@code nearOwner = exp(-distance / OWNER_DISTANCE_SCALE)}。
     */
    public static final double OWNER_DISTANCE_SCALE = 6.0;

    /** 敵 MOB 密度が 1.0 に飽和する頭数。 */
    public static final double HOSTILE_COUNT_SATURATION = 4.0;

    /**
     * 最近接敵距離の減衰スケール [blocks]。
     * {@code nearestThreat = exp(-distance / NEAREST_HOSTILE_SCALE)}。
     */
    public static final double NEAREST_HOSTILE_SCALE = 8.0;

    /** 脅威スコアにおける敵密度の重み。 */
    public static final double THREAT_WEIGHT_HOSTILE_DENSITY = 0.5;

    /** 脅威スコアにおける最近接敵の重み。 */
    public static final double THREAT_WEIGHT_NEAREST = 0.3;

    /** 脅威スコアにおける戦闘状態の重み。 */
    public static final double THREAT_WEIGHT_COMBAT = 0.2;

    /** この値以上の HP 比率低下で「HP 低下」特徴量が立ち始める閾値。 */
    public static final double LOW_HEALTH_THRESHOLD = 0.40;

    /** 緊急退避を強制的に優勢にする HP 比率。 */
    public static final double EMERGENCY_HEALTH_THRESHOLD = 0.15;

    /** 危険 region とみなす locationFear の閾値（AVOID_LOCATION の実行可能条件）。 */
    public static final double DANGEROUS_REGION_THRESHOLD = 0.30;

    // ==================================================================
    // 行動選択
    // ==================================================================

    /** Softmax の温度パラメータ。低いほど決定論的、高いほどランダム。 */
    public static final double SOFTMAX_TEMPERATURE = 0.6;

    /** 現在実行中の行動へ加算する継続ボーナス（行動の振動を防ぐ）。 */
    public static final double PERSISTENCE_BONUS = 0.25;

    /**
     * 緊急時（HP が {@link #EMERGENCY_HEALTH_THRESHOLD} 未満）に RETREAT へ加える
     * 非常に大きなボーナス。if 分岐で強制せずスコア構造に組み込むことで、
     * 研究ログから「なぜ退避したか」を一貫した形式で解析できる。
     */
    public static final double EMERGENCY_RETREAT_BONUS = 5.0;

    /** 通常時の行動再評価間隔 [ticks]。 */
    public static final int DECISION_INTERVAL_TICKS = 40;

    /** 行動の最小継続時間 [ticks]。 */
    public static final int MINIMUM_BEHAVIOR_DURATION_TICKS = 60;

    /**
     * 時間依存の内部状態更新（感情のベースライン回帰・energy 動態）を 1 回行う間隔 [ticks]。
     *
     * <p><strong>これは「呼び出し回数」ではなく「経過 tick」を単位にするための定数である。</strong>
     * シミュレーションは 1 イベントにつき 600 tick 進めて 1 回更新し、実機は 40 tick ごとに
     * 決定するので、どちらも「何回呼ばれたか」で数えると時間の進み方が 15 倍ずれる。
     * {@code WolfAiAgent.advanceTime(elapsedTicks)} がこの間隔で回数を決めるため、
     * <strong>経過 tick が同じなら両者で内部状態の時間変化が一致する</strong>。</p>
     *
     * <p>値はシミュレーションの 1 イベント間隔と同じ 600 にしてある。
     * こうすると既存のシミュレーション結果がビット単位で変わらず、
     * パイロットを取り直さずに実機側の欠落だけを直せる。</p>
     */
    public static final int MAINTENANCE_INTERVAL_TICKS = 600;

    /**
     * 1 回の {@code advanceTime} で許す最大の更新回数。
     *
     * <p>ワールド時刻が大きく飛んだとき（`/time set`、長時間の放置、
     * 別ディメンションからの再読み込み）に数万回の更新が走るのを防ぐ。
     * 上限に当たった場合、その個体の感情は「完全にベースラインへ戻った」
     * 状態に近づくだけで、値が壊れることはない。</p>
     */
    public static final int MAX_MAINTENANCE_STEPS_PER_CALL = 64;

    // ==================================================================
    // エピソード記憶
    // ==================================================================

    /** 1 個体あたりの最大記憶件数。 */
    public static final int MAX_MEMORIES_PER_WOLF = 200;

    /** 行動計算へ渡す記憶の件数（Top-K）。 */
    public static final int MEMORY_RETRIEVAL_TOP_K = 5;

    /** 検索スコアにおける recency の重み。 */
    public static final double RETRIEVAL_WEIGHT_RECENCY = 0.25;

    /** 検索スコアにおける salience の重み。 */
    public static final double RETRIEVAL_WEIGHT_SALIENCE = 0.35;

    /** 検索スコアにおける relevance の重み。 */
    public static final double RETRIEVAL_WEIGHT_RELEVANCE = 0.40;

    /** recency の時定数 [ticks]。30 Minecraft 分相当（1 分 = 1200 ticks）。 */
    public static final double RECENCY_TAU_TICKS = 36_000.0;

    /** relevance におけるイベント種別一致の重み。 */
    public static final double RELEVANCE_WEIGHT_EVENT = 0.50;

    /** relevance における region 一致の重み。 */
    public static final double RELEVANCE_WEIGHT_REGION = 0.30;

    /** relevance における actor 一致の重み。 */
    public static final double RELEVANCE_WEIGHT_ACTOR = 0.20;

    /** salience 計算における |valence| の重み。 */
    public static final double SALIENCE_WEIGHT_VALENCE = 0.5;

    /** salience 計算における arousal の重み。 */
    public static final double SALIENCE_WEIGHT_AROUSAL = 0.5;

    /** MemoryEffect の絶対値上限。 */
    public static final double MEMORY_EFFECT_LIMIT = 1.0;

    /**
     * 容量超過時の剪定に使う保持スコアの重み（salience 側）。
     *
     * <p>検索スコアより salience を重くしている。「瀕死になった」ような重要な記憶が、
     * 直近の些細な記憶に押し出されて消えることを防ぐためである。</p>
     */
    public static final double RETENTION_WEIGHT_SALIENCE = 0.7;

    /** 容量超過時の剪定に使う保持スコアの重み（recency 側）。 */
    public static final double RETENTION_WEIGHT_RECENCY = 0.3;

    // ==================================================================
    // 行動選好の学習
    // ==================================================================

    /** 選好の下限。 */
    public static final double PREFERENCE_MIN = -1.0;

    /** 選好の上限。 */
    public static final double PREFERENCE_MAX = 1.0;

    /** 選好の忘却係数 λ。1 イベントでの変化を小さく保つ。 */
    public static final double PREFERENCE_DECAY_LAMBDA = 0.01;

    /** 選好の学習率 η。1 イベントで性格が激変しないよう十分小さくする。 */
    public static final double PREFERENCE_LEARNING_RATE = 0.03;

    /** 選好がスコアへ寄与する係数 ρ。 */
    public static final double PREFERENCE_SCORE_COEFFICIENT = 1.0;

    // ==================================================================
    // 空間記憶
    // ==================================================================

    /** 空間記憶の region 1 辺のサイズ [blocks]。座標をそのまま保持しないための量子化単位。 */
    public static final int SPATIAL_REGION_SIZE = 24;

    /** 1 個体が保持する region の最大数。 */
    public static final int MAX_SPATIAL_REGIONS = 128;

    /** region の恐怖値・安心値を更新する際の学習率。 */
    public static final double SPATIAL_LEARNING_RATE = 0.30;

    /** region の実効恐怖値を求める際に、安心値を差し引く係数。 */
    public static final double SPATIAL_POSITIVE_OFFSET = 0.5;

    /** 隣接 region（チェビシェフ距離 1）とみなすときの relevance の割引率。 */
    public static final double ADJACENT_REGION_MATCH = 0.5;

    // ==================================================================
    // Minecraft 側の実行パラメータ
    //
    // ここだけはゲーム世界の単位（blocks / ticks）を扱うが、値としては研究条件の
    // 一部なので同じ場所に集約する。
    // ==================================================================

    /** 敵性 MOB を探す半径 [blocks]。毎 tick ではなく決定時のみ実行する。 */
    public static final double HOSTILE_SCAN_RADIUS = 16.0;

    /**
     * 飼い主が「近傍にいる」とみなす最大距離 [blocks]。
     *
     * <p>文脈生成・出来事検出・分離判定のすべてがこの 1 つの値を使う。
     * 「近くにいる」という同じ概念に別々の閾値を作ると、
     * 「飼い主のそばで被弾した」のに「長期分離中」という矛盾した記録が生まれる。</p>
     */
    public static final double OWNER_PRESENCE_RADIUS = 48.0;

    /** 飼い主が移動中とみなす、決定間隔あたりの最小移動距離 [blocks]。 */
    public static final double OWNER_MOVING_THRESHOLD = 1.5;

    /** FOLLOW_CLOSE で維持しようとする飼い主との距離 [blocks]。 */
    public static final double FOLLOW_CLOSE_DISTANCE = 2.5;

    /** FOLLOW_NORMAL で維持しようとする飼い主との距離 [blocks]。 */
    public static final double FOLLOW_NORMAL_DISTANCE = 7.0;

    /** 探索時に目標地点を探す水平範囲 [blocks]。 */
    public static final int EXPLORE_HORIZONTAL_RANGE = 14;

    /** 探索・退避で目標地点を探す垂直範囲 [blocks]。 */
    public static final int TARGET_VERTICAL_RANGE = 7;

    /** 退避・回避で逃げる水平範囲 [blocks]。 */
    public static final int RETREAT_HORIZONTAL_RANGE = 18;

    /** 追従時の移動速度倍率。 */
    public static final double SPEED_FOLLOW = 1.0;

    /** 接近・近距離追従時の移動速度倍率。 */
    public static final double SPEED_APPROACH = 1.2;

    /** 探索時の移動速度倍率。 */
    public static final double SPEED_EXPLORE = 0.9;

    /** 退避時の移動速度倍率。 */
    public static final double SPEED_RETREAT = 1.35;

    /** 戦闘が「直近にあった」とみなす tick 数。 */
    public static final int RECENT_COMBAT_WINDOW_TICKS = 200;

    /**
     * combat episode を立ち消えとみなす無活動 tick 数。
     * これを過ぎたら結末を判定して閉じる。
     */
    public static final int COMBAT_EPISODE_TIMEOUT_TICKS = 200;

    /** 同時に追跡する combat episode の最大数。取りこぼしよりメモリリークを防ぐ方を優先する。 */
    public static final int MAX_OPEN_COMBAT_EPISODES = 8;

    /** デバッグ表示を更新する間隔 [ticks]。 */
    public static final int DEBUG_DISPLAY_INTERVAL_TICKS = 10;

    /**
     * PET の再受付までのクールダウン [ticks]。既定 10 秒。
     * 連打による無制限の関係性上昇を防ぐ。
     */
    public static final int PET_COOLDOWN_TICKS = 200;

    /** 飼い主と離れていたと判定するまでの tick 数。既定 2 分。 */
    public static final int LONG_SEPARATION_TICKS = 2_400;

    /** 安全な単独探索と認めるまでに必要な連続決定回数。 */
    public static final int SAFE_EXPLORATION_STREAK = 5;

    /** 単独探索とみなす飼い主からの最小距離 [blocks]。 */
    public static final double SOLO_EXPLORATION_DISTANCE = 12.0;

    /** 1 回の出来事として扱う最小の HP 減少量。これ未満の削れは無視する。 */
    public static final float MIN_SIGNIFICANT_DAMAGE = 0.5f;

    /** 同じ睡眠で SLEEP_NEAR_PLAYER を繰り返さないための間隔 [ticks]。 */
    public static final int SLEEP_EVENT_COOLDOWN_TICKS = 600;

    // ==================================================================
    // 実験制御・再現性
    // ==================================================================

    /** 既定の実験条件。 */
    public static final ExperimentalCondition DEFAULT_EXPERIMENTAL_CONDITION = ExperimentalCondition.ADAPTIVE;

    /**
     * 既定の実験シード。
     *
     * <p>行動選択の確率的挙動を再現するため、ワールド単位で固定シードを保持し、
     * 個体シードはここから決定論的に導出する（{@link #deriveIndividualSeed}）。</p>
     */
    public static final long DEFAULT_EXPERIMENT_SEED = 20260919L;

    /** エクスポート時に数値を丸める小数桁数。 */
    public static final int EXPORT_DECIMALS = 6;

    /** 研究ログをディスクへ書き出す間隔 [ticks]。既定 30 秒。 */
    public static final int RESEARCH_LOG_FLUSH_INTERVAL_TICKS = 600;

    /** バッファがこの行数を超えたら、間隔を待たずに書き出す。 */
    public static final int RESEARCH_LOG_BUFFER_LIMIT = 2_000;

    /** 研究データを置くワールド内のディレクトリ名。 */
    public static final String RESEARCH_DIRECTORY = "aiwolf-research";

    /**
     * 実験シードと個体 UUID から、個体固有の決定論的シードを導出する。
     *
     * <p>SplitMix64 の finalizer を用いて UUID のビットを撹拌する。
     * 同じ実験シードと同じ UUID からは常に同じ値が得られるため、実験の再現性が保たれる。</p>
     *
     * @param experimentSeed 実験単位のシード
     * @param wolfUuid       個体 UUID
     * @return 個体固有シード
     */
    public static long deriveIndividualSeed(long experimentSeed, UUID wolfUuid) {
        long mixed = experimentSeed;
        mixed = mix64(mixed ^ wolfUuid.getMostSignificantBits());
        mixed = mix64(mixed ^ wolfUuid.getLeastSignificantBits());
        return mixed;
    }

    /**
     * SplitMix64 の finalizer。ビット撹拌用。
     *
     * @param input 入力
     * @return 撹拌後の値
     */
    public static long mix64(long input) {
        long z = input + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
