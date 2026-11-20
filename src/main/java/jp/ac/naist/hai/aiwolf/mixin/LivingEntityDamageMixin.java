package jp.ac.naist.hai.aiwolf.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;

import jp.ac.naist.hai.aiwolf.mc.DamageObserver;

/**
 * 被弾を観測するための Mixin。<strong>ダメージ処理そのものは一切変更しない。</strong>
 *
 * <h2>なぜ Fabric API では足りないのか</h2>
 *
 * <p>Fabric の {@code ServerLivingEntityEvents} には damage 系のイベントが 2 つあるが、
 * どちらも研究に必要な値を与えてくれない。</p>
 *
 * <ul>
 *   <li>{@code ALLOW_DAMAGE} は「防具やその他の軽減が適用される前」に発火する。
 *       攻撃の威力は分かるが、実際に失われた HP は分からない。</li>
 *   <li>{@code AFTER_DAMAGE} の {@code damageTaken} は、公式の Javadoc に
 *       「<em>防具とエンチャントによる軽減は含まない</em>」と明記されている。
 *       盾と凍結だけが反映された中間値で、実際の HP 損失ではない。</li>
 *   <li>そのうえ {@code AFTER_DAMAGE} は「<em>その damage で entity が死亡した場合は
 *       発火しない</em>」（Fabric 側が {@code @At("TAIL")} で注入しているため、
 *       死亡経路が TAIL に到達しない）。<strong>致死攻撃の帰属が丸ごと落ちる。</strong></li>
 *   <li>Absorption（金のリンゴの黄色いハート）の増減は、どちらのイベントも報告しない。</li>
 * </ul>
 *
 * <p>この 2 つを組み合わせても致死攻撃では後者が来ないため、前後の差が取れない。
 * したがって観測専用の Mixin が要る。</p>
 *
 * <h2>何をしているか</h2>
 *
 * <p>{@code damage} の入口で HP と Absorption を控え、出口で読み直して差を取る。
 * Fabric が {@code @At("TAIL")} を使うのに対し、こちらは {@code @At("RETURN")} を使う。
 * TAIL は最後の return 命令 1 か所だけを指すが、RETURN はすべての return を指すので、
 * 死亡して途中で返る経路も捉えられる。</p>
 *
 * <p>戻り値を書き換えず、キャンセルもせず、フィールドも触らない。読むだけである。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

    @Unique
    private float aiwolf$healthBefore;

    @Unique
    private float aiwolf$absorptionBefore;

    /**
     * ダメージ処理の入口で、適用前の耐久を控える。
     *
     * @param source ダメージ源
     * @param amount 攻撃の威力（軽減前）
     * @param info   呼び出し情報。変更しない
     */
    @Inject(method = "damage", at = @At("HEAD"))
    private void aiwolf$captureHealthBefore(DamageSource source, float amount,
                                            CallbackInfoReturnable<Boolean> info) {
        LivingEntity self = (LivingEntity) (Object) this;
        aiwolf$healthBefore = self.getHealth();
        aiwolf$absorptionBefore = self.getAbsorptionAmount();
    }

    /**
     * ダメージ処理の出口で、適用後の耐久を読んで観測結果を渡す。
     *
     * <p>すべての return を対象にするので、この攻撃で死亡した場合も呼ばれる。</p>
     *
     * @param source ダメージ源
     * @param amount 攻撃の威力（軽減前）
     * @param info   呼び出し情報。変更しない
     */
    @Inject(method = "damage", at = @At("RETURN"))
    private void aiwolf$reportHealthLoss(DamageSource source, float amount,
                                         CallbackInfoReturnable<Boolean> info) {
        if (!Boolean.TRUE.equals(info.getReturnValue())) {
            // ダメージが実際には適用されなかった（無敵時間・完全無効など）。
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        DamageObserver.onDamageApplied(self, source,
                aiwolf$healthBefore, aiwolf$absorptionBefore,
                self.getHealth(), self.getAbsorptionAmount());
    }
}
