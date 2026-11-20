package jp.ac.naist.hai.aiwolf.simulation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * 実験前シミュレーション。<strong>Minecraft を起動せずに実行できる。</strong>
 *
 * <p>ユーザ実験を始める前に必ず走らせること。同一の初期状態から出発した仮想個体を
 * 2 群に分け、異なるイベント履歴を与えたあとで同一のテスト文脈に置き、
 * 行動分布が本当に分離するかを確認する。</p>
 *
 * <p>ここで分離しないなら、Minecraft 上でユーザ実験をしても個体差は知覚されない。
 * 重みや学習率の調整はこの段階で行う。</p>
 *
 * <p>実行方法：</p>
 * <pre>
 * ./gradlew compileJava
 * java -cp build/classes/java/main jp.ac.naist.hai.aiwolf.simulation.PreExperimentSimulation
 * </pre>
 */
public final class PreExperimentSimulation {

    /** 1 群あたりの既定の個体数。 */
    private static final int DEFAULT_WOLVES_PER_GROUP = 50;

    /** 育成フェーズで与える既定のイベント数。 */
    private static final int DEFAULT_REARING_EVENTS = 120;

    /** 指紋測定 1 回あたりの既定の決定回数。 */
    private static final int DEFAULT_DECISIONS_PER_SESSION = StandardTestBattery.DEFAULT_DECISIONS;

    /**
     * 分離したと判定するために必要な、個体内距離に対する群間距離の比。
     *
     * <p>2 倍は「群間の差が個体内のばらつきの倍はある」という控えめな基準である。
     * 統計的検定の代わりではなく、パイロットで方向性を確認するための目安。</p>
     */
    private static final double SEPARATION_RATIO_THRESHOLD = 2.0;

    /** 育成イベント 1 件あたりに進むワールド時刻 [ticks]。 */
    private static final long TICKS_PER_EVENT = 600L;

    private static final String DIMENSION = "minecraft:overworld";
    private static final String BIOME = "minecraft:plains";
    private static final String OWNER_ID = "subject-anonymous";


    private PreExperimentSimulation() {
        throw new AssertionError("entry point holder");
    }

    /**
     * シミュレーションの設定。pilot 調整のためコマンドライン引数で上書きできる。
     *
     * @param wolvesPerGroup      1 群あたりの個体数
     * @param rearingEvents       育成フェーズのイベント数
     * @param decisionsPerSession 指紋測定 1 回あたりの決定回数
     * @param temperature         Softmax 温度
     * @param experimentSeed      実験シード
     */
    public record Settings(int wolvesPerGroup,
                           int rearingEvents,
                           int decisionsPerSession,
                           double temperature,
                           long experimentSeed) {

        /** @return 既定設定 */
        public static Settings defaults() {
            return new Settings(DEFAULT_WOLVES_PER_GROUP, DEFAULT_REARING_EVENTS,
                    DEFAULT_DECISIONS_PER_SESSION, AiWolfParameters.SOFTMAX_TEMPERATURE,
                    AiWolfParameters.DEFAULT_EXPERIMENT_SEED);
        }

        /**
         * {@code --key=value} 形式の引数で既定値を上書きする。
         *
         * @param args コマンドライン引数
         * @return 設定
         */
        public static Settings fromArgs(String[] args) {
            Settings settings = defaults();
            for (String arg : args) {
                int separator = arg.indexOf('=');
                if (!arg.startsWith("--") || separator < 0) {
                    throw new IllegalArgumentException("expected --key=value but got: " + arg);
                }
                String key = arg.substring(2, separator);
                String value = arg.substring(separator + 1);
                settings = switch (key) {
                    case "wolves" -> new Settings(Integer.parseInt(value), settings.rearingEvents,
                            settings.decisionsPerSession, settings.temperature, settings.experimentSeed);
                    case "events" -> new Settings(settings.wolvesPerGroup, Integer.parseInt(value),
                            settings.decisionsPerSession, settings.temperature, settings.experimentSeed);
                    case "decisions" -> new Settings(settings.wolvesPerGroup, settings.rearingEvents,
                            Integer.parseInt(value), settings.temperature, settings.experimentSeed);
                    case "temperature" -> new Settings(settings.wolvesPerGroup, settings.rearingEvents,
                            settings.decisionsPerSession, Double.parseDouble(value), settings.experimentSeed);
                    case "seed" -> new Settings(settings.wolvesPerGroup, settings.rearingEvents,
                            settings.decisionsPerSession, settings.temperature, Long.parseLong(value));
                    default -> throw new IllegalArgumentException("unknown option: --" + key);
                };
            }
            return settings;
        }
    }

