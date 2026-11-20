package jp.ac.naist.hai.aiwolf.simulation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.state.StateParameter;

/**
 * Phase 15：長期育成での内部状態の天井効果の測定。<strong>Minecraft に依存しない。</strong>
 *
 * <p><strong>研究パラメータは一切変えない。</strong>現在の {@code WolfAiAgent} に、
 * 4 種類の育成パターンの出来事を最大 120 分ぶん与え、内部状態の時間変化を 1 秒刻みで追う。</p>
 *
 * <h2>出来事の生成（仮定）</h2>
 * <p>各出来事の種類について「1 分あたりの平均回数（rate）」を決め、10 秒ごとの枠で
 * 確率 {@code rate / 6} で起こす（rate ≤ 6。撫でのクールダウン 10 秒と同じ刻み）。
 * 枠の中の時刻は一様乱数。乱数はシードから決まり、同じシードなら同じ出来事列になる。
 * rate は実機の発火条件（{@code docs/DEMO_RECORDING.md}）から見て妥当な範囲を仮定したもので、
 * 実測値ではない。強さは TYPICAL（ふつうに遊んだ場合の想定）と INTENSIVE
 * （デモ撮影のように出来事を集中して起こした場合。Demo D の育成と同程度）の 2 段階。</p>
 *
 * <h2>時間の進め方</h2>
 * <p>1 秒（20 ticks）ごとに、その秒の出来事を {@link WolfAiAgent#onEvent} で与え、
 * {@link WolfAiAgent#advanceTime} で時間を進める（感情のベースライン回帰は実機と同じ規則）。
 * 2 秒（40 ticks）ごとに {@link WolfAiAgent#decide} も呼ぶ。決定は内部状態を直接は変えないが、
 * 現在行動によって energy の増減が変わるため（{@code StateUpdater.applyEnergyDynamics}）、
 * 実機に近づけるために行う。文脈はパターンごとに固定の規則で作る。</p>
 *
 * <p>実行：{@code ./gradlew saturation}（出力先は {@code research-output/state-saturation/}）。</p>
 */
public final class StateSaturationSimulation {

    /** 記録する時間の長さ [分]。 */
    public static final int DEFAULT_MINUTES = 120;

    /** 既定のシード数。 */
    public static final int DEFAULT_SEEDS = 30;

    /** 既定の基準シード。シード i は {@code BASE_SEED + i}。 */
    public static final long BASE_SEED = 20_261_114L;

    /** レポートに載せる時点 [分]。 */
    public static final List<Integer> CHECKPOINTS = List.of(0, 10, 20, 30, 45, 60, 90, 120);

    /** 上限側の閾値。 */
    public static final double[] CEILING_THRESHOLDS = {0.90, 0.95, 0.99};

    /** 「上限に張り付いている」とみなす値。 */
    public static final double CEILING = 0.99;

    /** 「下限に張り付いている」とみなす値。 */
    public static final double FLOOR = 0.01;

    private static final int TICKS_PER_SECOND = 20;
    private static final int TICKS_PER_MINUTE = 1_200;
    private static final int SLOT_TICKS = 200;
    private static final int SLOTS_PER_MINUTE = TICKS_PER_MINUTE / SLOT_TICKS;
    private static final String DIMENSION = "minecraft:overworld";
    private static final String BIOME = "minecraft:plains";
    private static final String OWNER_ID = "subject-saturation";

    /** 育成パターン。 */
    public enum Pattern {
        /** A：飼い主中心（撫で・餌・救援・そばで過ごす）。 */
        OWNER_ORIENTED("A_owner"),
        /** B：探索中心（安全な単独探索・新バイオーム・飼い主から離れる）。 */
        EXPLORATION_ORIENTED("B_exploration"),
        /** C：危険が多い（敵との遭遇・被弾・体力低下・救援）。 */
        DANGER_HEAVY("C_danger"),
        /** D：混合（どれにも極端に偏らない）。 */
        MIXED("D_mixed");

        private final String id;

        Pattern(String id) {
            this.id = id;
        }

        /** @return CSV に書く ID */
        public String id() {
            return id;
        }
    }

