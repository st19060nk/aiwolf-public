package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.RegionMemory;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * {@code /wolfai inspect} の出力を組み立てる。<strong>Minecraft に依存しない。</strong>
 *
 * <p>Demo D で Wolf A / B を並べて確認するための、1 個体分の全研究状態の要約。
 * 個体を読むだけで変更しない。飼い主は匿名化済みの研究 ID でだけ表示し、
 * 生の UUID（個体・プレイヤー）は出さない。</p>
 */
public final class WolfInspectReport {

    private WolfInspectReport() {
        throw new AssertionError("utility class");
    }

    /**
     * レポートの行を組み立てる。
     *
     * @param subjectLabel 画面表示用のラベル（{@code WolfAiRegistry.displayLabel}）
     * @param agent        対象個体（変更しない）
     * @param lastDecision 直近の決定（{@code null} 可）
     * @return 表示行
     */
    public static List<String> lines(String subjectLabel, WolfAiAgent agent, BehaviorDecision lastDecision) {
        Objects.requireNonNull(agent, "agent");
        List<String> lines = new ArrayList<>();
        lines.add("Wolf Subject: " + subjectLabel);
        lines.add(String.format(Locale.ROOT, "  condition=%s  owner=%s  decisions=%d%s",
                agent.condition().id(),
                agent.ownerResearchId() == null ? "-" : agent.ownerResearchId(),
                agent.random().decisionIndex(),
                agent.isLearningFrozen() ? "  [EVALUATION: learning frozen]" : ""));

        WolfInternalState state = agent.state();
        StringBuilder stateLine = new StringBuilder("State:");
        for (StateParameter parameter : StateParameter.values()) {
            double initial = WolfInternalState.createInitial().get(parameter);
            double value = state.get(parameter);
            stateLine.append(String.format(Locale.ROOT, " %s %.2f(%+.2f)",
                    parameter.key(), value, value - initial));
        }
        lines.add(stateLine.toString());
        lines.add("  (括弧内は研究初期値からの変化)");

        StringBuilder preferenceLine = new StringBuilder("Preference:");
        for (BehaviorType behavior : BehaviorType.ALL) {
            preferenceLine.append(String.format(Locale.ROOT, " %s %+.3f",
                    behavior.id(), agent.preferences().get(behavior)));
        }
        lines.add(preferenceLine.toString());

        lines.add(String.format(Locale.ROOT, "Memory: %d / %d  %s",
                agent.memoryStore().size(), agent.memoryStore().capacity(),
                memoryBreakdown(agent.memoryStore().memories())));
        lines.add("  Last retrieved: " + WolfDebugView.retrievedMemory(agent));

        int dangerous = 0;
        double maxFear = 0.0;
        for (RegionMemory region : agent.spatialMemory().regions().values()) {
            maxFear = Math.max(maxFear, region.effectiveFear());
            if (region.effectiveFear() >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD) {
                dangerous++;
            }
        }
        lines.add(String.format(Locale.ROOT, "Spatial: %d regions  dangerous=%d  max fear=%.2f",
                agent.spatialMemory().size(), dangerous, maxFear));

        BehaviorType current = agent.currentBehavior();
        lines.add(String.format(Locale.ROOT, "Behavior: %s%s",
                current == null ? "-" : current.name(),
                lastDecision == null ? ""
                        : String.format(Locale.ROOT, "  (P=%.2f, decision #%d)",
                                lastDecision.selectedProbability(), lastDecision.decisionIndex())));
        return lines;
    }

    /**
     * 記憶を種別ごとに数え、多い順に並べる。
     *
     * @param memories 記憶
     * @return {@code "[pet x4, wolf_damaged x2]"} の形
     */
    static String memoryBreakdown(List<WolfMemory> memories) {
        if (memories.isEmpty()) {
            return "[]";
        }
        Map<WolfEventType, Integer> counts = new EnumMap<>(WolfEventType.class);
        for (WolfMemory memory : memories) {
            counts.merge(memory.type(), 1, Integer::sum);
        }
        List<Map.Entry<WolfEventType, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(sorted.get(i).getKey().id()).append(" x").append(sorted.get(i).getValue());
        }
        return text.append(']').toString();
    }
}
