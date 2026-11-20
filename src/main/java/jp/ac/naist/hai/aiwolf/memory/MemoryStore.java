package jp.ac.naist.hai.aiwolf.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * 1 個体分のエピソード記憶の保管庫。
 *
 * <p>容量上限（既定 200 件）を超えたら、salience と recency から求めた保持スコアが
 * 最も低い記憶を削除する。保持スコアは salience を重く見るため、
 * 重要な出来事は古くなっても残り、些細な出来事は新しくても早く消える。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ変更すること。</p>
 */
public final class MemoryStore {

    private final List<WolfMemory> memories = new ArrayList<>();
    private final int capacity;

    /** 既定の容量で保管庫を生成する。 */
    public MemoryStore() {
        this(AiWolfParameters.MAX_MEMORIES_PER_WOLF);
    }

    /**
     * 容量を明示して保管庫を生成する。
     *
     * @param capacity 保持件数の上限（正の値）
     */
    public MemoryStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0 but was " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 記憶を追加する。容量を超えた場合は重要度の低い記憶を削除する。
     *
     * @param memory           追加する記憶
     * @param currentWorldTime 現在のワールド時刻（剪定時の recency 計算に使用）
     */
    public void add(WolfMemory memory, long currentWorldTime) {
        Objects.requireNonNull(memory, "memory");
        memories.add(memory);
        pruneIfNeeded(currentWorldTime);
    }

    /**
     * 保持している記憶の不変ビューを返す。
     *
     * @return 記憶リスト
     */
    public List<WolfMemory> memories() {
        return Collections.unmodifiableList(memories);
    }

    /** @return 保持件数 */
    public int size() {
        return memories.size();
    }

    /** @return 容量上限 */
    public int capacity() {
        return capacity;
    }

    /** すべての記憶を削除する（{@code /wolfai reset} 用）。 */
    public void clear() {
        memories.clear();
    }

    /**
     * 保持スコアを返す。剪定の判断に用いる。
     *
     * @param memory           記憶
     * @param currentWorldTime 現在のワールド時刻
     * @return 保持スコア
     */
    public static double retentionScore(WolfMemory memory, long currentWorldTime) {
        double recency = memory.recency(currentWorldTime, AiWolfParameters.RECENCY_TAU_TICKS);
        return AiWolfParameters.RETENTION_WEIGHT_SALIENCE * memory.salience()
                + AiWolfParameters.RETENTION_WEIGHT_RECENCY * recency;
    }

    private void pruneIfNeeded(long currentWorldTime) {
        while (memories.size() > capacity) {
            int weakestIndex = 0;
            double weakestScore = Double.MAX_VALUE;
            for (int i = 0; i < memories.size(); i++) {
                double score = retentionScore(memories.get(i), currentWorldTime);
                if (score < weakestScore) {
                    weakestScore = score;
                    weakestIndex = i;
                }
            }
            memories.remove(weakestIndex);
        }
    }
}
