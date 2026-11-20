package jp.ac.naist.hai.aiwolf.memory;

/**
 * 記憶検索の問い合わせ条件。
 *
 * <p>「今まさに何が起きていて、どこにいて、誰といるのか」を表す。
 * relevance はこの条件と各記憶との一致度から計算される。</p>
 *
 * @param currentEventType 現在起きている出来事の種別（なければ {@code null}）
 * @param region           現在の量子化領域（なければ {@code null}）
 * @param actorId          現在関係している主体の匿名 ID（なければ {@code null}）
 * @param currentWorldTime 現在のワールド時刻 [ticks]
 */
public record MemoryQuery(
        jp.ac.naist.hai.aiwolf.event.WolfEventType currentEventType,
        SpatialRegion region,
        String actorId,
        long currentWorldTime) {

    /**
     * 出来事を伴わない、通常の行動評価時の問い合わせを生成する。
     *
     * @param region           現在の領域
     * @param actorId          現在の飼い主の匿名 ID
     * @param currentWorldTime 現在のワールド時刻
     * @return 問い合わせ
     */
    public static MemoryQuery atLocation(SpatialRegion region, String actorId, long currentWorldTime) {
        return new MemoryQuery(null, region, actorId, currentWorldTime);
    }
}
