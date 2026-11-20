package jp.ac.naist.hai.aiwolf.context;

import java.util.EnumMap;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * 行動決定時点のゲーム状況を表す不変スナップショット。
 *
 * <p><strong>Minecraft の型（WolfEntity / ServerWorld / BlockPos 等）を一切含まない。</strong>
 * BehaviorScorer はこのスナップショットのみを参照するため、Minecraft を起動せずに
 * 数万回のシミュレーションを実行して行動分布を検証できる。</p>
 *
 * <p>Minecraft 側からこのスナップショットを構築するのは {@code ContextBuilder}
 * （後続ステップで実装）の責務である。</p>
 *
 * @param ownerPresent           飼い主が近傍に存在するか
 * @param ownerDistance          飼い主との距離 [blocks]（{@code ownerPresent} が false なら無意味）
 * @param wolfHealthRatio        自身の HP 比率 [0,1]
 * @param ownerHealthRatio       飼い主の HP 比率 [0,1]
 * @param hostileMobCount        近傍の敵 MOB 数
 * @param nearestHostileDistance 最近接の敵 MOB までの距離 [blocks]（いなければ大きな値）
 * @param night                  夜であるか
 * @param underground            地下であるか
 * @param ownerMoving            飼い主が移動中であるか
 * @param regionFear             現在 region の恐怖値 [0,1]（空間記憶由来）
 * @param recentCombat           直近の戦闘度合い [0,1]
 * @param ownerUnderAttack       飼い主が攻撃を受けているか
 * @param wolfUnderAttack        自身が攻撃を受けているか
 */
