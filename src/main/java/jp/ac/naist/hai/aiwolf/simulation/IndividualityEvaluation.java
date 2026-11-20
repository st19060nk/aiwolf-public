package jp.ac.naist.hai.aiwolf.simulation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.simulation.StateSaturationSimulation.Intensity;
import jp.ac.naist.hai.aiwolf.simulation.StateSaturationSimulation.Pattern;

/**
 * Phase 17：アブレーション条件ごとの個体性評価。<strong>Minecraft に依存しない。</strong>
 *
 * <p>研究質問「複雑な記憶は、AI ペットの個性形成に必要か」に答えるための測定。
 * 各条件で、異なる経験履歴（育成パターン A〜D）を持つ個体を作り、
 * 同じ評価バッテリで行動指紋を測って、個体内の一貫性と個体間の差を比べる。</p>
 *
 * <h2>統制</h2>
 * <ul>
 *   <li>育成は {@link StateSaturationSimulation#rear} と同一（同じ出来事列・同じ文脈規則）。</li>
 *   <li>個体 ID と乱数はパターンとシードだけで決まり、<strong>条件を変えても同じ出来事列・同じ乱数列</strong>になる。
 *       条件間の差は学習・記憶機構の ON / OFF だけから生じる。</li>
 *   <li>評価は既存の標準テストバッテリ（{@link StandardTestBattery#trace}、200 決定）を使う。個体は変更しない。</li>
 * </ul>
 *
 * <h2>行動指紋（1 セッション分）</h2>
 * <ul>
 *   <li>8 行動の出現割合（拠点の領域で測定）</li>
 *   <li>dangerRetreatRate / dangerDefendRate：脅威区間（200 決定中 50 決定）での RETREAT / DEFEND_PLAYER の割合</li>
 *   <li>probeAvoidRate：危険地帯の領域（パターン C が被弾した最初の場所）で測った AVOID_LOCATION の割合</li>
 *   <li>meanOwnerDistance / 40：標準テストの飼い主距離（上限 40 blocks）を 0〜1 に正規化</li>
 * </ul>
 * <p>研究計画の explorationRadius と dangerRegionRevisitRate は、このバッテリが空間を移動しないため測れない。
 * probeAvoidRate がその場所を避ける傾向の代わりになる。</p>
 *
 * <h2>距離</h2>
 * <ul>
 *   <li>Euclidean：上の 12 次元のベクトルのユークリッド距離</li>
 *   <li>Cosine：同じベクトルの 1 − cos 類似度</li>
 *   <li>Jensen–Shannon：8 行動の出現割合（確率分布）の JS ダイバージェンス（底 2、0〜1）</li>
 * </ul>
 * <p>個体内距離 = 同じ個体の 3 セッションの総当たり（3 組）の平均。個体間距離 = 同じシードの
 * 異なるパターンの個体どうし（6 組、各セッション 1）の平均。分離比 = 個体間 ÷ 個体内（シードごとに計算）。</p>
 *
 * <p>実行：{@code ./gradlew individuality}（出力先は {@code research-output/individuality/}）。</p>
 */
public final class IndividualityEvaluation {

    /** 比較する条件（研究計画の 5 条件 + 記憶の追加分解 2 条件）。 */
    public static final List<ExperimentalCondition> CONDITIONS = List.of(
            ExperimentalCondition.STATIC,
            ExperimentalCondition.STATE_ONLY,
            ExperimentalCondition.STATE_EPISODIC,
            ExperimentalCondition.STATE_SPATIAL,
            ExperimentalCondition.STATE_MEMORY,
            ExperimentalCondition.STATE_PREFERENCE,
            ExperimentalCondition.ADAPTIVE);

    /** 既定の育成時間 [分]：1 セッション相当と、天井効果が出揃う長さ。 */
    public static final List<Integer> DEFAULT_DURATIONS = List.of(30, 120);

