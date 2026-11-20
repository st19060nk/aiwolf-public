package jp.ac.naist.hai.aiwolf.simulation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorEligibility;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScore;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScorer;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorWeights;
import jp.ac.naist.hai.aiwolf.behavior.PolicySelector;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.memory.MemoryImpactCalculator;
import jp.ac.naist.hai.aiwolf.memory.MemoryQuery;
import jp.ac.naist.hai.aiwolf.memory.MemoryRetriever;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.random.WolfRandomProvider;

/**
 * 標準テストバッテリ。個体の行動傾向（Behavioral Signature）を測る唯一の手続き。
 *
 * <p><strong>なぜ自由行動のログではなくこれを使うのか。</strong>自由に遊んでいる間の
 * 行動分布を個体間で比べると、「その個体が置かれていた状況の違い」と
 * 「その個体の傾向の違い」が混ざってしまう。よく撫でられた個体が飼い主の近くにいるのは、
 * 愛着が育ったからなのか、単に飼い主がそこにいたからなのか区別できない。
 * 全個体を<strong>まったく同一の文脈系列</strong>に置いて初めて、差を履歴に帰属できる。</p>
 *
 * <p>測定は閉ループで行う。飼い主との距離は選ばれた行動の結果として変化し
 * （接近すれば近づき、探索すれば離れる）、脅威・夜・地下の発生スケジュールは
 * 全個体で固定である。距離を固定したまま測ると「接近し続ける」退行的な分布になり、
 * 実際のゲーム内挙動と乖離する。</p>
 *
 * <p><strong>個体の状態を一切変更しない。</strong>内部状態・選好・記憶は読むだけで、
 * 乱数も測定専用のストリームを使う。したがって実機で何度測っても、
 * その個体のその後の振る舞いに影響しない。</p>
 *
 * <p>ここの定数を変えると過去に測った指紋と比較できなくなる。
 * 実験を始めたら変更しないこと。</p>
 */
public final class StandardTestBattery {

    /** 既定の測定試行数。 */
    public static final int DEFAULT_DECISIONS = 200;

    /** 測定開始時の飼い主との距離 [blocks]。 */
    private static final double INITIAL_DISTANCE = 8.0;

    /** 測定中に許す最大距離 [blocks]。 */
    private static final double MAX_DISTANCE = 40.0;

    /** 測定用の乱数ストリームを本番と分けるためのオフセット。 */
    private static final long EVALUATION_SEED_OFFSET = 0x5EED_BA11L;

    private static final BehaviorScorer SCORER = new BehaviorScorer(BehaviorWeights.createDefault());

    private StandardTestBattery() {
        throw new AssertionError("utility class");
    }

    /**
     * 既定の試行数で指紋を測る。
     *
     * @param agent        対象個体（変更されない）
     * @param region       記憶検索に使う領域（{@code null} 可）
     * @param actorId      記憶検索に使う飼い主の匿名 ID（{@code null} 可）
     * @param worldTime    測定開始時のワールド時刻
     * @param sessionIndex 測定セッション番号。詳細は
     *                     {@link #measure(WolfAiAgent, SpatialRegion, String, long, long, int, double)}
     * @return 行動指紋
     */
    public static BehaviouralSignature measure(WolfAiAgent agent, SpatialRegion region,
                                               String actorId, long worldTime, long sessionIndex) {
        return measure(agent, region, actorId, worldTime, sessionIndex, DEFAULT_DECISIONS,
                AiWolfParameters.SOFTMAX_TEMPERATURE);
    }

    /**
     * 試行数と温度を明示して指紋を測る。
     *
     * <p>{@code sessionIndex} は測定セッションを識別する番号で、乱数ストリームを分ける。
     * <strong>同じ個体を同じ番号で測れば必ず同じ結果になり、違う番号で測れば
     * サンプリング誤差の分だけ結果が揺れる。</strong>前者は再現性の担保に、
     * 後者は仮説 H2（同一個体の指紋が評価セッション間で一貫するか）の測定に使う。
     * 番号を固定してしまうと個体内距離が構造上ゼロになり、一貫性を測ったことにならない。</p>
     *
     * @param agent        対象個体（変更されない）
     * @param region       記憶検索に使う領域（{@code null} 可）
     * @param actorId      記憶検索に使う飼い主の匿名 ID（{@code null} 可）
     * @param worldTime    測定開始時のワールド時刻
     * @param sessionIndex 測定セッション番号
     * @param decisions    試行数（正の値）
     * @param temperature  Softmax 温度
     * @return 行動指紋
     */
    public static BehaviouralSignature measure(WolfAiAgent agent, SpatialRegion region, String actorId,
                                               long worldTime, long sessionIndex,
                                               int decisions, double temperature) {
        Objects.requireNonNull(agent, "agent");
        if (decisions <= 0) {
            throw new IllegalArgumentException("decisions must be > 0 but was " + decisions);
        }

        // 行動指紋は試行の記録（trace）から作る。trace が唯一の計算経路で、
        // 合計の順序も以前と同じなので、指紋の値はビット単位で変わらない。
        List<Step> steps = trace(agent, region, actorId, worldTime, sessionIndex, decisions, temperature);
        Map<BehaviorType, Integer> counts = new EnumMap<>(BehaviorType.class);
        double distanceSum = 0.0;
        for (Step step : steps) {
            counts.merge(step.behavior(), 1, Integer::sum);
            distanceSum += step.ownerDistance();
        }
        return BehaviouralSignature.fromCounts(counts, distanceSum / decisions);
    }

