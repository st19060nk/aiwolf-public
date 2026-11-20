package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorEligibility;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScore;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScorer;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorWeights;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.MemoryImpactCalculator;
import jp.ac.naist.hai.aiwolf.memory.MemoryQuery;
import jp.ac.naist.hai.aiwolf.memory.MemoryRetriever;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.simulation.BehaviouralSignature;
import jp.ac.naist.hai.aiwolf.simulation.StandardTestBattery;
import jp.ac.naist.hai.aiwolf.state.StateParameter;

/**
 * デモ撮影の見積もり：研究パラメータのまま、デモで与える経験がどれだけ行動確率を変えるかを測る。
 *
 * <p>実験ノート {@code docs/lab-notes/2026-11-14_demo-effect-size.md} の再現用。
 * Minecraft を起動せずに {@code ./gradlew demoEstimate} で実行できる。</p>
 *
 * <h2>測り方</h2>
 * <ul>
 *   <li>出来事は {@link WolfAiAgent#onEvent} へ直接与える（実機の検出器は通さない）。
 *       出来事の間隔ぶん {@link WolfAiAgent#advanceTime} で時間を進めるので、
 *       感情のベースライン回帰は実機と同じ規則で起きる。</li>
 *   <li>「選択確率」は、ある文脈でのスコアを {@link BehaviorScorer} で計算し、
 *       Softmax（τ = {@link AiWolfParameters#SOFTMAX_TEMPERATURE}）で確率にした<strong>決定論的な値</strong>。
 *       サンプリングはしない。記憶の検索は {@link WolfAiAgent#decide} と同じ手順で行う。
 *       現在行動は「なし」とする（継続ボーナス 0）。個体は変更しない。</li>
 *   <li>Demo D だけは {@link StandardTestBattery}（200 決定のサンプリング）を
 *       5 セッション（sessionIndex 1〜5）測り、平均と標準偏差を出す。</li>
 * </ul>
 *
 * <p>研究ロジックには何も足さない。既存の公開 API を呼ぶだけである。</p>
 */
public final class DemoEffectSizeEstimate {

    private static final String DIMENSION = "minecraft:overworld";
    private static final String BIOME = "minecraft:plains";
    private static final String ACTOR = "subject-0123456789ab";
    private static final long EXPERIMENT_SEED = 42L;
    private static final BehaviorScorer SCORER = new BehaviorScorer(BehaviorWeights.createDefault());

    /** 撫でのクールダウン（10 秒）。撫でを続けるときの間隔。 */
    private static final long PET_GAP = AiWolfParameters.PET_COOLDOWN_TICKS;
    /** 決定間隔（2 秒）。被弾など、決定ごとにまとめられる出来事の間隔。 */
    private static final long DECISION_GAP = AiWolfParameters.DECISION_INTERVAL_TICKS;

    private static final SpatialRegion HOME = SpatialRegion.of(DIMENSION, 0, 64, 0);
    private static final SpatialRegion CAVE = SpatialRegion.of(DIMENSION, 200, 30, 200);
    private static final SpatialRegion OTHER_CAVE = SpatialRegion.of(DIMENSION, 400, 30, 400);

    private final WolfAiAgent agent;
    private long time = 100_000L;

    private DemoEffectSizeEstimate(String wolfId) {
        agent = new WolfAiAgent(wolfId, ExperimentalCondition.ADAPTIVE, EXPERIMENT_SEED);
        agent.setOwnerResearchId(ACTOR);
    }

    private void events(WolfEventType type, SpatialRegion where, int count, long gapTicks) {
        double size = AiWolfParameters.SPATIAL_REGION_SIZE;
        for (int i = 0; i < count; i++) {
            agent.onEvent(WolfEvent.of(type, time, DIMENSION, BIOME,
                    where.regionX() * size + size / 2, where.regionY() * size + size / 2,
                    where.regionZ() * size + size / 2, ACTOR, 0.5, 1.0));
            elapse(gapTicks);
        }
    }

    private void elapse(long ticks) {
        time += ticks;
        agent.advanceTime(ticks);
    }