    /** 出来事の起こる頻度の強さ。 */
    public enum Intensity {
        /** ふつうに遊んだ場合の想定。 */
        TYPICAL,
        /** デモ撮影のように集中して起こした場合（Demo D の育成と同程度）。 */
        INTENSIVE
    }

    /**
     * 育成パターンの出来事の頻度 [回/分]。
     *
     * @param pattern   パターン
     * @param intensity 強さ
     * @return 出来事 → 平均回数/分（すべて 6 以下）
     */
    public static Map<WolfEventType, Double> rates(Pattern pattern, Intensity intensity) {
        Map<WolfEventType, Double> rates = new EnumMap<>(WolfEventType.class);
        boolean intensive = intensity == Intensity.INTENSIVE;
        switch (pattern) {
            case OWNER_ORIENTED -> {
                rates.put(WolfEventType.PET, intensive ? 6.0 : 2.0);
                rates.put(WolfEventType.FEED, intensive ? 1.0 : 0.3);
                rates.put(WolfEventType.PLAYER_PROTECTED_WOLF, intensive ? 0.33 : 0.1);
                rates.put(WolfEventType.SLEEP_NEAR_PLAYER, intensive ? 0.05 : 0.02);
            }
            case EXPLORATION_ORIENTED -> {
                rates.put(WolfEventType.SAFE_SOLO_EXPLORATION, intensive ? 6.0 : 2.0);
                rates.put(WolfEventType.NEW_BIOME, intensive ? 0.33 : 0.1);
                rates.put(WolfEventType.MONSTER_ENCOUNTER, intensive ? 0.2 : 0.1);
            }
            case DANGER_HEAVY -> {
                double scale = intensive ? 2.0 : 1.0;
                rates.put(WolfEventType.MONSTER_ENCOUNTER, 0.5 * scale);
                rates.put(WolfEventType.WOLF_DAMAGED, 1.0 * scale);
                rates.put(WolfEventType.WOLF_LOW_HEALTH, 0.2 * scale);
                rates.put(WolfEventType.NEAR_DEATH, 0.03 * scale);
                rates.put(WolfEventType.PLAYER_PROTECTED_WOLF, 0.2 * scale);
                rates.put(WolfEventType.DEFEND_SUCCESS, 0.2 * scale);
                rates.put(WolfEventType.PLAYER_DAMAGED_NEAR_WOLF, 0.2 * scale);
                rates.put(WolfEventType.PLAYER_AND_WOLF_COMBAT, 0.1 * scale);
            }
            case MIXED -> {
                double scale = intensive ? 2.0 : 1.0;
                rates.put(WolfEventType.PET, 1.0 * scale);
                rates.put(WolfEventType.FEED, 0.15 * scale);
                rates.put(WolfEventType.PLAYER_PROTECTED_WOLF, 0.05 * scale);
                rates.put(WolfEventType.SAFE_SOLO_EXPLORATION, 0.8 * scale);
                rates.put(WolfEventType.NEW_BIOME, 0.05 * scale);
                rates.put(WolfEventType.MONSTER_ENCOUNTER, 0.2 * scale);
                rates.put(WolfEventType.WOLF_DAMAGED, 0.2 * scale);
                rates.put(WolfEventType.DEFEND_SUCCESS, 0.05 * scale);
                rates.put(WolfEventType.PLAYER_AND_WOLF_COMBAT, 0.05 * scale);
                rates.put(WolfEventType.SLEEP_NEAR_PLAYER, 0.02 * scale);
            }
        }
        return rates;
    }