    /**
     * 既定の評価までの間隔 [分]。0 = 育成直後、60 = 出来事のない 60 分を挟んだ後（次のセッションの想定）。
     *
     * <p>育成直後に測ると、最後の数分の出来事で一時的に高まった fear・stress（学習を行わない STATIC でも動く）が
     * そのまま指紋に入り、学習による個体差と区別できない。間隔を置くと一時的な感情はベースラインへ戻り
     * （実機と同じ回帰規則）、学習によって残った差だけが測られる。</p>
     */
    public static final List<Integer> DEFAULT_DELAYS = List.of(0, 60);

    /** 1 個体あたりの評価セッション数。 */
    public static final int SESSIONS = 3;

    /** 1 セッションあたりの決定数（標準テストバッテリの既定）。 */
    public static final int DECISIONS = StandardTestBattery.DEFAULT_DECISIONS;

    /** 飼い主距離の正規化に使う上限 [blocks]（標準テストバッテリの上限と同じ）。 */
    public static final double DISTANCE_SCALE = 40.0;

    private static final String DIMENSION = "minecraft:overworld";

    /** 行動指紋の次元名（CSV の列名と同じ順序）。 */
    public static final List<String> FEATURES = buildFeatureNames();

    private IndividualityEvaluation() {
        throw new AssertionError("entry point holder");
    }

    private static List<String> buildFeatureNames() {
        List<String> names = new ArrayList<>();
        for (BehaviorType behavior : BehaviorType.ALL) {
            names.add("rate_" + behavior.id());
        }
        names.add("dangerRetreatRate");
        names.add("dangerDefendRate");
        names.add("probeAvoidRate");
        names.add("meanOwnerDistanceNorm");
        return List.copyOf(names);
    }

    /**
     * 1 セッションの行動指紋。
     *
     * @param features {@link #FEATURES} の順の値
     * @param meanOwnerDistance 正規化前の平均飼い主距離 [blocks]
     */
    public record Signature(double[] features, double meanOwnerDistance) {

        /** @return 8 行動の出現割合（合計 1） */
        public double[] behaviorRates() {
            return java.util.Arrays.copyOf(features, BehaviorType.ALL.size());
        }
    }

    /**
     * 1 個体の評価結果。
     *
     * @param condition 条件
     * @param minutes   育成時間 [分]
     * @param delayMinutes 育成終了から評価までの間隔 [分]
     * @param seed      シード
     * @param pattern   育成パターン
     * @param sessions  セッションごとの行動指紋（{@link #SESSIONS} 個）
     * @param finalState 評価時点の内部状態（状態名 → 値）
     */
    public record Wolf(ExperimentalCondition condition, int minutes, int delayMinutes, long seed, Pattern pattern,
                       List<Signature> sessions, Map<String, Double> finalState) {
    }

    /**
     * 1 個体を育成して評価する。同じ引数なら同じ結果。
     *
     * @param condition 条件
     * @param pattern   育成パターン
     * @param seed      シード
     * @param minutes   育成時間 [分]
     * @param delayMinutes 育成終了から評価までの間隔 [分]（その間は出来事なし・平穏な文脈で過ごす）
     * @return 評価結果
     */
    public static Wolf evaluate(ExperimentalCondition condition, Pattern pattern, long seed, int minutes,
                                int delayMinutes) {
        WolfAiAgent agent = StateSaturationSimulation.rear(pattern, Intensity.TYPICAL, seed, minutes, condition,
                new EnumMap<>(WolfEventType.class), null);
        long worldTime = (long) minutes * 1_200L;
        SpatialRegion home = SpatialRegion.of(DIMENSION, 0.0, 64.0, 0.0);
        // 間隔：出来事なしで、拠点の平穏な文脈（飼い主 6 blocks、敵なし、昼）で 2 秒ごとに決定する。
        // 時間の進め方は育成と同じ advanceTime なので、感情の回帰・energy の増減は実機と同じ規則で起きる。
        long delayTicks = (long) delayMinutes * 1_200L;
        for (long t = 0; t < delayTicks; t += AiWolfParameters.DECISION_INTERVAL_TICKS) {
            agent.advanceTime(AiWolfParameters.DECISION_INTERVAL_TICKS);
            worldTime += AiWolfParameters.DECISION_INTERVAL_TICKS;
            agent.decide(jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot.builder()
                            .ownerDistance(6.0).ownerMoving((t / AiWolfParameters.DECISION_INTERVAL_TICKS) % 2 == 0)
                            .regionFear(agent.regionFear(home)).build(),
                    home, StateSaturationSimulation.ownerId(), worldTime);
        }
        SpatialRegion probe = StateSaturationSimulation.regionOf(Pattern.DANGER_HEAVY, 0L);
        String owner = StateSaturationSimulation.ownerId();

        List<Signature> sessions = new ArrayList<>();
        for (int session = 1; session <= SESSIONS; session++) {
            List<StandardTestBattery.Step> homeSteps = StandardTestBattery.trace(agent, home, owner, worldTime,
                    session, DECISIONS, AiWolfParameters.SOFTMAX_TEMPERATURE);
            List<StandardTestBattery.Step> probeSteps = StandardTestBattery.trace(agent, probe, owner, worldTime,
                    1_000L + session, DECISIONS, AiWolfParameters.SOFTMAX_TEMPERATURE);
            sessions.add(signature(homeSteps, probeSteps));
        }
        Map<String, Double> state = new java.util.LinkedHashMap<>();
        agent.state().toValueMap().forEach(state::put);
        return new Wolf(condition, minutes, delayMinutes, seed, pattern, List.copyOf(sessions), state);
    }

