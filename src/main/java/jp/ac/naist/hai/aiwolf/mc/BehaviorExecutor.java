package jp.ac.naist.hai.aiwolf.mc;

import java.util.List;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.Vector3;
import jp.ac.naist.hai.aiwolf.memory.AvoidanceGeometry;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * 選ばれたマクロ行動を、実際の移動と視線へ落とし込む。
 *
 * <p><strong>バニラの機能を再実装しない。</strong>経路探索は
 * {@link EntityNavigation} に任せ、攻撃そのものはバニラの近接戦闘ゴールに任せる
 * （{@link BehaviorType#DEFEND_PLAYER} は攻撃対象を設定するだけで、殴るのはバニラ）。
 * 座って待機する挙動もバニラの sit をそのまま使う。</p>
 *
 * <p>ここには意思決定が一切入らない。「何をするか」は決まっており、その実行だけを担う。</p>
 */
public final class BehaviorExecutor {

    private BehaviorExecutor() {
        throw new AssertionError("utility class");
    }

    /**
     * 行動を実行する。移動先を新たに指示するのは、行動が変わったときと
     * 経路が尽きたときだけである。
     *
     * @param wolf     対象のオオカミ
     * @param behavior 実行する行動
     * @return 移動指示を出したら true
     */
    public static boolean apply(WolfEntity wolf, BehaviorType behavior) {
        return apply(wolf, behavior, null);
    }

    /**
     * 行動を実行する。
     *
     * @param wolf       対象のオオカミ
     * @param behavior   実行する行動
     * @param controller 個体のランタイム。空間記憶を参照する行動で使う（{@code null} 可）
     * @return 移動指示を出したら true
     */
    public static boolean apply(WolfEntity wolf, BehaviorType behavior, WolfAiController controller) {
        LivingEntity owner = wolf.getOwner();
        EntityNavigation navigation = wolf.getNavigation();

        if (owner != null && owner.isAlive() && owner.getWorld() == wolf.getWorld()) {
            wolf.getLookControl().lookAt(owner, 10.0f, wolf.getMaxLookPitchChange());
        }

        return switch (behavior) {
            case FOLLOW_CLOSE -> follow(wolf, owner, AiWolfParameters.FOLLOW_CLOSE_DISTANCE,
                    AiWolfParameters.SPEED_APPROACH);
            case FOLLOW_NORMAL -> follow(wolf, owner, AiWolfParameters.FOLLOW_NORMAL_DISTANCE,
                    AiWolfParameters.SPEED_FOLLOW);
            case APPROACH_PLAYER -> approach(wolf, owner);
            case EXPLORE_NEARBY -> explore(wolf);
            case DEFEND_PLAYER -> defend(wolf);
            case RETREAT -> retreat(wolf);
            case AVOID_LOCATION -> avoidLocation(wolf, controller);
            case REST_NEAR_PLAYER -> rest(wolf, navigation);
        };
    }

    /**
     * 指定距離より離れていれば飼い主へ近づく。届いていれば何もしない。
     */
    private static boolean follow(WolfEntity wolf, LivingEntity owner, double keepDistance, double speed) {
        if (owner == null || owner.getWorld() != wolf.getWorld()) {
            return false;
        }
        if (wolf.distanceTo(owner) <= keepDistance) {
            wolf.getNavigation().stop();
            return false;
        }
        return wolf.getNavigation().startMovingTo(owner, speed);
    }

    /**
     * 飼い主へ能動的に接近する。追従より近くまで寄る。
     */
    private static boolean approach(WolfEntity wolf, LivingEntity owner) {
        if (owner == null || owner.getWorld() != wolf.getWorld()) {
            return false;
        }
        if (wolf.distanceTo(owner) <= 1.5) {
            wolf.getNavigation().stop();
            return false;
        }
        return wolf.getNavigation().startMovingTo(owner, AiWolfParameters.SPEED_APPROACH);
    }

    /**
     * 近傍をひとりで探索する。目的地はバニラの経路コスト評価を使って選ぶ。
     */
    private static boolean explore(WolfEntity wolf) {
        Vec3d target = NoPenaltyTargeting.find(wolf,
                AiWolfParameters.EXPLORE_HORIZONTAL_RANGE, AiWolfParameters.TARGET_VERTICAL_RANGE);
        if (target == null) {
            return false;
        }
        return wolf.getNavigation().startMovingTo(target.x, target.y, target.z,
                AiWolfParameters.SPEED_EXPLORE);
    }

    /**
     * 最も近い敵性 MOB を攻撃対象に設定する。実際に殴るのはバニラの近接戦闘ゴール。
     */
    private static boolean defend(WolfEntity wolf) {
        LivingEntity current = wolf.getTarget();
        if (current != null && current.isAlive()) {
            return false;
        }
        HostileEntity nearest = nearestHostile(wolf);
        if (nearest == null) {
            return false;
        }
        wolf.setTarget(nearest);
        return true;
    }

    /**
     * 脅威から離れる。攻撃対象を解除してからでないと、バニラの戦闘ゴールに引き戻される。
     */
    private static boolean retreat(WolfEntity wolf) {
        wolf.setTarget(null);
        HostileEntity threat = nearestHostile(wolf);
        Vec3d away = threat != null
                ? FuzzyTargeting.findFrom(wolf, AiWolfParameters.RETREAT_HORIZONTAL_RANGE,
                        AiWolfParameters.TARGET_VERTICAL_RANGE, threat.getPos())
                : FuzzyTargeting.find(wolf, AiWolfParameters.RETREAT_HORIZONTAL_RANGE,
                        AiWolfParameters.TARGET_VERTICAL_RANGE);
        if (away == null) {
            return false;
        }
        return wolf.getNavigation().startMovingTo(away.x, away.y, away.z,
                AiWolfParameters.SPEED_RETREAT);
    }

    /**
     * 恐怖記憶のある領域から離れる。
     *
     * <p><strong>現在地から遠ざかるだけでは不十分である。</strong>それは退避（RETREAT）と
     * 区別できず、「空間記憶が行動に結び付いている」という研究上の主張の証拠にならない。
     * 恐怖を記憶した領域の<strong>中心</strong>を求め、そこから離れる方向へ候補を出して、
     * 経路探索が通る候補を採用する。</p>
     *
     * <p>候補は {@link AvoidanceGeometry} が左右へ振った複数を返すので、
     * 壁や崖で正面が塞がっていても迂回できる。中心から遠ざかる候補が
     * どれも歩けない場合にかぎり、通常の退避へ落とす。</p>
     */
    private static boolean avoidLocation(WolfEntity wolf, WolfAiController controller) {
        SpatialRegion feared = controller == null ? null : mostFearedNearbyRegion(wolf, controller);
        if (feared == null) {
            return retreat(wolf);
        }

        Vector3 center = AvoidanceGeometry.regionCenter(feared);
        Vector3 position = new Vector3(wolf.getX(), wolf.getY(), wolf.getZ());
        for (Vector3 candidate : AvoidanceGeometry.escapeCandidates(position, center,
                AiWolfParameters.RETREAT_HORIZONTAL_RANGE)) {
            // 経路探索が通る地点へ丸めたうえで、本当に中心から遠ざかっているか確認する。
            Vec3d resolved = FuzzyTargeting.findTo(wolf, AiWolfParameters.RETREAT_HORIZONTAL_RANGE,
                    AiWolfParameters.TARGET_VERTICAL_RANGE,
                    new Vec3d(candidate.x(), candidate.y(), candidate.z()));
            if (resolved == null) {
                continue;
            }
            Vector3 target = new Vector3(resolved.x, resolved.y, resolved.z);
            if (!AvoidanceGeometry.movesAwayFrom(position, center, target)) {
                continue;
            }
            if (wolf.getNavigation().startMovingTo(resolved.x, resolved.y, resolved.z,
                    AiWolfParameters.SPEED_RETREAT)) {
                return true;
            }
        }
        // 中心から離れる経路が見つからないときだけ、通常の退避へ落とす。
        return retreat(wolf);
    }

    /**
     * 近傍でもっとも恐れられている領域を返す。
     *
     * <p>現在いる領域が怖い場合はそこを、そうでなければ隣接領域のうち
     * もっとも恐怖値が高いものを返す。隣も見るのは、領域の境界に立っているときに
     * 「すぐ隣の怖い場所」から離れられるようにするためである。</p>
     *
     * @param wolf       対象のオオカミ
     * @param controller 個体のランタイム
     * @return 恐れられている領域。閾値を超えるものがなければ {@code null}
     */
    private static SpatialRegion mostFearedNearbyRegion(WolfEntity wolf, WolfAiController controller) {
        SpatialRegion current = ContextBuilder.currentRegion(wolf);
        SpatialRegion worst = null;
        double worstFear = AiWolfParameters.DANGEROUS_REGION_THRESHOLD;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                SpatialRegion candidate = new SpatialRegion(current.dimension(),
                        current.regionX() + dx, current.regionY(), current.regionZ() + dz);
                double fear = controller.agent().regionFear(candidate);
                if (fear >= worstFear) {
                    worstFear = fear;
                    worst = candidate;
                }
            }
        }
        return worst;
    }

    /**
     * その場で休息する。移動を止めるだけで、座らせはしない
     * （座り状態はプレイヤーが右クリックで制御するものなので奪わない）。
     */
    private static boolean rest(WolfEntity wolf, EntityNavigation navigation) {
        navigation.stop();
        wolf.setTarget(null);
        return false;
    }

    /**
     * 最も近い敵性 MOB を返す。
     *
     * @param wolf 対象のオオカミ
     * @return 最近接の敵。いなければ {@code null}
     */
    private static HostileEntity nearestHostile(WolfEntity wolf) {
        double radius = AiWolfParameters.HOSTILE_SCAN_RADIUS;
        List<HostileEntity> hostiles = wolf.getWorld().getEntitiesByClass(HostileEntity.class,
                Box.of(wolf.getPos(), radius * 2.0, radius * 2.0, radius * 2.0),
                hostile -> hostile.isAlive());
        HostileEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (HostileEntity hostile : hostiles) {
            double distance = wolf.squaredDistanceTo(hostile);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = hostile;
            }
        }
        return nearest;
    }
}