    /**
     * 1 試行の記録。
     *
     * @param index         試行番号（0 始まり）
     * @param behavior      選ばれた行動
     * @param threatWindow  その試行が脅威の区間（敵 2 体が 7 blocks）だったか
     * @param ownerDistance 行動を反映した後の飼い主との距離 [blocks]
     * @param regionFear    文脈へ渡した場所の恐怖値（空間記憶を使わない条件では 0）
     */
    public record Step(int index, BehaviorType behavior, boolean threatWindow, double ownerDistance,
                       double regionFear) {
    }

    /**
     * 標準テストを実行し、試行ごとの記録を返す。{@link #measure} と同じ計算経路・同じ乱数列。
     * 個体の状態は変更しない。
     *
     * @param agent        測定対象（変更しない）
     * @param region       測定を行う領域
     * @param actorId      記憶検索に使う飼い主の匿名 ID（{@code null} 可）
     * @param worldTime    測定開始時のワールド時刻
     * @param sessionIndex 測定セッション番号
     * @param decisions    試行数（正の値）
     * @param temperature  Softmax 温度
     * @return 試行の記録（試行順）
     */
    public static List<Step> trace(WolfAiAgent agent, SpatialRegion region, String actorId,
                                   long worldTime, long sessionIndex, int decisions, double temperature) {
        Objects.requireNonNull(agent, "agent");
        if (decisions <= 0) {
            throw new IllegalArgumentException("decisions must be > 0 but was " + decisions);
        }

        PolicySelector selector = new PolicySelector(temperature);
        // 測定専用の乱数ストリーム。本番の decisionIndex を消費しないので、
        // 何度測っても個体のその後の行動列は変わらない。
        WolfRandomProvider random = WolfRandomProvider.restore(
                agent.random().seed() ^ EVALUATION_SEED_OFFSET
                        ^ AiWolfParameters.mix64(sessionIndex), 0L);

        List<Step> steps = new java.util.ArrayList<>(decisions);
        double ownerDistance = INITIAL_DISTANCE;
        BehaviorType current = null;
        long time = worldTime;

        for (int i = 0; i < decisions; i++) {
            time += AiWolfParameters.DECISION_INTERVAL_TICKS;
            double regionFear = agent.regionFear(region);
            WolfContextSnapshot context = contextFor(i, ownerDistance, regionFear);

            MemorySummary memory = MemorySummary.empty();
            if (agent.condition().usesEpisodicMemory()) {
                memory = MemoryImpactCalculator.summarise(MemoryRetriever.retrieve(
                        agent.memoryStore(), MemoryQuery.atLocation(region, actorId, time)));
            }

            Set<BehaviorType> eligible = BehaviorEligibility.evaluate(context);
            Map<BehaviorType, BehaviorScore> scores = SCORER.score(agent.state(), context, memory,
                    agent.preferences(), current, eligible, agent.condition());
            BehaviorDecision decision = selector.select(scores, random);

            current = decision.selected();
            ownerDistance = updateOwnerDistance(ownerDistance, current);
            steps.add(new Step(i, current, isThreatWindow(i), ownerDistance, regionFear));
        }
        return steps;
    }

    /**
     * 標準テストの脅威区間（試行 10 回ごとに 4 区間に 1 回）。{@link #contextFor} と同じ定義。
     *
     * @param step 試行番号
     * @return 脅威区間なら true
     */
    public static boolean isThreatWindow(int step) {
        return (step / 10) % 4 == 0;
    }

    /**
     * 試行番号に応じた標準文脈を作る。脅威と夜のスケジュールは全個体で同一である。
     *
     * @param step          試行番号
     * @param ownerDistance 現在の飼い主との距離
     * @param regionFear    現在領域の恐怖値
     * @return 文脈スナップショット
     */
    private static WolfContextSnapshot contextFor(int step, double ownerDistance, double regionFear) {
        boolean threatWindow = isThreatWindow(step);
        boolean night = (step / 25) % 2 == 1;
        return WolfContextSnapshot.builder()
                .ownerDistance(ownerDistance)
                .ownerMoving(step % 3 != 0)
                .hostileMobCount(threatWindow ? 2 : 0)
                .nearestHostileDistance(threatWindow ? 7.0 : WolfContextSnapshot.NO_TARGET_DISTANCE)
                .night(night)
                .underground(night && threatWindow)
                .regionFear(regionFear)
                .build();
    }

    /**
     * 選ばれた行動に応じて飼い主との距離を更新する、ゲーム内移動の粗い近似。
     *
     * @param distance 現在の距離 [blocks]
     * @param behavior 選ばれた行動
     * @return 更新後の距離 [blocks]
     */
    private static double updateOwnerDistance(double distance, BehaviorType behavior) {
        double updated = switch (behavior) {
            case APPROACH_PLAYER -> distance * 0.5;
            case FOLLOW_CLOSE -> distance + (2.0 - distance) * 0.6;
            case FOLLOW_NORMAL, DEFEND_PLAYER -> distance + (6.0 - distance) * 0.4;
            case EXPLORE_NEARBY -> distance + 3.0;
            case RETREAT, AVOID_LOCATION -> distance + 1.5;
            case REST_NEAR_PLAYER -> distance;
        };
        return Math.min(MAX_DISTANCE, Math.max(0.5, updated));
    }

    /**
     * 測定に使う標準文脈の説明。研究ログと論文の記述を一致させるために公開している。
     *
     * @return 説明文の行
     */
    public static List<String> describeProtocol() {
        return List.of(
                "decisions=" + DEFAULT_DECISIONS,
                "initialOwnerDistance=" + INITIAL_DISTANCE,
                "threatWindow=(step/10)%4==0 with 2 hostiles at 7 blocks",
                "night=(step/25)%2==1, underground=night&&threatWindow",
                "ownerMoving=step%3!=0",
                "closed loop: owner distance responds to the selected behaviour");
    }
}