    /**
     * 標準テストの記録から行動指紋を作る。
     *
     * @param homeSteps  拠点での記録
     * @param probeSteps 危険地帯での記録
     * @return 行動指紋
     */
    static Signature signature(List<StandardTestBattery.Step> homeSteps, List<StandardTestBattery.Step> probeSteps) {
        double[] features = new double[FEATURES.size()];
        int threatSteps = 0;
        int threatRetreat = 0;
        int threatDefend = 0;
        double distanceSum = 0.0;
        for (StandardTestBattery.Step step : homeSteps) {
            features[step.behavior().ordinal()] += 1.0 / homeSteps.size();
            distanceSum += step.ownerDistance();
            if (step.threatWindow()) {
                threatSteps++;
                if (step.behavior() == BehaviorType.RETREAT) {
                    threatRetreat++;
                }
                if (step.behavior() == BehaviorType.DEFEND_PLAYER) {
                    threatDefend++;
                }
            }
        }
        int avoid = 0;
        for (StandardTestBattery.Step step : probeSteps) {
            if (step.behavior() == BehaviorType.AVOID_LOCATION) {
                avoid++;
            }
        }
        int base = BehaviorType.ALL.size();
        double meanDistance = distanceSum / homeSteps.size();
        features[base] = threatSteps == 0 ? 0.0 : threatRetreat / (double) threatSteps;
        features[base + 1] = threatSteps == 0 ? 0.0 : threatDefend / (double) threatSteps;
        features[base + 2] = probeSteps.isEmpty() ? 0.0 : avoid / (double) probeSteps.size();
        features[base + 3] = meanDistance / DISTANCE_SCALE;
        return new Signature(features, meanDistance);
    }

    // ------------------------------------------------------------------
    // 距離
    // ------------------------------------------------------------------

