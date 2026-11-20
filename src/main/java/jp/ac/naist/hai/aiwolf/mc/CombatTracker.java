package jp.ac.naist.hai.aiwolf.mc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.registry.Registries;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.CombatEpisode;
import jp.ac.naist.hai.aiwolf.event.CombatIdSequence;
import jp.ac.naist.hai.aiwolf.event.CombatTrigger;

/**
 * 個体ごとの combat episode の追跡。Minecraft 側の出来事を
 * {@link CombatEpisode}（純 Java）へ流し込む役割だけを持つ。
 *
 * <p>成功条件の判断は {@link CombatEpisode} 側にあり、こちらでは行わない。
 * 判定ロジックを Minecraft から切り離しておくことで、
 * 「飼い主が救ったのか」「オオカミが守ったのか」の判定を単体テストできる。</p>
 *
 * <p>進行中の戦闘そのものは再起動を跨がないため永続化しない。
 * ただし<strong>採番カウンタは永続化する</strong>。再起動で 0 へ戻ると、
 * 同じ個体で {@code <wolfId>#c0} が別セッションに二度現れ、
 * 研究解析で無関係な 2 つの戦闘が 1 件へ結合されてしまう。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class CombatTracker {

    private final Map<UUID, CombatEpisode> openEpisodes = new LinkedHashMap<>();
    private final CombatIdSequence combatIds;

    /**
     * 追跡器を生成する。
     *
     * @param wolfId 個体 ID。combat ID の採番に使う
     */
    public CombatTracker(String wolfId) {
        this.combatIds = new CombatIdSequence(wolfId);
    }

    /**
     * オオカミが敵から被弾したことを記録する。該当の戦闘がなければ開始する。
     *
     * @param hostile          相手
     * @param damage           受けたダメージ量
     * @param healthRatioAfter 被弾後の HP 比率
     * @param tick             発生ワールド時刻
     */
    public void onWolfDamaged(LivingEntity hostile, double damage, double healthRatioAfter, long tick) {
        if (hostile == null) {
            return;
        }
        episodeFor(hostile, CombatTrigger.WOLF_DAMAGED, tick, healthRatioAfter)
                .recordWolfDamage(damage, healthRatioAfter, tick);
    }

    /**
     * 飼い主が敵から被弾したことを記録する。該当の戦闘がなければ開始する。
     *
     * <p><strong>ここで戦闘を開始できることが DEFEND_SUCCESS の前提である。</strong>
     * 自然な Minecraft の戦闘は「敵が飼い主を殴る → 飼い主が被弾 → オオカミが介入」
     * の順に進む。飼い主の被弾時点で戦闘を開かないと、その時点の
     * {@code ownerDamageTaken} が捨てられ、あとからオオカミが介入して敵を倒しても
     * 「飼い主が攻撃を受けていた」という条件が満たされず、防衛成功が一度も成立しない。</p>
     *
     * @param hostile          相手
     * @param damage           受けたダメージ量
     * @param wolfHealthRatio  現在のオオカミの HP 比率（戦闘を新規に開く場合の開始値）
     * @param tick             発生ワールド時刻
     */
    public void onOwnerDamaged(LivingEntity hostile, double damage, double wolfHealthRatio, long tick) {
        if (hostile == null) {
            return;
        }
        episodeFor(hostile, CombatTrigger.OWNER_DAMAGED, tick, wolfHealthRatio)
                .recordOwnerDamage(damage, tick);
    }

    /**
     * 飼い主が敵を攻撃したことを記録する。
     *
     * <p>すでに開いている戦闘にだけ記録する。オオカミが関わっていない敵を
     * 飼い主が殴っただけでは戦闘を開始しない（救出とは無関係なため）。</p>
     *
     * @param hostileUuid 相手の UUID
     * @param tick        発生ワールド時刻
     */
    public void onOwnerAttackedHostile(UUID hostileUuid, long tick) {
        CombatEpisode episode = openEpisodes.get(hostileUuid);
        if (episode != null) {
            episode.recordOwnerAttack(tick);
        }
    }

    /**
     * オオカミが敵を攻撃対象にしたことを記録する。
     *
     * @param hostile         相手
     * @param wolfHealthRatio 現在のオオカミの HP 比率（戦闘を新規に開く場合の開始値）
     * @param tick            発生ワールド時刻
     */
    public void onWolfTargeting(LivingEntity hostile, double wolfHealthRatio, long tick) {
        if (hostile == null) {
            return;
        }
        episodeFor(hostile, CombatTrigger.WOLF_TARGETED_HOSTILE, tick, wolfHealthRatio)
                .recordWolfAttack(tick);
    }

    /**
     * 敵が死亡したことを記録する。
     *
     * <p><strong>役割は戦闘の終了だけである。</strong>誰が攻撃したかは被弾の観測経路が
     * すでに記録している。ここでも飼い主の介入を記録すると、同じ一撃が
     * ダメージ経路と死亡経路の両方から入り、二重に数えられる。</p>
     *
     * @param hostileUuid 相手の UUID
     * @param tick        発生ワールド時刻
     */
    public void onHostileDied(UUID hostileUuid, long tick) {
        CombatEpisode episode = openEpisodes.get(hostileUuid);
        if (episode == null) {
            return;
        }
        episode.markHostileRemoved(true, tick);
    }

    /**
     * オオカミが死亡したことを記録する。死亡した戦闘は成功として扱われない。
     */
    public void onWolfDied() {
        openEpisodes.values().forEach(CombatEpisode::markWolfDead);
    }

    /**
     * 結末が確定した戦闘を取り出して閉じる。
     *
     * <p>敵が消えた戦闘と、無活動で立ち消えになった戦闘の両方を返す。
     * 立ち消えの場合は {@link CombatEpisode#resolve()} が空を返すので、
     * イベントは発火しない。</p>
     *
     * @param currentTick 現在のワールド時刻
     * @return 閉じた戦闘のリスト
     */
    public List<CombatEpisode> drainFinishedEpisodes(long currentTick) {
        List<CombatEpisode> finished = new ArrayList<>();
        Iterator<Map.Entry<UUID, CombatEpisode>> iterator = openEpisodes.entrySet().iterator();
        while (iterator.hasNext()) {
            CombatEpisode episode = iterator.next().getValue();
            boolean expired = episode.isExpired(currentTick, AiWolfParameters.COMBAT_EPISODE_TIMEOUT_TICKS);
            if (episode.hostileRemoved() || !episode.wolfSurvived() || expired) {
                finished.add(episode);
                iterator.remove();
            }
        }
        return finished;
    }

    /** @return 現在追跡中の戦闘数 */
    public int openEpisodeCount() {
        return openEpisodes.size();
    }

    /**
     * その相手との戦闘を追跡中かを返す。
     *
     * <p>敵の死亡をどのオオカミへ伝えるかは、この判定で決める。距離で決めてはならない。
     * 退避や場所回避で敵から離れた個体は、まさにその戦闘の結末を知る必要がある側なのに、
     * 距離で絞ると通知が届かず、その戦闘だけが記録から落ちる。
     * 落ちるのが「逃げた個体の戦闘」に偏るため、測ろうとしている個体差そのものに
     * バイアスがかかる。</p>
     *
     * @param hostileUuid 相手の UUID
     * @return 追跡中なら true
     */
    public boolean tracks(UUID hostileUuid) {
        return openEpisodes.containsKey(hostileUuid);
    }

    /**
     * その相手との戦闘の ID を返す。
     *
     * <p>被弾 1 発ごとの研究ログを、どの戦闘の出来事だったかへ結び付けるために使う。</p>
     *
     * @param hostile 相手（{@code null} 可）
     * @return 戦闘 ID。追跡していなければ {@code null}
     */
    public String combatIdFor(LivingEntity hostile) {
        if (hostile == null) {
            return null;
        }
        CombatEpisode episode = openEpisodes.get(hostile.getUuid());
        return episode == null ? null : episode.combatId();
    }

    /** @return 次に採番される戦闘の連番（永続化用） */
    public long combatSequence() {
        return combatIds.sequence();
    }

    /**
     * 戦闘の連番を復元する（永続化からの読み戻し専用）。
     *
     * <p>復元しないと再起動のたびに {@code #c0} から振り直され、
     * 同じ個体の別セッションの戦闘が同じ ID を持つ。</p>
     *
     * @param sequence 次に採番する連番
     */
    public void restoreCombatSequence(long sequence) {
        combatIds.restore(sequence);
    }

    /**
     * 相手に対応する戦闘を返し、なければ開始する。
     */
    private CombatEpisode episodeFor(LivingEntity hostile, CombatTrigger trigger,
                                     long tick, double wolfHealthRatio) {
        return openEpisodes.computeIfAbsent(hostile.getUuid(), uuid -> {
            pruneIfTooMany();
            return CombatEpisode.open(nextCombatId(), shortId(uuid), typeId(hostile),
                    trigger, tick, wolfHealthRatio);
        });
    }

    private void pruneIfTooMany() {
        while (openEpisodes.size() >= AiWolfParameters.MAX_OPEN_COMBAT_EPISODES) {
            Iterator<UUID> iterator = openEpisodes.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * 決定論的に combat ID を採番する。研究ログで戦闘を突き合わせるため、
     * ランダム UUID は使わない。
     */
    private String nextCombatId() {
        return combatIds.next();
    }

    /**
     * 相手の短い識別子を返す。研究ログと戦闘記録で同じ表記を使うため、ここに 1 つだけ置く。
     *
     * @param uuid 相手の UUID
     * @return 短い識別子
     */
    static String shortId(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    /**
     * 相手の種別 ID を返す。
     *
     * @param entity 相手
     * @return 種別 ID
     */
    static String typeId(Entity entity) {
        return Registries.ENTITY_TYPE.getId(entity.getType()).toString();
    }

    /**
     * 対象が敵性 MOB かを返す。
     *
     * @param entity 対象
     * @return 敵性 MOB なら true
     */
    public static boolean isHostile(Entity entity) {
        return entity instanceof HostileEntity;
    }

    /**
     * オオカミが現在狙っている敵を返す。
     *
     * @param wolf 対象のオオカミ
     * @return 敵性 MOB の対象。いなければ {@code null}
     */
    public static LivingEntity hostileTargetOf(WolfEntity wolf) {
        LivingEntity target = wolf.getTarget();
        return target instanceof HostileEntity ? target : null;
    }
}