    /**
     * 1 回の育成の結果。
     *
     * @param pattern       パターン
     * @param intensity     強さ
     * @param seed          シード
     * @param minuteStates  minuteStates[m][p] = m 分時点の状態 p（m = 0..minutes）
     * @param minutePreferenceMaxAbs 各分の |選好| の最大値
     * @param minuteMemoryCount      各分のエピソード記憶の件数
     * @param firstReach    firstReach[p][k] = 状態 p が CEILING_THRESHOLDS[k] 以上に初めてなった時刻 [秒]。なければ -1
     * @param firstFloor    状態 p が FLOOR 以下に初めてなった時刻 [秒]。なければ -1
     * @param secondsAtCeiling 状態 p が CEILING 以上だった秒数
     * @param secondsAtFloor   状態 p が FLOOR 以下だった秒数
     * @param totalSeconds  記録した秒数
     * @param eventCounts   出来事の種類ごとの回数
     */
    public record Run(Pattern pattern, Intensity intensity, long seed,
                      double[][] minuteStates, double[] minutePreferenceMaxAbs, int[] minuteMemoryCount,
                      long[][] firstReach, long[] firstFloor,
                      long[] secondsAtCeiling, long[] secondsAtFloor, long totalSeconds,
                      Map<WolfEventType, Integer> eventCounts) {
    }

    private StateSaturationSimulation() {
        throw new AssertionError("entry point holder");
    }

    /**
     * 出来事列を作る。同じシードなら同じ列。
     *
     * @param pattern   パターン
     * @param intensity 強さ
     * @param seed      シード
     * @param minutes   長さ [分]
     * @return 時刻順の (tick, 出来事) の列
     */
    public static List<Map.Entry<Long, WolfEventType>> schedule(Pattern pattern, Intensity intensity,
                                                               long seed, int minutes) {
        SplittableRandom random = new SplittableRandom(seed * 31L + pattern.ordinal() * 7L + intensity.ordinal());
        Map<WolfEventType, Double> rates = rates(pattern, intensity);
        List<Map.Entry<Long, WolfEventType>> events = new ArrayList<>();
        int slots = minutes * SLOTS_PER_MINUTE;
        for (int slot = 0; slot < slots; slot++) {
            for (Map.Entry<WolfEventType, Double> rate : rates.entrySet()) {
                if (random.nextDouble() < rate.getValue() / SLOTS_PER_MINUTE) {
                    long tick = (long) slot * SLOT_TICKS + random.nextInt(SLOT_TICKS);
                    events.add(Map.entry(tick, rate.getKey()));
                }
            }
        }
        events.sort(Map.Entry.comparingByKey());
        return events;
    }

    /**
     * 1 個体を育成して記録する。同じ引数なら同じ結果（決定論的）。
     *
     * @param pattern   パターン
     * @param intensity 強さ
     * @param seed      シード（出来事列と個体の乱数の両方に使う）
     * @param minutes   長さ [分]
     * @return 結果
     */
    public static Run run(Pattern pattern, Intensity intensity, long seed, int minutes) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(intensity, "intensity");
        int parameters = StateParameter.values().length;
        double[][] minuteStates = new double[minutes + 1][parameters];
        double[] minutePreference = new double[minutes + 1];
        int[] minuteMemories = new int[minutes + 1];
        long[][] firstReach = new long[parameters][CEILING_THRESHOLDS.length];
        long[] firstFloor = new long[parameters];
        long[] atCeiling = new long[parameters];
        long[] atFloor = new long[parameters];
        for (long[] row : firstReach) {
            java.util.Arrays.fill(row, -1L);
        }
        java.util.Arrays.fill(firstFloor, -1L);
        Map<WolfEventType, Integer> counts = new EnumMap<>(WolfEventType.class);

