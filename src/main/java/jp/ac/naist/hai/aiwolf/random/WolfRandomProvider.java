package jp.ac.naist.hai.aiwolf.random;

import java.util.UUID;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * 個体ごとの決定論的乱数供給器。研究の再現性を担保する中核クラス。
 *
 * <p>通常の {@link java.util.Random} は「これまで何回呼ばれたか」に状態が依存するため、
 * 途中でセーブ／ロードが挟まると再現性が壊れる。本クラスは乱数値を
 * {@code (seed, decisionIndex)} から純粋関数として導出するため、
 * 永続化すべき状態は {@code decisionIndex} ただ 1 つで済み、
 * 「同一イベント列 + 同一シード ⇒ 完全に同一の行動列」が保証される。</p>
 *
 * <pre>
 * seed = mix64(experimentSeed, wolfUUID)
 * r_k  = toUnitInterval( mix64(seed ^ mix64(k)) )
 * </pre>
 */
public final class WolfRandomProvider {

    /** {@code long} を [0,1) へ写すための除数（53 bit 精度）。 */
    private static final double UNIT_SCALE = 1.0 / (1L << 53);

    private final long seed;
    private long decisionIndex;

    private WolfRandomProvider(long seed, long decisionIndex) {
        this.seed = seed;
        this.decisionIndex = decisionIndex;
    }

    /**
     * 実験シードと個体 UUID から供給器を生成する。
     *
     * @param experimentSeed 実験単位のシード
     * @param wolfUuid       個体 UUID
     * @return 供給器（decisionIndex は 0 から開始）
     */
    public static WolfRandomProvider forWolf(long experimentSeed, UUID wolfUuid) {
        return new WolfRandomProvider(AiWolfParameters.deriveIndividualSeed(experimentSeed, wolfUuid), 0L);
    }

    /**
     * 既知のシードと decisionIndex から供給器を復元する（永続化からの読み戻し用）。
     *
     * @param seed          個体固有シード
     * @param decisionIndex 次に使用する決定インデックス
     * @return 供給器
     */
    public static WolfRandomProvider restore(long seed, long decisionIndex) {
        return new WolfRandomProvider(seed, decisionIndex);
    }

    /**
     * 次の決定用の乱数値を返し、決定インデックスを 1 つ進める。
     *
     * @return [0,1) の乱数値
     */
    public double nextDecisionRandom() {
        double value = randomValueFor(seed, decisionIndex);
        decisionIndex++;
        return value;
    }

    /**
     * 決定インデックスを進めずに、次に得られる乱数値を覗く。
     *
     * @return [0,1) の乱数値
     */
    public double peekNextRandom() {
        return randomValueFor(seed, decisionIndex);
    }

    /** @return 個体固有シード */
    public long seed() {
        return seed;
    }

    /** @return 次に使用される決定インデックス */
    public long decisionIndex() {
        return decisionIndex;
    }

    /**
     * 決定インデックスを設定する（永続化からの復元用）。
     *
     * @param decisionIndex 決定インデックス（0 以上）
     */
    public void setDecisionIndex(long decisionIndex) {
        if (decisionIndex < 0) {
            throw new IllegalArgumentException("decisionIndex must be >= 0 but was " + decisionIndex);
        }
        this.decisionIndex = decisionIndex;
    }

    /**
     * {@code (seed, index)} から乱数値を純粋関数として導出する。
     *
     * <p>副作用がないため、研究ログに記録した {@code seed} と {@code decisionIndex} から
     * 後日まったく同じ値を再計算して検証できる。</p>
     *
     * @param seed  個体固有シード
     * @param index 決定インデックス
     * @return [0,1) の乱数値
     */
    public static double randomValueFor(long seed, long index) {
        long mixed = AiWolfParameters.mix64(seed ^ AiWolfParameters.mix64(index));
        return (mixed >>> 11) * UNIT_SCALE;
    }

    @Override
    public String toString() {
        return "WolfRandomProvider{seed=" + seed + ", decisionIndex=" + decisionIndex + '}';
    }
}
