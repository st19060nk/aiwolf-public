package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;

/**
 * {@code /wolfai summary} のための、最近の行動の記録。<strong>Minecraft に依存しない。</strong>
 *
 * <p>決定のたびに「時刻・選ばれた行動・飼い主との距離」を 1 件ずつ積むだけで、
 * 個体の状態にも行動選択にも一切関与しない。メモリ上にだけ持ち、永続化もしない
 * （研究データは従来どおり decisions.jsonl にある）。</p>
 *
 * <h2>集計の仕方</h2>
 * <p>割合は<strong>時間で重み付け</strong>する。1 件の記録は「その決定から次の決定まで」
 * その行動を続けた時間を表す。緊急時は決定の間隔が短くなるので、件数で数えると
 * 戦闘中の行動が実際より多く見えてしまうためである。1 件の重みは
 * {@link #MAX_SEGMENT_TICKS} で打ち切る（座らせていた間などに決定が止まると、
 * 最後の行動が不当に長く数えられるのを防ぐ）。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class BehaviorHistory {

    /** 1 個体あたりの最大記録数。決定 2 秒ごとで約 11 時間分。 */
    public static final int CAPACITY = 20_000;

    /** 1 件の記録が表す最長の時間 [ticks]（通常の決定間隔 40 ticks の 3 倍）。 */
    public static final long MAX_SEGMENT_TICKS = 120L;

    /** サーバ全体で共有する記録。{@code WolfAiController} が決定のたびに積む。 */
    public static final BehaviorHistory SHARED = new BehaviorHistory();

    /** 1 件の記録。 */
    public record Entry(long worldTime, BehaviorType behavior, boolean ownerPresent, double ownerDistance) {
    }

    /**
     * 集計結果。
     *
     * @param proportions       行動 → 時間割合（0〜1、合計 1）
     * @param coveredTicks      集計に含めた時間 [ticks]
     * @param decisionCount     集計に含めた決定数
     * @param meanOwnerDistance 飼い主が近傍にいた間の平均距離 [blocks]。いなければ NaN
     */
    public record Summary(Map<BehaviorType, Double> proportions, long coveredTicks, int decisionCount,
                          double meanOwnerDistance) {

        /** @return 1 件も記録がなければ true */
        public boolean isEmpty() {
            return decisionCount == 0;
        }
    }

    private final Map<String, Deque<Entry>> entries = new HashMap<>();
    private final Map<String, Long> sessionStart = new HashMap<>();

    /**
     * 決定 1 件を記録する。
     *
     * @param wolfId        個体 ID
     * @param worldTime     決定のワールド時刻 [ticks]
     * @param behavior      選ばれた行動
     * @param ownerPresent  飼い主が近傍にいたか
     * @param ownerDistance 飼い主との距離 [blocks]
     */
    public void record(String wolfId, long worldTime, BehaviorType behavior,
                       boolean ownerPresent, double ownerDistance) {
        Objects.requireNonNull(wolfId, "wolfId");
        Objects.requireNonNull(behavior, "behavior");
        Deque<Entry> deque = entries.computeIfAbsent(wolfId, key -> new ArrayDeque<>());
        sessionStart.putIfAbsent(wolfId, worldTime);
        deque.addLast(new Entry(worldTime, behavior, ownerPresent, ownerDistance));
        while (deque.size() > CAPACITY) {
            deque.removeFirst();
        }
    }

    /**
     * 個体の記録を消す（{@code /wolfai summary reset}）。次の記録からが新しいセッションになる。
     *
     * @param wolfId 個体 ID
     */
    public void reset(String wolfId) {
        entries.remove(wolfId);
        sessionStart.remove(wolfId);
    }

    /** すべての記録を消す。 */
    public void clear() {
        entries.clear();
        sessionStart.clear();
    }

    /**
     * セッション（記録開始または reset 以降）の開始時刻を返す。
     *
     * @param wolfId 個体 ID
     * @return 開始時刻。記録がなければ {@code -1}
     */
    public long sessionStartTick(String wolfId) {
        return sessionStart.getOrDefault(wolfId, -1L);
    }

    /**
     * 直近の区間を集計する。
     *
     * @param wolfId      個体 ID
     * @param now         現在のワールド時刻 [ticks]
     * @param windowTicks 区間の長さ [ticks]。{@code Long.MAX_VALUE} ならセッション全体
     * @return 集計結果
     */
    public Summary summarize(String wolfId, long now, long windowTicks) {
        Map<BehaviorType, Double> weights = new EnumMap<>(BehaviorType.class);
        Deque<Entry> deque = entries.get(wolfId);
        if (deque == null || deque.isEmpty()) {
            return new Summary(Map.of(), 0L, 0, Double.NaN);
        }
        long windowStart = windowTicks == Long.MAX_VALUE ? Long.MIN_VALUE : now - windowTicks;

        double total = 0.0;
        double distanceWeighted = 0.0;
        double distanceWeight = 0.0;
        int decisions = 0;
        Iterator<Entry> iterator = deque.iterator();
        Entry current = iterator.next();
        while (current != null) {
            Entry next = iterator.hasNext() ? iterator.next() : null;
            long end = next == null ? now : next.worldTime();
            long start = current.worldTime();
            long segmentEnd = Math.min(end, start + MAX_SEGMENT_TICKS);
            long clippedStart = Math.max(start, windowStart);
            long clippedEnd = Math.min(segmentEnd, now);
            if (clippedEnd > clippedStart) {
                double weight = clippedEnd - clippedStart;
                weights.merge(current.behavior(), weight, Double::sum);
                total += weight;
                if (current.ownerPresent()) {
                    distanceWeighted += weight * current.ownerDistance();
                    distanceWeight += weight;
                }
            }
            if (start >= windowStart && start <= now) {
                decisions++;
            }
            current = next;
        }

        Map<BehaviorType, Double> proportions = new EnumMap<>(BehaviorType.class);
        if (total > 0.0) {
            for (Map.Entry<BehaviorType, Double> entry : weights.entrySet()) {
                proportions.put(entry.getKey(), entry.getValue() / total);
            }
        }
        return new Summary(proportions, (long) total, decisions,
                distanceWeight > 0.0 ? distanceWeighted / distanceWeight : Double.NaN);
    }
}
