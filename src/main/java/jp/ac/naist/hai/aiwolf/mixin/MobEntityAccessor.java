package jp.ac.naist.hai.aiwolf.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;

/**
 * {@link MobEntity} の {@code goalSelector} へアクセスするためのアクセサ。
 *
 * <p>Fabric API には既存エンティティへ Goal を追加する公式 API がないため、
 * ここだけ Mixin を使う。振る舞いの書き換えは一切行わず、フィールドを読むだけなので
 * 他 MOD との競合が起きにくい。<strong>本 MOD の Mixin はこれ 1 つだけである。</strong></p>
 */
@Mixin(MobEntity.class)
public interface MobEntityAccessor {

    /**
     * 通常の行動を制御する {@link GoalSelector} を返す。
     *
     * @return ゴールセレクタ
     */
    @Accessor("goalSelector")
    GoalSelector aiwolf$getGoalSelector();
}