    /**
     * ユークリッド距離。
     *
     * @param a ベクトル
     * @param b ベクトル
     * @return 距離
     */
    public static double euclidean(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (a[i] - b[i]) * (a[i] - b[i]);
        }
        return Math.sqrt(sum);
    }

    /**
     * コサイン距離（1 − cos 類似度）。どちらかが零ベクトルなら 0。
     *
     * @param a ベクトル
     * @param b ベクトル
     * @return 距離（0〜1。非負ベクトルなので 1 を超えない）
     */
    public static double cosine(double[] a, double[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return 1.0 - dot / Math.sqrt(na * nb);
    }

    /**
     * Jensen–Shannon ダイバージェンス（底 2、0〜1）。確率分布にだけ使う。
     *
     * @param p 確率分布
     * @param q 確率分布
     * @return ダイバージェンス
     */
    public static double jensenShannon(double[] p, double[] q) {
        double divergence = 0.0;
        for (int i = 0; i < p.length; i++) {
            double m = (p[i] + q[i]) / 2.0;
            divergence += 0.5 * term(p[i], m) + 0.5 * term(q[i], m);
        }
        return Math.max(0.0, divergence);
    }

    private static double term(double x, double m) {
        return x <= 0.0 ? 0.0 : x * (Math.log(x / m) / Math.log(2.0));
    }

    /**
     * 2 つの行動指紋の距離 3 種。
     *
     * @param a 行動指紋
     * @param b 行動指紋
     * @return {euclidean, cosine, jensenShannon}
     */
    public static double[] distances(Signature a, Signature b) {
        return new double[] {euclidean(a.features(), b.features()), cosine(a.features(), b.features()),
                jensenShannon(a.behaviorRates(), b.behaviorRates())};
    }

    // ------------------------------------------------------------------
    // 集計
    // ------------------------------------------------------------------

    /**
     * 1 つのシードの個体内・個体間距離と分離比。
     *
     * @param within  個体内距離の平均 {euclidean, cosine, js}
     * @param between 個体間距離の平均 {euclidean, cosine, js}
     */
    public record SeedResult(double[] within, double[] between) {

        /**
         * @param metric 0 = euclidean, 1 = cosine, 2 = js
         * @return 分離比（個体間 ÷ 個体内）
         */
        public double ratio(int metric) {
            return within[metric] <= 0.0 ? Double.POSITIVE_INFINITY : between[metric] / within[metric];
        }
    }

    /**
     * 同じ条件・時間・シードの 4 個体から、個体内・個体間距離を計算する。
     *
     * @param wolves 4 パターンの個体
     * @return 結果
     */
    public static SeedResult seedResult(List<Wolf> wolves) {
        double[] within = new double[3];
        int withinPairs = 0;
        for (Wolf wolf : wolves) {
            for (int i = 0; i < wolf.sessions().size(); i++) {
                for (int j = i + 1; j < wolf.sessions().size(); j++) {
                    add(within, distances(wolf.sessions().get(i), wolf.sessions().get(j)));
                    withinPairs++;
                }
            }
        }
        double[] between = new double[3];
        int betweenPairs = 0;
        for (int i = 0; i < wolves.size(); i++) {
            for (int j = i + 1; j < wolves.size(); j++) {
                add(between, distances(wolves.get(i).sessions().get(0), wolves.get(j).sessions().get(0)));
                betweenPairs++;
            }
        }
        for (int k = 0; k < 3; k++) {
            within[k] /= Math.max(1, withinPairs);
            between[k] /= Math.max(1, betweenPairs);
        }
        return new SeedResult(within, between);
    }

    private static void add(double[] sum, double[] values) {
        for (int i = 0; i < sum.length; i++) {
            sum[i] += values[i];
        }
    }

    // ------------------------------------------------------------------
    // 実行と出力
    // ------------------------------------------------------------------

    /**
     * 引数：{@code --seeds=N}（既定 30）、{@code --minutes=30,120}、{@code --out=DIR}
     * （既定 research-output/individuality）。
     *
     * @param args 引数
     */
    public static void main(String[] args) {
        int seeds = StateSaturationSimulation.DEFAULT_SEEDS;
        List<Integer> durations = DEFAULT_DURATIONS;
        List<Integer> delays = DEFAULT_DELAYS;
        Path out = Path.of("research-output", "individuality");
        for (String arg : args) {
            if (arg.startsWith("--seeds=")) {
                seeds = Integer.parseInt(arg.substring("--seeds=".length()));
            } else if (arg.startsWith("--minutes=")) {
                durations = java.util.Arrays.stream(arg.substring("--minutes=".length()).split(","))
                        .map(Integer::parseInt).toList();
            } else if (arg.startsWith("--delays=")) {
                delays = java.util.Arrays.stream(arg.substring("--delays=".length()).split(","))
                        .map(Integer::parseInt).toList();
            } else if (arg.startsWith("--out=")) {
                out = Path.of(arg.substring("--out=".length()));
            } else {
                throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        List<Wolf> wolves = run(seeds, durations, delays);
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("signatures.csv"), signaturesCsv(wolves), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("distances.csv"), distancesCsv(wolves), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("separation_by_seed.csv"), seedCsv(wolves), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("individuality_report.md"), report(wolves, seeds, durations, delays),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        System.out.println("evaluated " + wolves.size() + " wolves; wrote " + out.toAbsolutePath());
    }

    /**
     * すべての条件・時間・シード・パターンを評価する（並列。結果の順序は決定論的）。
     *
     * @param seeds     シード数
     * @param durations 育成時間 [分]
     * @param delays    評価までの間隔 [分]
     * @return 評価結果（条件 → 時間 → 間隔 → シード → パターンの順）
     */
    public static List<Wolf> run(int seeds, List<Integer> durations, List<Integer> delays) {
        record Task(ExperimentalCondition condition, int minutes, int delay, long seed, Pattern pattern) {
        }
        List<Task> tasks = new ArrayList<>();
        for (ExperimentalCondition condition : CONDITIONS) {
            for (int minutes : durations) {
                for (int delay : delays) {
                    for (int i = 0; i < seeds; i++) {
                        for (Pattern pattern : Pattern.values()) {
                            tasks.add(new Task(condition, minutes, delay, StateSaturationSimulation.BASE_SEED + i,
                                    pattern));
                        }
                    }
                }
            }
        }
        Wolf[] results = new Wolf[tasks.size()];
        IntStream.range(0, tasks.size()).parallel().forEach(index -> {
            Task task = tasks.get(index);
            results[index] = evaluate(task.condition(), task.pattern(), task.seed(), task.minutes(), task.delay());
        });
        return List.of(results);
    }

    private static List<List<Wolf>> bySeed(List<Wolf> wolves, ExperimentalCondition condition, int minutes,
                                           int delay) {
        Map<Long, List<Wolf>> groups = new java.util.TreeMap<>();
        for (Wolf wolf : wolves) {
            if (wolf.condition() == condition && wolf.minutes() == minutes && wolf.delayMinutes() == delay) {
                groups.computeIfAbsent(wolf.seed(), key -> new ArrayList<>()).add(wolf);
            }
        }
        List<List<Wolf>> result = new ArrayList<>();
        for (List<Wolf> group : groups.values()) {
            group.sort(Comparator.comparing(Wolf::pattern));
            result.add(group);
        }
        return result;
    }

    private static String signaturesCsv(List<Wolf> wolves) {
        StringBuilder csv = new StringBuilder(
                "parameterSetVersion,condition,features,minutes,delayMinutes,seed,pattern,session,meanOwnerDistance");
        for (String feature : FEATURES) {
            csv.append(',').append(feature);
        }
        for (String state : wolves.isEmpty() ? List.<String>of() : wolves.get(0).finalState().keySet()) {
            csv.append(",final_").append(state);
        }
        csv.append('\n');
        for (Wolf wolf : wolves) {
            for (int s = 0; s < wolf.sessions().size(); s++) {
                Signature signature = wolf.sessions().get(s);
                csv.append(String.format(Locale.ROOT, "%d,%s,%s,%d,%d,%d,%s,%d,%.4f",
                        AiWolfParameters.PARAMETER_SET_VERSION, wolf.condition().id(),
                        wolf.condition().features().describe(), wolf.minutes(), wolf.delayMinutes(), wolf.seed(),
                        wolf.pattern().id(), s + 1, signature.meanOwnerDistance()));
                for (double value : signature.features()) {
                    csv.append(String.format(Locale.ROOT, ",%.5f", value));
                }
                for (double value : wolf.finalState().values()) {
                    csv.append(String.format(Locale.ROOT, ",%.5f", value));
                }
                csv.append('\n');
            }
        }
        return csv.toString();
    }

    private static String distancesCsv(List<Wolf> wolves) {
        StringBuilder csv = new StringBuilder(
                "condition,minutes,delayMinutes,seed,kind,patternA,sessionA,patternB,sessionB,euclidean,cosine,jensenShannon\n");
        for (ExperimentalCondition condition : CONDITIONS) {
            for (int minutes : minutesOf(wolves)) {
              for (int delay : delaysOf(wolves)) {
                for (List<Wolf> group : bySeed(wolves, condition, minutes, delay)) {
                    for (Wolf wolf : group) {
                        for (int i = 0; i < wolf.sessions().size(); i++) {
                            for (int j = i + 1; j < wolf.sessions().size(); j++) {
                                appendDistance(csv, wolf, "within", wolf, i, j);
                            }
                        }
                    }
                    for (int i = 0; i < group.size(); i++) {
                        for (int j = i + 1; j < group.size(); j++) {
                            appendDistance(csv, group.get(i), "between", group.get(j), 0, 0);
                        }
                    }
                }
              }
            }
        }
        return csv.toString();
    }

    private static void appendDistance(StringBuilder csv, Wolf a, String kind, Wolf b, int sessionA, int sessionB) {
        double[] d = distances(a.sessions().get(sessionA), b.sessions().get(sessionB));
        csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%s,%s,%d,%s,%d,%.6f,%.6f,%.6f%n",
                a.condition().id(), a.minutes(), a.delayMinutes(), a.seed(), kind, a.pattern().id(), sessionA + 1,
                b.pattern().id(), sessionB + 1, d[0], d[1], d[2]));
    }

    private static List<Integer> minutesOf(List<Wolf> wolves) {
        return wolves.stream().map(Wolf::minutes).distinct().sorted().toList();
    }

    private static List<Integer> delaysOf(List<Wolf> wolves) {
        return wolves.stream().map(Wolf::delayMinutes).distinct().sorted().toList();
    }

    private static String seedCsv(List<Wolf> wolves) {
        StringBuilder csv = new StringBuilder("condition,minutes,delayMinutes,seed,withinEuclidean,betweenEuclidean,ratioEuclidean,"
                + "withinCosine,betweenCosine,ratioCosine,withinJS,betweenJS,ratioJS\n");
        for (ExperimentalCondition condition : CONDITIONS) {
            for (int minutes : minutesOf(wolves)) {
              for (int delay : delaysOf(wolves)) {
                for (List<Wolf> group : bySeed(wolves, condition, minutes, delay)) {
                    SeedResult result = seedResult(group);
                    csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%.6f,%.6f,%.4f,%.6f,%.6f,%.4f,%.6f,%.6f,%.4f%n",
                            condition.id(), minutes, delay, group.get(0).seed(),
                            result.within()[0], result.between()[0], result.ratio(0),
                            result.within()[1], result.between()[1], result.ratio(1),
                            result.within()[2], result.between()[2], result.ratio(2)));
                }
              }
            }
        }
        return csv.toString();
    }

    private static double[] meanSd(List<Double> values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        double mean = sum / values.size();
        double squares = 0.0;
        for (double value : values) {
            squares += (value - mean) * (value - mean);
        }
        return new double[] {mean, values.size() > 1 ? Math.sqrt(squares / (values.size() - 1)) : 0.0};
    }

    static String report(List<Wolf> wolves, int seeds, List<Integer> durations, List<Integer> delays) {
        StringBuilder md = new StringBuilder("# Individuality evaluation report\n\n");
        md.append(String.format(Locale.ROOT,
                "PARAMETER_SET_VERSION=%d, intensity=typical, seeds=%d (%d..%d), sessions=%d × %d decisions, "
                        + "patterns=A_owner/B_exploration/C_danger/D_mixed%n%n",
                AiWolfParameters.PARAMETER_SET_VERSION, seeds, StateSaturationSimulation.BASE_SEED,
                StateSaturationSimulation.BASE_SEED + seeds - 1, SESSIONS, DECISIONS));
        md.append("値はシードごとに計算した値の**平均 ± 標準偏差（n = シード数）**。分離比 = 個体間距離 ÷ 個体内距離。\n\n");
        for (int minutes : durations) {
          for (int delay : delays) {
            md.append("## 育成 ").append(minutes).append(" 分 / 評価まで ").append(delay).append(" 分\n\n");
            md.append("| 条件 | 機構 | 個体内 (Euclid) | 個体間 (Euclid) | 分離比 (Euclid) | 分離比 (Cosine) | 分離比 (JS) |\n");
            md.append("|---|---|---:|---:|---:|---:|---:|\n");
            for (ExperimentalCondition condition : CONDITIONS) {
                List<List<Wolf>> groups = bySeed(wolves, condition, minutes, delay);
                List<Double> within = new ArrayList<>();
                List<Double> between = new ArrayList<>();
                List<Double> ratioE = new ArrayList<>();
                List<Double> ratioC = new ArrayList<>();
                List<Double> ratioJ = new ArrayList<>();
                for (List<Wolf> group : groups) {
                    SeedResult result = seedResult(group);
                    within.add(result.within()[0]);
                    between.add(result.between()[0]);
                    ratioE.add(result.ratio(0));
                    ratioC.add(result.ratio(1));
                    ratioJ.add(result.ratio(2));
                }
                double[] w = meanSd(within);
                double[] b = meanSd(between);
                double[] re = meanSd(ratioE);
                double[] rc = meanSd(ratioC);
                double[] rj = meanSd(ratioJ);
                md.append(String.format(Locale.ROOT,
                        "| %s | %s | %.3f ± %.3f | %.3f ± %.3f | %.2f ± %.2f | %.2f ± %.2f | %.2f ± %.2f |%n",
                        condition.id(), condition.features().describe(), w[0], w[1], b[0], b[1],
                        re[0], re[1], rc[0], rc[1], rj[0], rj[1]));
            }
            md.append("\n### パターンごとの行動指紋（セッション 1、シード平均）\n\n");
            md.append("| 条件 | パターン | APPROACH | FOLLOW_CLOSE | FOLLOW_NORMAL | EXPLORE | DEFEND | RETREAT | "
                    + "AVOID(拠点) | 危険時 RETREAT | 危険時 DEFEND | 危険地帯 AVOID | 平均距離 [blocks] |\n");
            md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (ExperimentalCondition condition : CONDITIONS) {
                for (Pattern pattern : Pattern.values()) {
                    double[] mean = new double[FEATURES.size()];
                    double distance = 0.0;
                    int n = 0;
                    for (Wolf wolf : wolves) {
                        if (wolf.condition() == condition && wolf.minutes() == minutes
                                && wolf.delayMinutes() == delay && wolf.pattern() == pattern) {
                            double[] features = wolf.sessions().get(0).features();
                            for (int i = 0; i < mean.length; i++) {
                                mean[i] += features[i];
                            }
                            distance += wolf.sessions().get(0).meanOwnerDistance();
                            n++;
                        }
                    }
                    if (n == 0) {
                        continue;
                    }
                    int base = BehaviorType.ALL.size();
                    md.append(String.format(Locale.ROOT,
                            "| %s | %s | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |%n",
                            condition.id(), pattern.id(),
                            pct(mean, BehaviorType.APPROACH_PLAYER, n), pct(mean, BehaviorType.FOLLOW_CLOSE, n),
                            pct(mean, BehaviorType.FOLLOW_NORMAL, n), pct(mean, BehaviorType.EXPLORE_NEARBY, n),
                            pct(mean, BehaviorType.DEFEND_PLAYER, n), pct(mean, BehaviorType.RETREAT, n),
                            pct(mean, BehaviorType.AVOID_LOCATION, n),
                            100.0 * mean[base] / n, 100.0 * mean[base + 1] / n, 100.0 * mean[base + 2] / n,
                            distance / n));
                }
            }
            md.append('\n');
          }
        }
        md.append("割合はすべて [%]。AVOID(拠点) は拠点の領域、危険地帯 AVOID はパターン C の最初の危険地帯で測った値。\n");
        return md.toString();
    }

    private static double pct(double[] mean, BehaviorType behavior, int n) {
        return 100.0 * mean[behavior.ordinal()] / n;
    }
}
