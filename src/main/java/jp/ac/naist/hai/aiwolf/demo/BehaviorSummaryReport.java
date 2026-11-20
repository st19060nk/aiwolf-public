package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;

/**
 * {@code /wolfai summary} の出力を組み立てる。<strong>Minecraft に依存しない。</strong>
 *
 * <p>直近 1 分・直近 5 分・セッション全体の 3 列を並べ、Before / After をそのまま比べられるようにする。
 * 割合は時間で重み付けした実際の行動の割合（{@link BehaviorHistory}）であり、
 * デバッグ表示の「選択確率」とは別物である。</p>
 */
public final class BehaviorSummaryReport {

    /** 1 分 [ticks]。 */
    public static final long ONE_MINUTE = 1_200L;

    /** 5 分 [ticks]。 */
    public static final long FIVE_MINUTES = 6_000L;

    private BehaviorSummaryReport() {
        throw new AssertionError("utility class");
    }

    /**
     * 3 つの区間の集計を表にする。
     *
     * @param history 記録
     * @param wolfId  個体 ID
     * @param now     現在のワールド時刻 [ticks]
     * @return 表示行
     */
    public static List<String> lines(BehaviorHistory history, String wolfId, long now) {
        BehaviorHistory.Summary last1 = history.summarize(wolfId, now, ONE_MINUTE);
        BehaviorHistory.Summary last5 = history.summarize(wolfId, now, FIVE_MINUTES);
        BehaviorHistory.Summary session = history.summarize(wolfId, now, Long.MAX_VALUE);

        List<String> lines = new ArrayList<>();
        if (session.isEmpty()) {
            lines.add("まだ記録がありません（オオカミが座っておらず、AI が判断しているときに記録されます）");
            return lines;
        }
        // Minecraft のチャットはプロポーショナルフォントで桁が揃わないので、列の順を見出しで明示し「/」で区切る。
        lines.add("Behavior:  last 1 min / last 5 min / session");
        for (BehaviorType behavior : BehaviorType.ALL) {
            double s = session.proportions().getOrDefault(behavior, 0.0);
            double a = last1.proportions().getOrDefault(behavior, 0.0);
            double b = last5.proportions().getOrDefault(behavior, 0.0);
            if (s == 0.0 && a == 0.0 && b == 0.0) {
                continue;
            }
            lines.add(String.format(Locale.ROOT, "  %s:  %s / %s / %s",
                    behavior.name(), percent(last1, a), percent(last5, b), percent(session, s)));
        }
        lines.add(String.format(Locale.ROOT, "Mean Owner Distance [blocks]:  %s / %s / %s",
                distance(last1), distance(last5), distance(session)));
        lines.add(String.format(Locale.ROOT, "Covered [min]:  %.1f / %.1f / %.1f",
                last1.coveredTicks() / (double) ONE_MINUTE, last5.coveredTicks() / (double) ONE_MINUTE,
                session.coveredTicks() / (double) ONE_MINUTE));
        lines.add(String.format(Locale.ROOT, "Decisions (n):  %d / %d / %d",
                last1.decisionCount(), last5.decisionCount(), session.decisionCount()));
        lines.add("割合 = 実際に行動していた時間の割合 [%]、距離 = 飼い主が近くにいた間の平均 [blocks]");
        return lines;
    }

    private static String percent(BehaviorHistory.Summary summary, double value) {
        return summary.coveredTicks() == 0L ? "-" : String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }

    private static String distance(BehaviorHistory.Summary summary) {
        return Double.isNaN(summary.meanOwnerDistance()) ? "-"
                : String.format(Locale.ROOT, "%.1f", summary.meanOwnerDistance());
    }
}