    /**
     * 1 条件分のシミュレーション結果。
     *
     * @param condition       実験条件
     * @param companionship              一緒に過ごした群の平均行動分布
     * @param solo                       単独探索群の平均行動分布
     * @param companionshipOwnerDistance 一緒に過ごした群の平均 owner distance
     * @param soloOwnerDistance          単独探索群の平均 owner distance
     * @param withinDistance             個体内の平均距離
     * @param betweenDistance            群間の平均距離
     */
    public record Result(ExperimentalCondition condition,
                         Map<BehaviorType, Double> companionship,
                         Map<BehaviorType, Double> solo,
                         double companionshipOwnerDistance,
                         double soloOwnerDistance,
                         double withinDistance,
                         double betweenDistance) {

        /**
         * 個体内距離に対する群間距離の比。1 に近ければ、群間の差はサンプリング誤差と
         * 区別できないことを意味する。
         *
         * @return 比。個体内距離が 0 なら {@link Double#POSITIVE_INFINITY}
         */
        public double separationRatio() {
            return withinDistance <= 0.0 ? Double.POSITIVE_INFINITY : betweenDistance / withinDistance;
        }

        /**
         * 育成履歴によって行動分布が分離したかを返す。
         *
         * <p>単純な {@code between > within} では、両者がほぼ同値のときに
         * 誤差の向きだけで判定が反転してしまう。個体内のばらつきに対して
         * 群間の差が明確に大きいことを要求する。</p>
         *
         * @return 分離していれば true
         */
        public boolean separated() {
            return separationRatio() >= SEPARATION_RATIO_THRESHOLD;
        }
    }

    /** 育成の型。 */
    private enum RearingStyle {
        /** 一緒に過ごし、守られる経験が多い。 */
        COMPANIONSHIP,
        /** 単独で安全に探索し、戦闘にも成功してきた。 */
        SOLO_EXPLORATION
    }

    /**
     * エントリポイント。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        Settings settings = Settings.fromArgs(args);
        System.out.println("=== AI Wolf pre-experiment simulation ===");
        System.out.println("parameter set version : " + AiWolfParameters.PARAMETER_SET_VERSION);
        System.out.println("experiment seed       : " + settings.experimentSeed());
        System.out.println("wolves per group      : " + settings.wolvesPerGroup());
        System.out.println("rearing events        : " + settings.rearingEvents());
        System.out.println("decisions per session : " + settings.decisionsPerSession());
        System.out.println("softmax temperature   : " + settings.temperature());
        System.out.println("\noptions: --wolves=N --events=N --decisions=N --temperature=T --seed=S");

        for (ExperimentalCondition condition : List.of(ExperimentalCondition.ADAPTIVE,
                ExperimentalCondition.STATE_ONLY, ExperimentalCondition.STATIC)) {
            report(run(condition, settings));
        }
    }

    /**
     * 1 条件分のシミュレーションを実行する。テストからも呼べるよう副作用は持たない。
     *
     * @param condition 実験条件
     * @param settings  設定
     * @return 結果
     */
    public static Result run(ExperimentalCondition condition, Settings settings) {
        List<BehaviouralSignature> groupA = new ArrayList<>();
        List<BehaviouralSignature> groupB = new ArrayList<>();
        List<Double> withinDistances = new ArrayList<>();

        for (int i = 0; i < settings.wolvesPerGroup(); i++) {
            WolfAiAgent companionship = rear(newAgent(condition, i, RearingStyle.COMPANIONSHIP, settings),
                    RearingStyle.COMPANIONSHIP, settings);
            WolfAiAgent solo = rear(
                    newAgent(condition, i + settings.wolvesPerGroup(), RearingStyle.SOLO_EXPLORATION, settings),
                    RearingStyle.SOLO_EXPLORATION, settings);

            // 同一個体を 2 セッション測定し、個体内の安定性（H2）も見る。
            BehaviouralSignature companionshipFirst = measure(companionship, settings, 0L);
            BehaviouralSignature companionshipSecond = measure(companionship, settings, 1L);
            withinDistances.add(companionshipFirst.distanceTo(companionshipSecond));

            groupA.add(companionshipFirst);
            groupB.add(measure(solo, settings, 0L));
        }

        return new Result(condition, mean(groupA), mean(groupB),
                meanOwnerDistance(groupA), meanOwnerDistance(groupB),
                withinDistances.stream().mapToDouble(Double::doubleValue).average().orElse(0.0),
                meanDistanceBetween(groupA, groupB));
    }