    /** 決定論的な選択確率。個体は変更しない。 */
    private Map<BehaviorType, Double> probabilities(WolfContextSnapshot context, SpatialRegion region) {
        MemorySummary memory = MemoryImpactCalculator.summarise(
                MemoryRetriever.retrieve(agent.memoryStore(), MemoryQuery.atLocation(region, ACTOR, time)));
        Set<BehaviorType> eligible = BehaviorEligibility.evaluate(context);
        Map<BehaviorType, BehaviorScore> scores = SCORER.score(agent.state(), context, memory,
                agent.preferences(), null, eligible, agent.condition());
        List<BehaviorType> order = new ArrayList<>(scores.keySet());
        double[] raw = new double[order.size()];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = scores.get(order.get(i)).finalScore();
        }
        double[] p = MathSupport.softmax(raw, AiWolfParameters.SOFTMAX_TEMPERATURE);
        Map<BehaviorType, Double> result = new EnumMap<>(BehaviorType.class);
        for (BehaviorType behavior : BehaviorType.ALL) {
            result.put(behavior, 0.0);
        }
        for (int i = 0; i < raw.length; i++) {
            result.put(order.get(i), p[i]);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 文脈（すべての個体・すべての時点で同一）
    // ------------------------------------------------------------------

    /** 平穏：飼い主 6 blocks・移動中・敵なし・昼・地上。 */
    private static WolfContextSnapshot calm() {
        return WolfContextSnapshot.builder().ownerDistance(6.0).ownerMoving(true).build();
    }

    /** 脅威：飼い主 6 blocks・静止・敵 1 体が 7 blocks・昼・地上。 */
    private static WolfContextSnapshot threat() {
        return WolfContextSnapshot.builder().ownerDistance(6.0).ownerMoving(false)
                .hostileMobCount(1).nearestHostileDistance(7.0).build();
    }

    /** 洞窟：飼い主 6 blocks・移動中・敵なし・地下。場所の恐怖は個体の空間記憶から入れる。 */
    private WolfContextSnapshot cave(SpatialRegion region) {
        return WolfContextSnapshot.builder().ownerDistance(6.0).ownerMoving(true).underground(true)
                .regionFear(agent.regionFear(region)).build();
    }

    // ------------------------------------------------------------------
    // 出力
    // ------------------------------------------------------------------

    private static String header() {
        StringBuilder line = new StringBuilder("| 時点 |");
        StringBuilder rule = new StringBuilder("|---|");
        for (BehaviorType behavior : BehaviorType.ALL) {
            line.append(' ').append(behavior.name()).append(" |");
            rule.append("---:|");
        }
        return line + "\n" + rule;
    }

    private static String row(String label, Map<BehaviorType, Double> p) {
        StringBuilder line = new StringBuilder("| ").append(label).append(" |");
        for (BehaviorType behavior : BehaviorType.ALL) {
            line.append(String.format(Locale.ROOT, " %.1f |", p.get(behavior) * 100.0));
        }
        return line.toString();
    }

    /**
     * 実行する。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        System.out.println("# Demo effect-size estimate");
        System.out.printf(Locale.ROOT, "PARAMETER_SET_VERSION=%d  SOFTMAX_TEMPERATURE=%.2f  experimentSeed=%d%n%n",
                AiWolfParameters.PARAMETER_SET_VERSION, AiWolfParameters.SOFTMAX_TEMPERATURE, EXPERIMENT_SEED);
        demoA();
        demoABreakdown();
        demoB();
        demoC();
        demoD();
    }

    private static void demoA() {
        System.out.println("## Demo A  選択確率 [%]（文脈 = calm）");
        System.out.println(header());
        DemoEffectSizeEstimate a = new DemoEffectSizeEstimate("11111111-1111-4111-8111-111111111111");
        System.out.println(row("A0 初期", a.probabilities(calm(), HOME)));
        a.events(WolfEventType.PET, HOME, 6, PET_GAP);
        a.events(WolfEventType.FEED, HOME, 3, 100L);
        System.out.println(row("A1 撫で6+餌3 (経過 1.5 分)", a.probabilities(calm(), HOME)));
        a.events(WolfEventType.PLAYER_PROTECTED_WOLF, HOME, 1, 100L);
        System.out.println(row("A2 +救援1", a.probabilities(calm(), HOME)));
        a.events(WolfEventType.PET, HOME, 24, PET_GAP);
        System.out.println(row("A3 +撫で24 (さらに 4 分)", a.probabilities(calm(), HOME)));
        a.elapse(36_000L);
        System.out.println(row("A4 A3 の 30 分後", a.probabilities(calm(), HOME)));
        System.out.printf(Locale.ROOT, "A3 state: %s%nA3 preference: %s%n%n", a.agent.state(), a.agent.preferences());

        System.out.println("## Demo A 系列  撫での回数 → P(APPROACH_PLAYER) [%]（撫で 10 秒間隔のみ、文脈 = calm）");
        System.out.println("| 撫での回数 | 経過 [分] | P(APPROACH_PLAYER) [%] | P(EXPLORE_NEARBY) [%] | trust | attachment |");
        System.out.println("|---:|---:|---:|---:|---:|---:|");
        DemoEffectSizeEstimate series = new DemoEffectSizeEstimate("12121212-1212-4121-8121-121212121212");
        for (int pets = 0; pets <= 30; pets += 3) {
            Map<BehaviorType, Double> p = series.probabilities(calm(), HOME);
            System.out.printf(Locale.ROOT, "| %d | %.1f | %.1f | %.1f | %.3f | %.3f |%n", pets,
                    pets * PET_GAP / 1200.0, p.get(BehaviorType.APPROACH_PLAYER) * 100.0,
                    p.get(BehaviorType.EXPLORE_NEARBY) * 100.0,
                    series.agent.state().trust(), series.agent.state().attachment());
            series.events(WolfEventType.PET, HOME, 3, PET_GAP);
        }
        System.out.println();
    }

    /** 決定論的なスコア内訳（{@link #probabilities} と同じ計算経路）。個体は変更しない。 */
    private Map<BehaviorType, BehaviorScore> scores(WolfContextSnapshot context, SpatialRegion region) {
        MemorySummary memory = MemoryImpactCalculator.summarise(
                MemoryRetriever.retrieve(agent.memoryStore(), MemoryQuery.atLocation(region, ACTOR, time)));
        return SCORER.score(agent.state(), context, memory, agent.preferences(), null,
                BehaviorEligibility.evaluate(context), agent.condition());
    }

    /**
     * Phase 14C：Demo A の各時点で、APPROACH_PLAYER と EXPLORE_NEARBY のスコアを成分に分解する。
     * 出来事列は {@link #demoA()} と同じ（同じ個体 ID・同じシード）なので、確率の表と対応する。
     */
    private static void demoABreakdown() {
        DemoEffectSizeEstimate a = new DemoEffectSizeEstimate("11111111-1111-4111-8111-111111111111");
        List<String> labels = new ArrayList<>();
        List<Map<BehaviorType, BehaviorScore>> snapshots = new ArrayList<>();
        List<Map<BehaviorType, Double>> probabilities = new ArrayList<>();
        List<Map<StateParameter, Double>> states = new ArrayList<>();
        Runnable capture = () -> {
            snapshots.add(a.scores(calm(), HOME));
            probabilities.add(a.probabilities(calm(), HOME));
            Map<StateParameter, Double> state = new EnumMap<>(StateParameter.class);
            for (StateParameter parameter : StateParameter.values()) {
                state.put(parameter, a.agent.state().signed(parameter));
            }
            states.add(state);
        };
        labels.add("A0");
        capture.run();
        a.events(WolfEventType.PET, HOME, 6, PET_GAP);
        a.events(WolfEventType.FEED, HOME, 3, 100L);
        labels.add("A1");
        capture.run();
        a.events(WolfEventType.PLAYER_PROTECTED_WOLF, HOME, 1, 100L);
        labels.add("A2");
        capture.run();
        a.events(WolfEventType.PET, HOME, 24, PET_GAP);
        labels.add("A3");
        capture.run();
        a.elapse(36_000L);
        labels.add("A4");
        capture.run();

        writeBreakdownCsv(labels, snapshots, probabilities);
        System.out.println("## Demo A 成分分解（Phase 14C）  スコアの単位は Softmax 前のスコア（τ = 0.60 で割って確率へ）");
        System.out.println("A0 初期 / A1 撫で6+餌3 / A2 +救援1 / A3 +撫で24 / A4 A3 の 30 分後。文脈 = calm、現在行動なし（継続 0）");
        for (BehaviorType behavior : List.of(BehaviorType.APPROACH_PLAYER, BehaviorType.EXPLORE_NEARBY,
                BehaviorType.FOLLOW_NORMAL)) {
            System.out.println();
            System.out.println("### " + behavior.name());
            StringBuilder header = new StringBuilder("| 成分 |");
            StringBuilder rule = new StringBuilder("|---|");
            for (String label : labels) {
                header.append(' ').append(label).append(" |");
                rule.append("---:|");
            }
            System.out.println(header);
            System.out.println(rule);
            List<String> names = List.copyOf(snapshots.get(0).get(behavior).components().keySet());
            for (String name : names) {
                StringBuilder line = new StringBuilder("| " + name + " |");
                for (Map<BehaviorType, BehaviorScore> snapshot : snapshots) {
                    line.append(String.format(Locale.ROOT, " %+.3f |", snapshot.get(behavior).components().get(name)));
                }
                System.out.println(line);
            }
            StringBuilder total = new StringBuilder("| **total** |");
            StringBuilder sum = new StringBuilder("| (componentSum − total) |");
            StringBuilder prob = new StringBuilder("| 選択確率 [%] |");
            for (int i = 0; i < snapshots.size(); i++) {
                BehaviorScore score = snapshots.get(i).get(behavior);
                total.append(String.format(Locale.ROOT, " **%+.3f** |", score.finalScore()));
                sum.append(String.format(Locale.ROOT, " %.1e |", score.componentSum() - score.finalScore()));
                prob.append(String.format(Locale.ROOT, " %.1f |", probabilities.get(i).get(behavior) * 100.0));
            }
            System.out.println(total);
            System.out.println(sum);
            System.out.println(prob);
        }

        System.out.println();
        System.out.println("### APPROACH_PLAYER の state 成分の内訳（重み × (2x − 1)）");
        StringBuilder header = new StringBuilder("| 内部状態 | 重み |");
        StringBuilder rule = new StringBuilder("|---|---:|");
        for (String label : labels) {
            header.append(' ').append(label).append(" |");
            rule.append("---:|");
        }
        System.out.println(header);
        System.out.println(rule);
        Map<StateParameter, Double> weights = BehaviorWeights.createDefault().stateWeights(BehaviorType.APPROACH_PLAYER);
        for (Map.Entry<StateParameter, Double> entry : weights.entrySet()) {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "| %s | %+.2f |",
                    entry.getKey().key(), entry.getValue()));
            for (Map<StateParameter, Double> state : states) {
                line.append(String.format(Locale.ROOT, " %+.3f |", entry.getValue() * state.get(entry.getKey())));
            }
            System.out.println(line);
        }
        System.out.println();
    }

    /**
     * Demo A の成分分解を CSV にも書く（{@code analysis/score_contribution.py} の入力）。
     * 出力先：{@code research-output/score-breakdown/demo_a_breakdown.csv}。
     */
    private static void writeBreakdownCsv(List<String> labels, List<Map<BehaviorType, BehaviorScore>> snapshots,
                                          List<Map<BehaviorType, Double>> probabilities) {
        StringBuilder csv = new StringBuilder(
                "parameterSetVersion,timepoint,behavior,component,value,total,probability\n");
        for (int i = 0; i < labels.size(); i++) {
            for (Map.Entry<BehaviorType, BehaviorScore> entry : snapshots.get(i).entrySet()) {
                for (Map.Entry<String, Double> component : entry.getValue().components().entrySet()) {
                    csv.append(String.format(Locale.ROOT, "%d,%s,%s,%s,%.6f,%.6f,%.6f%n",
                            AiWolfParameters.PARAMETER_SET_VERSION, labels.get(i), entry.getKey().id(),
                            component.getKey(), component.getValue(), entry.getValue().finalScore(),
                            probabilities.get(i).get(entry.getKey())));
                }
            }
        }
        try {
            java.nio.file.Path out = java.nio.file.Path.of("research-output", "score-breakdown");
            java.nio.file.Files.createDirectories(out);
            java.nio.file.Files.writeString(out.resolve("demo_a_breakdown.csv"), csv.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static void demoB() {
        System.out.println("## Demo B  選択確率 [%]（文脈 = threat）");
        System.out.println(header());
        DemoEffectSizeEstimate b = new DemoEffectSizeEstimate("22222222-2222-4222-8222-222222222222");
        System.out.println(row("B0 初期", b.probabilities(threat(), HOME)));
        b.events(WolfEventType.MONSTER_ENCOUNTER, HOME, 1, DECISION_GAP);
        b.events(WolfEventType.WOLF_DAMAGED, HOME, 2, DECISION_GAP);
        b.events(WolfEventType.WOLF_LOW_HEALTH, HOME, 1, DECISION_GAP);
        b.events(WolfEventType.PLAYER_PROTECTED_WOLF, HOME, 1, DECISION_GAP);
        System.out.println(row("B1 被弾3+救援1 の直後", b.probabilities(threat(), HOME)));
        b.elapse(1_200L);
        System.out.println(row("B2 B1 の 1 分後", b.probabilities(threat(), HOME)));
        String stateB2 = b.agent.state().toString();
        b.elapse(36_000L);
        System.out.println(row("B3 B1 の 31 分後", b.probabilities(threat(), HOME)));
        System.out.printf(Locale.ROOT, "B2 state: %s%nB3 state: %s%n%n", stateB2, b.agent.state());
    }

    private static void demoC() {
        System.out.println("## Demo C 系列  同じ場所での被弾回数 → 場所の実効恐怖値（被弾は 2 秒間隔）");
        System.out.println("| 被弾回数 | 実効恐怖値 | 危険判定 (>= 0.30) |");
        System.out.println("|---:|---:|:---:|");
        DemoEffectSizeEstimate series = new DemoEffectSizeEstimate("33333333-3333-4333-8333-333333333333");
        for (int hits = 0; hits <= 6; hits++) {
            double fear = series.agent.regionFear(CAVE);
            System.out.printf(Locale.ROOT, "| %d | %.3f | %s |%n", hits, fear,
                    fear >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD ? "yes" : "no");
            series.events(WolfEventType.WOLF_DAMAGED, CAVE, 1, DECISION_GAP);
        }
        System.out.println();

        DemoEffectSizeEstimate c = new DemoEffectSizeEstimate("44444444-4444-4444-8444-444444444444");
        DemoEffectSizeEstimate fresh = new DemoEffectSizeEstimate("55555555-5555-4555-8555-555555555555");
        c.events(WolfEventType.MONSTER_ENCOUNTER, CAVE, 1, DECISION_GAP);
        c.events(WolfEventType.WOLF_DAMAGED, CAVE, 3, DECISION_GAP);
        c.events(WolfEventType.WOLF_LOW_HEALTH, CAVE, 1, DECISION_GAP);
        double beforeRescue = c.agent.regionFear(CAVE);
        c.events(WolfEventType.PLAYER_PROTECTED_WOLF, CAVE, 1, DECISION_GAP);
        double afterRescue = c.agent.regionFear(CAVE);
        System.out.printf(Locale.ROOT, "Demo C 救援の影響：洞窟の実効恐怖値 救援前 %.3f → 同じ場所で救援後 %.3f%n%n",
                beforeRescue, afterRescue);
        c.elapse(2_400L);

        System.out.println("## Demo C  選択確率 [%]（文脈 = cave、場所の恐怖は各個体の空間記憶）");
        System.out.println(header());
        System.out.println(row("未経験の個体・洞窟", fresh.probabilities(fresh.cave(CAVE), CAVE)));
        System.out.println(row("経験後・危険な洞窟 (2 分後)", c.probabilities(c.cave(CAVE), CAVE)));
        System.out.println(row("経験後・別の洞窟 (2 分後)", c.probabilities(c.cave(OTHER_CAVE), OTHER_CAVE)));
        c.elapse(36_000L);
        System.out.println(row("経験後・危険な洞窟 (32 分後)", c.probabilities(c.cave(CAVE), CAVE)));
        System.out.println(row("経験後・別の洞窟 (32 分後)", c.probabilities(c.cave(OTHER_CAVE), OTHER_CAVE)));
        System.out.printf(Locale.ROOT, "32 分後の洞窟の実効恐怖値 %.3f%n%n", c.agent.regionFear(CAVE));
    }

    private static void demoD() {
        DemoEffectSizeEstimate untrained = new DemoEffectSizeEstimate("88888888-8888-4888-8888-888888888888");
        DemoEffectSizeEstimate wolfA = new DemoEffectSizeEstimate("66666666-6666-4666-8666-666666666666");
        DemoEffectSizeEstimate wolfB = new DemoEffectSizeEstimate("77777777-7777-4777-8777-777777777777");
        // 10 分間の育成。1 分ごとに：A は撫で 6 回（10 秒間隔）+ 餌 1 回、
        // B は安全な単独探索 6 回（10 秒ごと = 5 決定の連続探索ごと）。3 分に 1 回、A は救援、B は新バイオーム。
        for (int minute = 0; minute < 10; minute++) {
            wolfA.events(WolfEventType.PET, HOME, 6, PET_GAP);
            wolfA.events(WolfEventType.FEED, HOME, 1, 10L);
            SpatialRegion field = SpatialRegion.of(DIMENSION, 300 + minute * 30, 64, 0);
            wolfB.events(WolfEventType.SAFE_SOLO_EXPLORATION, field, 6, PET_GAP);
            if (minute % 3 == 0) {
                wolfA.events(WolfEventType.PLAYER_PROTECTED_WOLF, HOME, 1, 10L);
                wolfB.events(WolfEventType.NEW_BIOME, field, 1, 10L);
            }
        }
        System.out.println("## Demo D  標準テストバッテリ（200 決定 × 5 セッション、平均 ± 標準偏差、割合 [%]）");
        System.out.println("| 個体 | APPROACH_PLAYER | FOLLOW_CLOSE | FOLLOW_NORMAL | EXPLORE_NEARBY | DEFEND_PLAYER | 平均飼い主距離 [blocks] |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|");
        for (Object[] entry : new Object[][] {{"未育成", untrained}, {"Wolf A（飼い主中心 10 分）", wolfA},
                {"Wolf B（探索中心 10 分）", wolfB}}) {
            DemoEffectSizeEstimate wolf = (DemoEffectSizeEstimate) entry[1];
            int sessions = 5;
            BehaviorType[] columns = {BehaviorType.APPROACH_PLAYER, BehaviorType.FOLLOW_CLOSE,
                    BehaviorType.FOLLOW_NORMAL, BehaviorType.EXPLORE_NEARBY, BehaviorType.DEFEND_PLAYER};
            double[][] values = new double[columns.length + 1][sessions];
            for (int s = 0; s < sessions; s++) {
                BehaviouralSignature signature = StandardTestBattery.measure(wolf.agent, HOME, ACTOR,
                        wolf.time, s + 1L);
                for (int c = 0; c < columns.length; c++) {
                    values[c][s] = signature.proportionOf(columns[c]) * 100.0;
                }
                values[columns.length][s] = signature.meanOwnerDistance();
            }
            StringBuilder line = new StringBuilder("| " + entry[0] + " |");
            for (double[] column : values) {
                line.append(String.format(Locale.ROOT, " %.1f ± %.1f |", mean(column), sd(column)));
            }
            System.out.println(line);
        }
        System.out.printf(Locale.ROOT, "%nWolf A state: %s%nWolf A preference: %s%n", wolfA.agent.state(), wolfA.agent.preferences());
        System.out.printf(Locale.ROOT, "Wolf B state: %s%nWolf B preference: %s%n", wolfB.agent.state(), wolfB.agent.preferences());
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double sd(double[] values) {
        double m = mean(values);
        double sum = 0.0;
        for (double value : values) {
            sum += (value - m) * (value - m);
        }
        return Math.sqrt(sum / (values.length - 1));
    }
}