        rear(pattern, intensity, seed, minutes, ExperimentalCondition.ADAPTIVE, counts, (agent, elapsedSeconds) -> {
            if (elapsedSeconds == 0L) {
                record(agent, minuteStates[0], minutePreference, minuteMemories, 0);
                return;
            }
            // 1 秒の終わりの値で判定する（この 1 秒間その値だったとみなす）。
            for (StateParameter parameter : StateParameter.values()) {
                int p = parameter.ordinal();
                double value = agent.state().get(parameter);
                for (int k = 0; k < CEILING_THRESHOLDS.length; k++) {
                    if (firstReach[p][k] < 0 && value >= CEILING_THRESHOLDS[k]) {
                        firstReach[p][k] = elapsedSeconds;
                    }
                }
                if (firstFloor[p] < 0 && value <= FLOOR) {
                    firstFloor[p] = elapsedSeconds;
                }
                if (value >= CEILING) {
                    atCeiling[p]++;
                }
                if (value <= FLOOR) {
                    atFloor[p]++;
                }
            }
            if (elapsedSeconds % 60 == 0) {
                int minute = (int) (elapsedSeconds / 60);
                record(agent, minuteStates[minute], minutePreference, minuteMemories, minute);
            }
        });
        return new Run(pattern, intensity, seed, minuteStates, minutePreference, minuteMemories,
                firstReach, firstFloor, atCeiling, atFloor, (long) minutes * 60L, counts);
    }

    /** 育成の 1 秒ごとに呼ばれる観測。 */
    @FunctionalInterface
    public interface SecondObserver {
        /**
         * @param agent          育成中の個体（観測は変更してはならない）
         * @param elapsedSeconds 経過秒数。育成開始前に 0 で 1 回呼ばれる
         */
        void afterSecond(WolfAiAgent agent, long elapsedSeconds);
    }

    /**
     * 1 個体を育成する。天井効果の測定（{@link #run}）と個体性評価（{@code IndividualityEvaluation}）の
     * 共通の入口。同じ引数なら同じ個体になる（決定論的）。
     *
     * <p>個体 ID と個体の乱数はパターンとシードだけで決まり、<strong>実験条件には依存しない</strong>。
     * したがって条件だけを変えた比較では、出来事列も乱数列も同一になる。</p>
     *
     * @param pattern   パターン
     * @param intensity 強さ
     * @param seed      シード（出来事列と個体の乱数の両方に使う）
     * @param minutes   長さ [分]
     * @param condition 実験条件
     * @param counts    出来事の種類ごとの回数を書き込むマップ
     * @param observer  1 秒ごとの観測（{@code null} 可）
     * @return 育成後の個体
     */
    public static WolfAiAgent rear(Pattern pattern, Intensity intensity, long seed, int minutes,
                                   ExperimentalCondition condition, Map<WolfEventType, Integer> counts,
                                   SecondObserver observer) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(intensity, "intensity");
        Objects.requireNonNull(condition, "condition");
        UUID uuid = new UUID(0x5A7L << 32 | pattern.ordinal(), seed);
        WolfAiAgent agent = new WolfAiAgent(uuid.toString(), condition, seed);
        agent.setOwnerResearchId(OWNER_ID);

        List<Map.Entry<Long, WolfEventType>> events = schedule(pattern, intensity, seed, minutes);
        if (observer != null) {
            observer.afterSecond(agent, 0L);
        }
        int next = 0;
        long totalSeconds = (long) minutes * 60L;
        for (long second = 0; second < totalSeconds; second++) {
            long secondStart = second * TICKS_PER_SECOND;
            long secondEnd = secondStart + TICKS_PER_SECOND;
            while (next < events.size() && events.get(next).getKey() < secondEnd) {
                Map.Entry<Long, WolfEventType> event = events.get(next++);
                SpatialRegion where = locationFor(pattern, event.getKey());
                double size = AiWolfParameters.SPATIAL_REGION_SIZE;
                agent.onEvent(WolfEvent.of(event.getValue(), event.getKey(), DIMENSION, BIOME,
                        where.regionX() * size + 1.0, where.regionY() * size + 1.0, where.regionZ() * size + 1.0,
                        OWNER_ID, healthFor(event.getValue()), 1.0));
                counts.merge(event.getValue(), 1, Integer::sum);
            }
            agent.advanceTime(TICKS_PER_SECOND);
            if (secondEnd % AiWolfParameters.DECISION_INTERVAL_TICKS == 0) {
                SpatialRegion here = locationFor(pattern, secondEnd);
                agent.decide(contextFor(pattern, secondEnd, agent.regionFear(here)), here, OWNER_ID, secondEnd);
            }
            if (observer != null) {
                observer.afterSecond(agent, second + 1);
            }
        }
        return agent;
    }

    /** @return 育成で使う飼い主の匿名 ID（評価で記憶を検索するときに同じ ID を使う） */
    public static String ownerId() {
        return OWNER_ID;
    }

    /**
     * 出来事の起きた場所（パターンごと）。危険の多い育成の最初の危険地帯は
     * {@code locationFor(DANGER_HEAVY, 0)}。
     *
     * @param pattern パターン
     * @param tick    時刻
     * @return 領域
     */
    public static SpatialRegion regionOf(Pattern pattern, long tick) {
        return locationFor(pattern, tick);
    }

    private static void record(WolfAiAgent agent, double[] states, double[] preference, int[] memories, int minute) {
        for (StateParameter parameter : StateParameter.values()) {
            states[parameter.ordinal()] = agent.state().get(parameter);
        }
        double maxAbs = 0.0;
        for (BehaviorType behavior : BehaviorType.ALL) {
            maxAbs = Math.max(maxAbs, Math.abs(agent.preferences().get(behavior)));
        }
        preference[minute] = maxAbs;
        memories[minute] = agent.memoryStore().size();
    }

    /** 体力の比率。被弾系の出来事だけ実際の種別に合う値にする（記憶の内容に入るだけで、更新量には影響しない）。 */
    private static double healthFor(WolfEventType type) {
        return switch (type) {
            case NEAR_DEATH -> 0.1;
            case WOLF_LOW_HEALTH -> 0.3;
            case WOLF_DAMAGED, WOLF_DAMAGED_DURING_EXPLORATION -> 0.7;
            default -> 1.0;
        };
    }

    /** 出来事の起きる場所。A は拠点、B は 1 分ごとに移動、C は 3 か所の危険地帯、D は拠点と周辺。 */
    private static SpatialRegion locationFor(Pattern pattern, long tick) {
        int minute = (int) (tick / TICKS_PER_MINUTE);
        int size = AiWolfParameters.SPATIAL_REGION_SIZE;
        return switch (pattern) {
            case OWNER_ORIENTED -> SpatialRegion.of(DIMENSION, 0.0, 64.0, 0.0);
            case EXPLORATION_ORIENTED -> SpatialRegion.of(DIMENSION, 200.0 + minute * size, 64.0, 0.0);
            case DANGER_HEAVY -> SpatialRegion.of(DIMENSION, 400.0 + (minute % 3) * size, 30.0, 0.0);
            case MIXED -> SpatialRegion.of(DIMENSION, (minute % 5) * size, 64.0, 0.0);
        };
    }

    /** 決定時の文脈。パターンごとの典型的な状況を固定の規則で作る。 */
    private static WolfContextSnapshot contextFor(Pattern pattern, long tick, double regionFear) {
        int step = (int) (tick / AiWolfParameters.DECISION_INTERVAL_TICKS);
        WolfContextSnapshot.Builder builder = WolfContextSnapshot.builder().regionFear(regionFear);
        return switch (pattern) {
            case OWNER_ORIENTED -> builder.ownerDistance(3.0).ownerMoving(step % 2 == 0).build();
            case EXPLORATION_ORIENTED -> builder.ownerDistance(20.0).ownerMoving(step % 3 == 0).build();
            case DANGER_HEAVY -> {
                boolean threat = (step / 5) % 2 == 0;
                yield builder.ownerDistance(6.0).ownerMoving(false).underground(true)
                        .hostileMobCount(threat ? 1 : 0)
                        .nearestHostileDistance(threat ? 7.0 : WolfContextSnapshot.NO_TARGET_DISTANCE)
                        .build();
            }
            case MIXED -> {
                boolean threat = step % 15 == 0;
                yield builder.ownerDistance(step % 4 == 0 ? 15.0 : 5.0).ownerMoving(step % 2 == 0)
                        .hostileMobCount(threat ? 1 : 0)
                        .nearestHostileDistance(threat ? 8.0 : WolfContextSnapshot.NO_TARGET_DISTANCE)
                        .build();
            }
        };
    }

    // ==================================================================
    // 実行と出力
    // ==================================================================

    /**
     * すべてのパターン・強さ・シードを実行し、CSV とレポートを書き出す。
     *
     * <p>引数：{@code --seeds=N}（既定 30）、{@code --minutes=M}（既定 120）、
     * {@code --out=DIR}（既定 research-output/state-saturation）。</p>
     *
     * @param args 引数
     */
    public static void main(String[] args) {
        int seeds = DEFAULT_SEEDS;
        int minutes = DEFAULT_MINUTES;
        Path out = Path.of("research-output", "state-saturation");
        for (String arg : args) {
            if (arg.startsWith("--seeds=")) {
                seeds = Integer.parseInt(arg.substring("--seeds=".length()));
            } else if (arg.startsWith("--minutes=")) {
                minutes = Integer.parseInt(arg.substring("--minutes=".length()));
            } else if (arg.startsWith("--out=")) {
                out = Path.of(arg.substring("--out=".length()));
            } else {
                throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        List<Run> runs = new ArrayList<>();
        for (Pattern pattern : Pattern.values()) {
            for (Intensity intensity : Intensity.values()) {
                for (int i = 0; i < seeds; i++) {
                    runs.add(run(pattern, intensity, BASE_SEED + i, minutes));
                }
            }
        }
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("state_trajectory.csv"), trajectoryCsv(runs), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("state_trajectory_mean.csv"), meanTrajectoryCsv(runs, minutes),
                    StandardCharsets.UTF_8);
            Files.writeString(out.resolve("saturation_summary.csv"), summaryCsv(runs), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("event_rates.csv"), ratesCsv(), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("saturation_report.md"), report(runs, seeds, minutes),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        System.out.println("wrote " + runs.size() + " runs to " + out.toAbsolutePath());
    }

    private static String header() {
        return "parameterSetVersion,pattern,intensity,seed";
    }

    private static String prefix(Run run) {
        return AiWolfParameters.PARAMETER_SET_VERSION + "," + run.pattern().id() + ","
                + run.intensity().name().toLowerCase(Locale.ROOT) + "," + run.seed();
    }

    static String trajectoryCsv(List<Run> runs) {
        StringBuilder csv = new StringBuilder(header()).append(",minute");
        for (StateParameter parameter : StateParameter.values()) {
            csv.append(',').append(parameter.key());
        }
        csv.append(",preferenceMaxAbs,episodicMemoryCount\n");
        for (Run run : runs) {
            for (int minute = 0; minute < run.minuteStates().length; minute++) {
                csv.append(prefix(run)).append(',').append(minute);
                for (double value : run.minuteStates()[minute]) {
                    csv.append(String.format(Locale.ROOT, ",%.5f", value));
                }
                csv.append(String.format(Locale.ROOT, ",%.5f,%d%n",
                        run.minutePreferenceMaxAbs()[minute], run.minuteMemoryCount()[minute]));
            }
        }
        return csv.toString();
    }

    private static String meanTrajectoryCsv(List<Run> runs, int minutes) {
        StringBuilder csv = new StringBuilder("parameterSetVersion,pattern,intensity,minute,state,n,mean,sd,min,max\n");
        for (Pattern pattern : Pattern.values()) {
            for (Intensity intensity : Intensity.values()) {
                List<Run> group = group(runs, pattern, intensity);
                for (int minute = 0; minute <= minutes; minute++) {
                    for (StateParameter parameter : StateParameter.values()) {
                        double[] values = new double[group.size()];
                        for (int i = 0; i < group.size(); i++) {
                            values[i] = group.get(i).minuteStates()[minute][parameter.ordinal()];
                        }
                        Stats stats = Stats.of(values);
                        csv.append(String.format(Locale.ROOT, "%d,%s,%s,%d,%s,%d,%.5f,%.5f,%.5f,%.5f%n",
                                AiWolfParameters.PARAMETER_SET_VERSION, pattern.id(),
                                intensity.name().toLowerCase(Locale.ROOT), minute, parameter.key(),
                                values.length, stats.mean(), stats.sd(), stats.min(), stats.max()));
                    }
                }
            }
        }
        return csv.toString();
    }

    private static String summaryCsv(List<Run> runs) {
        StringBuilder csv = new StringBuilder(header()).append(",state");
        for (double threshold : CEILING_THRESHOLDS) {
            csv.append(String.format(Locale.ROOT, ",timeTo%.2fMin", threshold));
        }
        csv.append(",timeToFloor0.01Min,fractionAtCeiling0.99,fractionAtFloor0.01,finalValue\n");
        for (Run run : runs) {
            for (StateParameter parameter : StateParameter.values()) {
                int p = parameter.ordinal();
                csv.append(prefix(run)).append(',').append(parameter.key());
                for (long reach : run.firstReach()[p]) {
                    // 4 桁：判定は 1 秒刻みなので、分に直しても丸めで中央値が変わらない精度にする。
                    csv.append(reach < 0 ? "," : String.format(Locale.ROOT, ",%.4f", reach / 60.0));
                }
                csv.append(run.firstFloor()[p] < 0 ? "," : String.format(Locale.ROOT, ",%.4f", run.firstFloor()[p] / 60.0));
                csv.append(String.format(Locale.ROOT, ",%.4f,%.4f,%.5f%n",
                        run.secondsAtCeiling()[p] / (double) run.totalSeconds(),
                        run.secondsAtFloor()[p] / (double) run.totalSeconds(),
                        run.minuteStates()[run.minuteStates().length - 1][p]));
            }
        }
        return csv.toString();
    }

    private static String ratesCsv() {
        StringBuilder csv = new StringBuilder("pattern,intensity,event,ratePerMinute\n");
        for (Pattern pattern : Pattern.values()) {
            for (Intensity intensity : Intensity.values()) {
                for (Map.Entry<WolfEventType, Double> rate : rates(pattern, intensity).entrySet()) {
                    csv.append(String.format(Locale.ROOT, "%s,%s,%s,%.3f%n", pattern.id(),
                            intensity.name().toLowerCase(Locale.ROOT), rate.getKey().id(), rate.getValue()));
                }
            }
        }
        return csv.toString();
    }

    private static List<Run> group(List<Run> runs, Pattern pattern, Intensity intensity) {
        List<Run> group = new ArrayList<>();
        for (Run run : runs) {
            if (run.pattern() == pattern && run.intensity() == intensity) {
                group.add(run);
            }
        }
        return group;
    }

    /** 平均・標準偏差（n − 1）・最小・最大。 */
    record Stats(double mean, double sd, double min, double max) {
        static Stats of(double[] values) {
            if (values.length == 0) {
                return new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            double sum = 0.0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double value : values) {
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            double mean = sum / values.length;
            double squares = 0.0;
            for (double value : values) {
                squares += (value - mean) * (value - mean);
            }
            double sd = values.length > 1 ? Math.sqrt(squares / (values.length - 1)) : 0.0;
            return new Stats(mean, sd, min, max);
        }
    }

    /** 到達時刻の要約：到達した個体の中央値 [分] と、到達した個体数。 */
    private static String reachText(List<Run> group, int parameter, int thresholdIndex) {
        // 初期値の時点ですでに閾値を満たしている状態（energy の初期値 1.0 など）は、
        // 「0 分で到達」と書くと飽和したように読めてしまうので区別する。
        double initial = group.isEmpty() ? Double.NaN : group.get(0).minuteStates()[0][parameter];
        boolean initiallyMet = thresholdIndex < 0 ? initial <= FLOOR : initial >= CEILING_THRESHOLDS[thresholdIndex];
        if (initiallyMet) {
            return "初期値で該当";
        }
        List<Double> reached = new ArrayList<>();
        for (Run run : group) {
            long seconds = thresholdIndex < 0 ? run.firstFloor()[parameter] : run.firstReach()[parameter][thresholdIndex];
            if (seconds >= 0) {
                reached.add(seconds / 60.0);
            }
        }
        if (reached.isEmpty()) {
            return "— (0/" + group.size() + ")";
        }
        reached.sort(Double::compare);
        double median = reached.size() % 2 == 1 ? reached.get(reached.size() / 2)
                : (reached.get(reached.size() / 2 - 1) + reached.get(reached.size() / 2)) / 2.0;
        return String.format(Locale.ROOT, "%.1f (%d/%d)", median, reached.size(), group.size());
    }

    static String report(List<Run> runs, int seeds, int minutes) {
        StringBuilder md = new StringBuilder();
        md.append("# State saturation report\n\n");
        md.append(String.format(Locale.ROOT,
                "PARAMETER_SET_VERSION=%d, condition=ADAPTIVE, seeds=%d (%d..%d), minutes=%d, 1 秒刻みで判定%n%n",
                AiWolfParameters.PARAMETER_SET_VERSION, seeds, BASE_SEED, BASE_SEED + seeds - 1, minutes));
        md.append("到達時刻は「初めて閾値以上になった時刻」の**中央値 [分]**と、到達したシード数（到達数/全数）。"
                + "到達しなかったシードは中央値に含めない。張り付き割合は全育成時間のうち値が 0.99 以上（下限は 0.01 以下）"
                + "だった時間の割合の**平均 [%]**（n = シード数）。\n\n");

        for (Pattern pattern : Pattern.values()) {
            for (Intensity intensity : Intensity.values()) {
                List<Run> group = group(runs, pattern, intensity);
                md.append("## ").append(pattern.id()).append(" / ").append(intensity.name().toLowerCase(Locale.ROOT))
                        .append("\n\n");
                md.append("出来事の頻度 [回/分]：");
                List<String> rateTexts = new ArrayList<>();
                for (Map.Entry<WolfEventType, Double> rate : rates(pattern, intensity).entrySet()) {
                    rateTexts.add(String.format(Locale.ROOT, "%s %.2f", rate.getKey().id(), rate.getValue()));
                }
                md.append(String.join(", ", rateTexts)).append("\n\n");

                md.append("### 時点ごとの平均値（± 標準偏差）\n\n| 状態 |");
                StringBuilder rule = new StringBuilder("|---|");
                for (int checkpoint : CHECKPOINTS) {
                    if (checkpoint <= minutes) {
                        md.append(' ').append(checkpoint).append(" 分 |");
                        rule.append("---:|");
                    }
                }
                md.append('\n').append(rule).append('\n');
                for (StateParameter parameter : StateParameter.values()) {
                    md.append("| ").append(parameter.key()).append(" |");
                    for (int checkpoint : CHECKPOINTS) {
                        if (checkpoint > minutes) {
                            continue;
                        }
                        double[] values = new double[group.size()];
                        for (int i = 0; i < group.size(); i++) {
                            values[i] = group.get(i).minuteStates()[checkpoint][parameter.ordinal()];
                        }
                        Stats stats = Stats.of(values);
                        md.append(String.format(Locale.ROOT, " %.3f ± %.3f |", stats.mean(), stats.sd()));
                    }
                    md.append('\n');
                }

                md.append("\n### 飽和の指標\n\n");
                md.append("| 状態 | 0.90 到達 [分] | 0.95 到達 [分] | 0.99 到達 [分] | ≥0.99 の時間 [%] | 0.01 以下到達 [分] | ≤0.01 の時間 [%] |\n");
                md.append("|---|---:|---:|---:|---:|---:|---:|\n");
                for (StateParameter parameter : StateParameter.values()) {
                    int p = parameter.ordinal();
                    double[] ceiling = new double[group.size()];
                    double[] floor = new double[group.size()];
                    for (int i = 0; i < group.size(); i++) {
                        ceiling[i] = 100.0 * group.get(i).secondsAtCeiling()[p] / group.get(i).totalSeconds();
                        floor[i] = 100.0 * group.get(i).secondsAtFloor()[p] / group.get(i).totalSeconds();
                    }
                    md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %.1f | %s | %.1f |%n",
                            parameter.key(), reachText(group, p, 0), reachText(group, p, 1), reachText(group, p, 2),
                            Stats.of(ceiling).mean(), reachText(group, p, -1), Stats.of(floor).mean()));
                }

                Map<String, Double> eventsPerRun = new LinkedHashMap<>();
                for (Run run : group) {
                    run.eventCounts().forEach((type, count) -> eventsPerRun.merge(type.id(), count / (double) group.size(), Double::sum));
                }
                md.append("\n出来事の平均回数（").append(minutes).append(" 分間）：");
                List<String> counts = new ArrayList<>();
                eventsPerRun.forEach((type, count) -> counts.add(String.format(Locale.ROOT, "%s %.1f", type, count)));
                md.append(String.join(", ", counts)).append("\n\n");
            }
        }
        return md.toString();
    }
}