    private static void report(Result result) {
        System.out.println("\n--- condition: " + result.condition().id() + " ---");
        System.out.printf("%-20s %14s %10s %10s%n", "behaviour", "companionship", "solo", "diff");
        for (BehaviorType behavior : BehaviorType.ALL) {
            double a = result.companionship().getOrDefault(behavior, 0.0);
            double b = result.solo().getOrDefault(behavior, 0.0);
            System.out.printf("%-20s %14.3f %10.3f %+10.3f%n", behavior.id(), a, b, a - b);
        }
        System.out.printf("%-20s %14.2f %10.2f %+10.2f%n", "mean owner distance",
                result.companionshipOwnerDistance(), result.soloOwnerDistance(),
                result.companionshipOwnerDistance() - result.soloOwnerDistance());
        System.out.printf("%nmean within-individual distance : %.4f%n", result.withinDistance());
        System.out.printf("mean between-group distance     : %.4f%n", result.betweenDistance());
        System.out.printf("separation ratio (between/within): %.2f  (>= %.1f で分離とみなす)%n",
                result.separationRatio(), SEPARATION_RATIO_THRESHOLD);

        boolean expectedToSeparate = result.condition().usesLearnedPreference()
                || result.condition().usesPersistentStateUpdate();
        if (result.separated()) {
            System.out.println(expectedToSeparate
                    ? "=> OK: 育成履歴によって行動分布が分離し、個体内では安定している"
                    : "=> WARNING: 対照条件なのに分離した。条件の実装を確認すること");
        } else {
            System.out.println(expectedToSeparate
                    ? "=> WARNING: 適応条件なのに分離していない。重み・学習率の調整が必要"
                    : "=> OK: 対照条件では履歴による分離が起きない（期待どおり）");
        }
    }

    private static WolfAiAgent newAgent(ExperimentalCondition condition,
                                        int index,
                                        RearingStyle style,
                                        Settings settings) {
        // 個体 UUID は決定論的に生成し、同じシミュレーションを何度でも再現できるようにする。
        UUID uuid = new UUID(0x4000L | (long) style.ordinal() << 32, index);
        return new WolfAiAgent(uuid.toString(), condition,
                jp.ac.naist.hai.aiwolf.random.WolfRandomProvider.restore(
                        AiWolfParameters.deriveIndividualSeed(settings.experimentSeed(), uuid), 0L),
                new jp.ac.naist.hai.aiwolf.behavior.BehaviorScorer(
                        jp.ac.naist.hai.aiwolf.behavior.BehaviorWeights.createDefault()),
                new jp.ac.naist.hai.aiwolf.behavior.PolicySelector(settings.temperature()));
    }

