package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScore;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.memory.RegionMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * デバッグ表示（Wolf Debug Overlay）の中身を組み立てる。<strong>Minecraft に依存しない。</strong>
 *
 * <p><strong>読むだけで、個体を一切変更しない。</strong>記憶の検索も行動の再計算もしない。
 * 表示するのは、直近の実際の決定（{@link WolfAiAgent#decide} の結果）と、
 * その決定で実際に参照された記憶だけである。表示のために別の計算をすると、
 * 「画面の説明」と「実際の行動理由」がずれてしまう。</p>
 *
 * <p>生の UUID（プレイヤー・飼い主・個体）は出力に含めない。記憶は種別だけを出す。</p>
 */
public final class WolfDebugView {

    /** 表示の詳しさ。 */
    public enum Level {
        /** 表示しない。 */
        OFF,
        /** 行動・trust・attachment・fear・curiosity・参照記憶・場所の恐怖。 */
        BASIC,
        /** BASIC に加えて、全状態・選好・空間記憶・上位スコア・確率・決定番号。 */
        DETAILED
    }

    /** サイドバーに出せる最大行数（Minecraft の制限）。 */
    public static final int MAX_LINES = 15;

    /** DETAILED で出す上位スコアの件数。 */
    private static final int TOP_SCORES = 4;

    private WolfDebugView() {
        throw new AssertionError("utility class");
    }

    /**
     * 表示行を組み立てる。
     *
     * @param level        表示の詳しさ（OFF なら空リスト）
     * @param agent        対象個体（変更しない）
     * @param lastDecision 直近の決定（未決定なら {@code null}）
     * @param region       個体がいま居る領域（{@code null} 可）
     * @param demoMode     Demo Mode 中なら true
     * @param evaluation   評価モード中なら true
     * @return 上から順の表示行（最大 {@link #MAX_LINES} 行）
     */
    public static List<String> lines(Level level, WolfAiAgent agent, BehaviorDecision lastDecision,
                                     SpatialRegion region, boolean demoMode, boolean evaluation) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(agent, "agent");
        List<String> lines = new ArrayList<>();
        if (level == Level.OFF) {
            return lines;
        }
        WolfInternalState state = agent.state();
        BehaviorType behavior = agent.currentBehavior();

        if (evaluation) {
            lines.add("[EVALUATION] learning frozen");
        } else if (demoMode) {
            lines.add("[DEMO MODE]");
        }

        String probability = lastDecision == null ? ""
                : String.format(Locale.ROOT, " %.0f%%", lastDecision.selectedProbability() * 100.0);
        lines.add("Behavior: " + behaviorName(behavior) + probability);

        if (level == Level.BASIC) {
            lines.add(String.format(Locale.ROOT, "Trust %.2f  Attach %.2f", state.trust(), state.attachment()));
            lines.add(String.format(Locale.ROOT, "Fear %.2f  Curious %.2f", state.fear(), state.curiosity()));
        } else {
            lines.add(String.format(Locale.ROOT, "Tru %.2f Att %.2f Fear %.2f",
                    state.trust(), state.attachment(), state.fear()));
            lines.add(String.format(Locale.ROOT, "Cur %.2f Aut %.2f Str %.2f",
                    state.curiosity(), state.autonomy(), state.stress()));
            lines.add(String.format(Locale.ROOT, "Energy %.2f", state.energy()));
        }

        lines.add("Memory: " + retrievedMemory(agent));
        lines.add(locationLine(agent, region, level));

        if (level == Level.DETAILED) {
            lines.add(preferenceLine(agent));
            if (lastDecision != null) {
                lines.add("Top scores (score / P):");
                for (Map.Entry<BehaviorType, BehaviorScore> entry : topScores(lastDecision)) {
                    double p = lastDecision.probabilities().getOrDefault(entry.getKey(), 0.0);
                    lines.add(String.format(Locale.ROOT, " %s %.2f / %.0f%%",
                            shortName(entry.getKey()), entry.getValue().finalScore(), p * 100.0));
                }
                lines.add(String.format(Locale.ROOT, "Decision #%d  (%s)",
                        lastDecision.decisionIndex(), agent.condition().id()));
            } else {
                lines.add("(no decision yet)");
            }
        }
        return lines.size() > MAX_LINES ? new ArrayList<>(lines.subList(0, MAX_LINES)) : lines;
    }

    /**
     * 直近の決定で実際に参照された記憶のうち、最も強く効いたものの種別を返す。
     *
     * @param agent 対象個体
     * @return {@code "PLAYER_PROTECTED_WOLF (+2)"} のような文字列。なければ {@code "-"}
     */
    public static String retrievedMemory(WolfAiAgent agent) {
        List<String> ids = agent.lastRelevantMemoryIds();
        if (ids.isEmpty()) {
            return "-";
        }
        // MemoryRetriever は検索スコアの降順で返すので、先頭が最も強く効いた記憶である。
        String first = ids.get(0);
        String type = "?";
        for (WolfMemory memory : agent.memoryStore().memories()) {
            if (memory.memoryId().equals(first)) {
                type = memory.type().name();
                break;
            }
        }
        return ids.size() > 1 ? type + " (+" + (ids.size() - 1) + ")" : type;
    }

    private static String locationLine(WolfAiAgent agent, SpatialRegion region, Level level) {
        double fear = agent.regionFear(region);
        if (level == Level.BASIC) {
            return String.format(Locale.ROOT, "Location fear %.2f", fear);
        }
        RegionMemory memory = region == null ? null : agent.spatialMemory().get(region);
        if (memory == null) {
            return String.format(Locale.ROOT, "Loc fear %.2f (unknown)", fear);
        }
        return String.format(Locale.ROOT, "Loc fear %.2f pos %.2f n=%d",
                fear, memory.positiveValue(), memory.visitCount());
    }

    private static String preferenceLine(WolfAiAgent agent) {
        BehaviorType strongest = null;
        double strongestValue = 0.0;
        for (BehaviorType type : BehaviorType.ALL) {
            double value = agent.preferences().get(type);
            if (Math.abs(value) > Math.abs(strongestValue)) {
                strongest = type;
                strongestValue = value;
            }
        }
        return strongest == null ? "Pref: (initial)"
                : String.format(Locale.ROOT, "Pref %s %+.3f", shortName(strongest), strongestValue);
    }

    private static List<Map.Entry<BehaviorType, BehaviorScore>> topScores(BehaviorDecision decision) {
        return decision.scores().entrySet().stream()
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<BehaviorType, BehaviorScore> entry) -> entry.getValue().finalScore())
                        .reversed())
                .limit(TOP_SCORES)
                .toList();
    }

    private static String behaviorName(BehaviorType behavior) {
        return behavior == null ? "-" : behavior.name();
    }

    /** サイドバーの幅に収めるための短い行動名。 */
    private static String shortName(BehaviorType behavior) {
        return switch (behavior) {
            case FOLLOW_CLOSE -> "FOLLOW_CLOSE";
            case FOLLOW_NORMAL -> "FOLLOW_NORM";
            case EXPLORE_NEARBY -> "EXPLORE";
            case APPROACH_PLAYER -> "APPROACH";
            case DEFEND_PLAYER -> "DEFEND";
            case RETREAT -> "RETREAT";
            case AVOID_LOCATION -> "AVOID_LOC";
            case REST_NEAR_PLAYER -> "REST";
        };
    }
}