public record WolfContextSnapshot(
        boolean ownerPresent,
        double ownerDistance,
        double wolfHealthRatio,
        double ownerHealthRatio,
        int hostileMobCount,
        double nearestHostileDistance,
        boolean night,
        boolean underground,
        boolean ownerMoving,
        double regionFear,
        double recentCombat,
        boolean ownerUnderAttack,
        boolean wolfUnderAttack) {

    /** 飼い主が存在しないときに用いる十分大きな距離。 */
    public static final double NO_TARGET_DISTANCE = 1_000.0;

    /**
     * 飼い主への近さ {@code exp(-d / OWNER_DISTANCE_SCALE)} を返す。
     *
     * @return [0,1] の近さ。飼い主不在なら 0
     */
    public double nearOwner() {
        if (!ownerPresent) {
            return 0.0;
        }
        return MathSupport.proximity(ownerDistance, AiWolfParameters.OWNER_DISTANCE_SCALE);
    }

    /**
     * 敵 MOB の密度を [0,1] へ正規化して返す。
     *
     * @return 敵密度
     */
    public double hostileDensity() {
        return MathSupport.clamp01(hostileMobCount / AiWolfParameters.HOSTILE_COUNT_SATURATION);
    }

    /**
     * 最近接の敵の切迫度を [0,1] で返す。
     *
     * @return 切迫度
     */
    public double nearestThreat() {
        if (hostileMobCount <= 0) {
            return 0.0;
        }
        return MathSupport.proximity(nearestHostileDistance, AiWolfParameters.NEAREST_HOSTILE_SCALE);
    }

    /**
     * 総合脅威スコアを返す。
     *
     * <p>{@code 0.5 * hostileDensity + 0.3 * nearestThreat + 0.2 * combatState}。
     * 「ゾンビが 3 匹いる」「Creeper がすぐ近くにいる」「現在攻撃を受けている」を
     * 1 つの量にまとめて表現する。</p>
     *
     * @return [0,1] の脅威スコア
     */
    public double threat() {
        double combatState = MathSupport.clamp01(Math.max(recentCombat,
                (wolfUnderAttack || ownerUnderAttack) ? 1.0 : 0.0));
        double raw = AiWolfParameters.THREAT_WEIGHT_HOSTILE_DENSITY * hostileDensity()
                + AiWolfParameters.THREAT_WEIGHT_NEAREST * nearestThreat()
                + AiWolfParameters.THREAT_WEIGHT_COMBAT * combatState;
        return MathSupport.clamp01(raw);
    }

    /**
     * HP 比率を「低下度」[0,1] へ変換する。閾値以上では 0、0 で 1 になる。
     *
     * @param healthRatio HP 比率
     * @return 低下度
     */
    private static double lowHealthFeature(double healthRatio) {
        double threshold = AiWolfParameters.LOW_HEALTH_THRESHOLD;
        if (healthRatio >= threshold) {
            return 0.0;
        }
        return MathSupport.clamp01((threshold - healthRatio) / threshold);
    }

    /**
     * BehaviorScorer が参照する文脈特徴量ベクトルを構築する。
     *
     * @return 特徴量マップ（すべて [0,1]）
     */
    public Map<ContextFeature, Double> toFeatureVector() {
        Map<ContextFeature, Double> features = new EnumMap<>(ContextFeature.class);
        double near = nearOwner();
        features.put(ContextFeature.NEAR_OWNER, near);
        features.put(ContextFeature.OWNER_DISTANCE_FAR, ownerPresent ? 1.0 - near : 1.0);
        features.put(ContextFeature.THREAT, threat());
        features.put(ContextFeature.LOCATION_FEAR, MathSupport.clamp01(regionFear));
        features.put(ContextFeature.OWNER_UNDER_ATTACK, ownerUnderAttack ? 1.0 : 0.0);
        features.put(ContextFeature.WOLF_UNDER_ATTACK, wolfUnderAttack ? 1.0 : 0.0);
        features.put(ContextFeature.WOLF_HEALTH_LOW, lowHealthFeature(wolfHealthRatio));
        features.put(ContextFeature.OWNER_HEALTH_LOW, ownerPresent ? lowHealthFeature(ownerHealthRatio) : 0.0);
        features.put(ContextFeature.NIGHT, night ? 1.0 : 0.0);
        features.put(ContextFeature.UNDERGROUND, underground ? 1.0 : 0.0);
        features.put(ContextFeature.OWNER_MOVING, ownerMoving ? 1.0 : 0.0);
        features.put(ContextFeature.RECENT_COMBAT, MathSupport.clamp01(recentCombat));
        features.put(ContextFeature.EMERGENCY_LOW_HEALTH,
                wolfHealthRatio < AiWolfParameters.EMERGENCY_HEALTH_THRESHOLD ? 1.0 : 0.0);
        return features;
    }

    /**
     * 既定値（飼い主が隣にいて脅威なし、満タン HP、昼、地上）のビルダを返す。
     * テストおよびシミュレーションの記述量を減らすためのもの。
     *
     * @return ビルダ
     */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link WolfContextSnapshot} のビルダ。 */
    public static final class Builder {

        private boolean ownerPresent = true;
        private double ownerDistance = 3.0;
        private double wolfHealthRatio = 1.0;
        private double ownerHealthRatio = 1.0;
        private int hostileMobCount = 0;
        private double nearestHostileDistance = NO_TARGET_DISTANCE;
        private boolean night = false;
        private boolean underground = false;
        private boolean ownerMoving = false;
        private double regionFear = 0.0;
        private double recentCombat = 0.0;
        private boolean ownerUnderAttack = false;
        private boolean wolfUnderAttack = false;

        private Builder() {
        }

        /** @param value 飼い主が近傍にいるか @return this */
        public Builder ownerPresent(boolean value) {
            this.ownerPresent = value;
            return this;
        }

        /** @param value 飼い主との距離 [blocks] @return this */
        public Builder ownerDistance(double value) {
            this.ownerDistance = value;
            return this;
        }

        /** @param value 自身の HP 比率 @return this */
        public Builder wolfHealthRatio(double value) {
            this.wolfHealthRatio = value;
            return this;
        }

        /** @param value 飼い主の HP 比率 @return this */
        public Builder ownerHealthRatio(double value) {
            this.ownerHealthRatio = value;
            return this;
        }

        /** @param value 近傍の敵 MOB 数 @return this */
        public Builder hostileMobCount(int value) {
            this.hostileMobCount = value;
            return this;
        }

        /** @param value 最近接の敵までの距離 [blocks] @return this */
        public Builder nearestHostileDistance(double value) {
            this.nearestHostileDistance = value;
            return this;
        }

        /** @param value 夜であるか @return this */
        public Builder night(boolean value) {
            this.night = value;
            return this;
        }

        /** @param value 地下であるか @return this */
        public Builder underground(boolean value) {
            this.underground = value;
            return this;
        }

        /** @param value 飼い主が移動中か @return this */
        public Builder ownerMoving(boolean value) {
            this.ownerMoving = value;
            return this;
        }

        /** @param value 現在 region の恐怖値 @return this */
        public Builder regionFear(double value) {
            this.regionFear = value;
            return this;
        }

        /** @param value 直近戦闘度合い @return this */
        public Builder recentCombat(double value) {
            this.recentCombat = value;
            return this;
        }

        /** @param value 飼い主が攻撃を受けているか @return this */
        public Builder ownerUnderAttack(boolean value) {
            this.ownerUnderAttack = value;
            return this;
        }

        /** @param value 自身が攻撃を受けているか @return this */
        public Builder wolfUnderAttack(boolean value) {
            this.wolfUnderAttack = value;
            return this;
        }

        /** @return 構築したスナップショット */
        public WolfContextSnapshot build() {
            return new WolfContextSnapshot(ownerPresent, ownerDistance, wolfHealthRatio, ownerHealthRatio,
                    hostileMobCount, nearestHostileDistance, night, underground, ownerMoving,
                    regionFear, recentCombat, ownerUnderAttack, wolfUnderAttack);
        }
    }
}