    private static WolfAiAgent rear(WolfAiAgent agent, RearingStyle style, Settings settings) {
        long worldTime = 0L;
        for (int i = 0; i < settings.rearingEvents(); i++) {
            worldTime += TICKS_PER_EVENT;
            for (WolfEventType type : eventsFor(style, i)) {
                agent.onEvent(WolfEvent.of(type, worldTime, DIMENSION, BIOME,
                        locationFor(style, i), 64.0, locationFor(style, i),
                        OWNER_ID, 1.0, 1.0));
            }
            // 実機と同じ入口を使う。MAINTENANCE_INTERVAL_TICKS は TICKS_PER_EVENT と
            // 同じ値なので 1 イベント 1 回となり、以前の結果とビット単位で一致する。
            agent.advanceTime(TICKS_PER_EVENT);
        }
        return agent;
    }

    private static double locationFor(RearingStyle style, int index) {
        // COMPANIONSHIP は飼い主の拠点周辺、SOLO は広い範囲を動き回る想定。
        return style == RearingStyle.COMPANIONSHIP ? (index % 3) * 10.0 : (index % 40) * 25.0;
    }

    private static List<WolfEventType> eventsFor(RearingStyle style, int index) {
        if (style == RearingStyle.COMPANIONSHIP) {
            List<WolfEventType> events = new ArrayList<>(List.of(WolfEventType.PET));
            if (index % 3 == 0) {
                events.add(WolfEventType.FEED);
            }
            if (index % 5 == 0) {
                events.add(WolfEventType.PLAYER_PROTECTED_WOLF);
            }
            if (index % 11 == 0) {
                events.add(WolfEventType.SLEEP_NEAR_PLAYER);
            }
            return events;
        }
        List<WolfEventType> events = new ArrayList<>(List.of(WolfEventType.SAFE_SOLO_EXPLORATION));
        if (index % 4 == 0) {
            events.add(WolfEventType.DEFEND_SUCCESS);
        }
        if (index % 7 == 0) {
            events.add(WolfEventType.NEW_BIOME);
        }
        if (index % 13 == 0) {
            events.add(WolfEventType.MONSTER_ENCOUNTER);
        }
        return events;
    }

    /**
     * 標準テストバッテリで指紋を測る。
     *
     * <p>実機の {@code /wolfai signature} とまったく同じ手続きを使う。
     * シミュレーションと実測を直接比較できるようにするためである。</p>
     */
    private static BehaviouralSignature measure(WolfAiAgent agent, Settings settings, long sessionIndex) {
        SpatialRegion region = SpatialRegion.of(DIMENSION, 0.0, 64.0, 0.0);
        return StandardTestBattery.measure(agent, region, OWNER_ID,
                (long) settings.rearingEvents() * TICKS_PER_EVENT, sessionIndex,
                settings.decisionsPerSession(), settings.temperature());
    }

    private static Map<BehaviorType, Double> mean(List<BehaviouralSignature> signatures) {
        Map<BehaviorType, Double> means = new EnumMap<>(BehaviorType.class);
        for (BehaviorType behavior : BehaviorType.ALL) {
            double sum = 0.0;
            for (BehaviouralSignature signature : signatures) {
                sum += signature.proportionOf(behavior);
            }
            means.put(behavior, signatures.isEmpty() ? 0.0 : sum / signatures.size());
        }
        return means;
    }

    private static double meanOwnerDistance(List<BehaviouralSignature> signatures) {
        return signatures.stream().mapToDouble(BehaviouralSignature::meanOwnerDistance).average().orElse(0.0);
    }

    private static double meanDistanceBetween(List<BehaviouralSignature> groupA,
                                              List<BehaviouralSignature> groupB) {
        double sum = 0.0;
        int pairs = 0;
        for (BehaviouralSignature a : groupA) {
            for (BehaviouralSignature b : groupB) {
                sum += a.distanceTo(b);
                pairs++;
            }
        }
        return pairs == 0 ? 0.0 : sum / pairs;
    }
}
