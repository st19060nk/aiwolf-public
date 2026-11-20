package jp.ac.naist.hai.aiwolf.memory;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * 1 つの {@link SpatialRegion} に対する場所記憶。
 *
 * <p>「洞窟 A で大ダメージを受けた」「ここで飼い主に助けられた」といった経験を
 * 領域単位で蓄積し、行動方策の {@code locationFear} 特徴量として供給する。</p>
 *
 * <p>スレッド安全ではない。</p>
 */
public final class RegionMemory {

    private double fearValue;
    private double positiveValue;
    private int visitCount;
    private long lastVisitedWorldTime;

    /** 何も経験していない状態の場所記憶を生成する。 */
    public RegionMemory() {
        this(0.0, 0.0, 0, 0L);
    }

    /**
     * 永続化からの復元用コンストラクタ。
     *
     * @param fearValue            恐怖値 [0,1]
     * @param positiveValue        安心値 [0,1]
     * @param visitCount           訪問回数
     * @param lastVisitedWorldTime 最終訪問ワールド時刻
     */
    public RegionMemory(double fearValue, double positiveValue, int visitCount, long lastVisitedWorldTime) {
        this.fearValue = MathSupport.clamp01(fearValue);
        this.positiveValue = MathSupport.clamp01(positiveValue);
        this.visitCount = Math.max(0, visitCount);
        this.lastVisitedWorldTime = lastVisitedWorldTime;
    }

    /**
     * 出来事を反映する。負の出来事は恐怖値を、正の出来事は安心値を引き上げる。
     *
     * <p>更新は指数移動平均で行うため、1 回の出来事で場所の評価が振り切れることはない。
     * 強度（arousal）が高いほど大きく動く。</p>
     *
     * @param valence 快・不快の向き [-1,+1]
     * @param arousal 強度 [0,1]
     */
    public void recordOutcome(double valence, double arousal) {
        double rate = MathSupport.clamp01(AiWolfParameters.SPATIAL_LEARNING_RATE * MathSupport.clamp01(arousal));
        double magnitude = Math.abs(MathSupport.clipSigned(valence));
        if (valence < 0.0) {
            fearValue = MathSupport.clamp01(MathSupport.exponentialMovingAverage(fearValue, magnitude, rate));
        } else if (valence > 0.0) {
            positiveValue = MathSupport.clamp01(
                    MathSupport.exponentialMovingAverage(positiveValue, magnitude, rate));
        }
    }

    /**
     * 訪問を記録する。
     *
     * @param worldTime 現在のワールド時刻
     */
    public void recordVisit(long worldTime) {
        visitCount++;
        lastVisitedWorldTime = worldTime;
    }

    /**
     * 行動方策へ渡す実効恐怖値を返す。安心の経験があれば恐怖は相殺される。
     *
     * @return [0,1] の実効恐怖値
     */
    public double effectiveFear() {
        return MathSupport.clamp01(fearValue - AiWolfParameters.SPATIAL_POSITIVE_OFFSET * positiveValue);
    }

    /**
     * 容量超過時の剪定に用いる重要度を返す。
     *
     * @return 重要度
     */
    public double importance() {
        return Math.max(fearValue, positiveValue);
    }

    /** @return 恐怖値 */
    public double fearValue() {
        return fearValue;
    }

    /** @return 安心値 */
    public double positiveValue() {
        return positiveValue;
    }

    /** @return 訪問回数 */
    public int visitCount() {
        return visitCount;
    }

    /** @return 最終訪問ワールド時刻 */
    public long lastVisitedWorldTime() {
        return lastVisitedWorldTime;
    }

    @Override
    public String toString() {
        return "RegionMemory{fear=" + MathSupport.round(fearValue, 3)
                + ", positive=" + MathSupport.round(positiveValue, 3)
                + ", visits=" + visitCount + '}';
    }
}
