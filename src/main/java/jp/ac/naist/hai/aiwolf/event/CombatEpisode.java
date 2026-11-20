package jp.ac.naist.hai.aiwolf.event;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 1 回の戦闘（combat episode）の記録と、その結末の判定。Minecraft に依存しない。
 *
 * <p><strong>なぜ episode 単位で管理するのか。</strong>「攻撃対象が変わったら戦闘が終わった」
 * という判定では、Mob A から Mob B へ狙いが移った瞬間を戦闘成功と誤認しうる。
 * DEFEND_SUCCESS や PLAYER_PROTECTED_WOLF は学習済み選好へ直接入るため、
 * 誤検出はそのまま研究結果の誤りになる。相手ごとに episode を開き、
 * 「誰が誰を攻撃し、相手がどうなり、オオカミが生きているか」を明示的に追う。</p>
 *
 * <h2>成功条件</h2>
 * <dl>
 *   <dt>{@link WolfEventType#PLAYER_PROTECTED_WOLF}</dt>
 *   <dd>オオカミがこの敵からダメージを受け、<strong>その後に</strong>飼い主がこの敵を
 *       少なくとも一度攻撃し、敵が死亡または脅威解除され、オオカミが生存した場合。
 *       飼い主が近くにいただけでは成立しない。
 *       救援後にオオカミが同じ敵から再び被弾しても成立は取り消されない。</dd>
 *   <dt>{@link WolfEventType#DEFEND_SUCCESS}</dt>
 *   <dd>飼い主がこの敵から攻撃を受けている状態でオオカミがこの敵へ介入し、
 *       敵が死亡または脅威解除され、オオカミが生存した場合。</dd>
 *   <dt>{@link WolfEventType#PLAYER_AND_WOLF_COMBAT}</dt>
 *   <dd>双方が関与して敵が消えたが、上記のどちらの型にも当てはまらない場合。</dd>
 * </dl>
 *
 * <p><strong>1 つの episode からは最優先の 1 件だけを発火する。</strong>
 * 同じ戦闘で複数のイベントを出すと、state・記憶・選好の 3 経路すべてに
 * 重複して入り、1 回の戦闘から過剰に学習してしまう。</p>
 *
 * <p>スレッド安全ではない。</p>
 */
public final class CombatEpisode {

    private final String combatId;
    private final String hostileId;
    private final String hostileType;
    private final CombatTrigger trigger;
    private final long startTick;
    private final double wolfHealthAtStart;

    private long lastActivityTick;
    private double wolfHealthAtEnd;
    private double wolfDamageTaken;
    private double ownerDamageTaken;
    private long wolfDamagedTick = Long.MIN_VALUE;
    private long ownerAttackedHostileTick = Long.MIN_VALUE;
    private boolean ownerIntervenedAfterWolfDamage;
    private boolean wolfAttackedHostile;
    private boolean hostileKilled;
    private boolean hostileRemoved;
    private boolean wolfSurvived = true;
    private boolean resolved;

    private CombatEpisode(String combatId, String hostileId, String hostileType,
                          CombatTrigger trigger, long startTick, double wolfHealthAtStart) {
        this.combatId = Objects.requireNonNull(combatId, "combatId");
        this.hostileId = Objects.requireNonNull(hostileId, "hostileId");
        this.hostileType = hostileType == null ? "unknown" : hostileType;
        this.trigger = trigger == null ? CombatTrigger.WOLF_TARGETED_HOSTILE : trigger;
        this.startTick = startTick;
        this.lastActivityTick = startTick;
        this.wolfHealthAtStart = wolfHealthAtStart;
        this.wolfHealthAtEnd = wolfHealthAtStart;
    }

    /**
     * 戦闘を開始する。
     *
     * @param combatId          この戦闘の ID（研究ログで追跡するため決定論的に採番する）
     * @param hostileId         相手の識別子
     * @param hostileType       相手の種別 ID
     * @param startTick         開始ワールド時刻
     * @param wolfHealthAtStart 開始時のオオカミの HP 比率
     * @return 戦闘記録
     */
    public static CombatEpisode open(String combatId, String hostileId, String hostileType,
                                     long startTick, double wolfHealthAtStart) {
        return open(combatId, hostileId, hostileType, CombatTrigger.WOLF_TARGETED_HOSTILE,
                startTick, wolfHealthAtStart);
    }

    /**
     * 開始のきっかけを明示して戦闘を開始する。
     *
     * @param combatId          この戦闘の ID
     * @param hostileId         相手の識別子
     * @param hostileType       相手の種別 ID
     * @param trigger           開始のきっかけ
     * @param startTick         開始ワールド時刻
     * @param wolfHealthAtStart 開始時のオオカミの HP 比率
     * @return 戦闘記録
     */
    public static CombatEpisode open(String combatId, String hostileId, String hostileType,
                                     CombatTrigger trigger, long startTick,
                                     double wolfHealthAtStart) {
        return new CombatEpisode(combatId, hostileId, hostileType, trigger,
                startTick, wolfHealthAtStart);
    }

    /**
     * オオカミがこの敵からダメージを受けたことを記録する。
     *
     * @param amount            受けたダメージ量
     * @param healthRatioAfter  被弾後の HP 比率
     * @param tick              発生ワールド時刻
     */
    public void recordWolfDamage(double amount, double healthRatioAfter, long tick) {
        wolfDamageTaken += Math.max(0.0, amount);
        wolfHealthAtEnd = healthRatioAfter;
        wolfDamagedTick = tick;
        lastActivityTick = tick;
    }

    /**
     * 飼い主がこの敵からダメージを受けたことを記録する。
     *
     * @param amount 受けたダメージ量
     * @param tick   発生ワールド時刻
     */
    public void recordOwnerDamage(double amount, long tick) {
        ownerDamageTaken += Math.max(0.0, amount);
        lastActivityTick = tick;
    }

    /**
     * 飼い主がこの敵を攻撃したことを記録する。
     *
     * @param tick 発生ワールド時刻
     */
    public void recordOwnerAttack(long tick) {
        ownerAttackedHostileTick = tick;
        if (wolfDamageTaken > 0.0) {
            // オオカミがすでに傷ついている状態での攻撃は救援である。
            // この事実は一度立てたら二度と降ろさない。降ろすと、救援後も
            // 戦闘が続いた場合——つまり危機的だった戦闘ほど——救援が記録から消える。
            ownerIntervenedAfterWolfDamage = true;
        }
        lastActivityTick = tick;
    }

    /**
     * オオカミがこの敵を攻撃対象にしたことを記録する。
     *
     * @param tick 発生ワールド時刻
     */
    public void recordWolfAttack(long tick) {
        wolfAttackedHostile = true;
        lastActivityTick = tick;
    }

    /**
     * 敵がいなくなったことを記録する。
     *
     * @param killed オオカミまたは飼い主によって倒されたなら true。逃走・デスポーンなら false
     * @param tick   発生ワールド時刻
     */
    public void markHostileRemoved(boolean killed, long tick) {
        hostileRemoved = true;
        hostileKilled = killed;
        lastActivityTick = tick;
    }

    /**
     * オオカミが死亡したことを記録する。死亡した戦闘は成功として扱わない。
     */
    public void markWolfDead() {
        wolfSurvived = false;
    }

    /**
     * 最後の活動からの経過で戦闘が立ち消えになったかを返す。
     *
     * @param currentTick  現在のワールド時刻
     * @param timeoutTicks 無活動とみなす経過 tick 数
     * @return 立ち消えなら true
     */
    public boolean isExpired(long currentTick, long timeoutTicks) {
        return currentTick - lastActivityTick >= timeoutTicks;
    }

    /** @return すでに結末を判定済みなら true */
    public boolean isResolved() {
        return resolved;
    }

    /**
     * 結末を判定し、発火すべきイベントを返す。
     *
     * <p>1 回しか結果を返さない。2 回目以降は空を返すので、同じ戦闘から
     * 重複してイベントが出ることはない。</p>
     *
     * @return 発火すべきイベント種別。該当がなければ {@link Optional#empty()}
     */
    public Optional<WolfEventType> resolve() {
        if (resolved) {
            return Optional.empty();
        }
        resolved = true;

        if (!wolfSurvived || !hostileRemoved) {
            return Optional.empty();
        }

        // 優先順位つきで 1 件だけ返す。同じ戦闘から複数出すと 1 回の経験から
        // state・記憶・選好の 3 経路すべてへ重複して入り、過剰に学習する。
        if (playerProtectedWolf()) {
            return Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF);
        }
        if (defendSuccess()) {
            return Optional.of(WolfEventType.DEFEND_SUCCESS);
        }
        if (wolfAttackedHostile && ownerAttackedHostileTick != Long.MIN_VALUE) {
            return Optional.of(WolfEventType.PLAYER_AND_WOLF_COMBAT);
        }
        return Optional.empty();
    }

    /**
     * 飼い主に救われたか。
     *
     * <p>オオカミが被弾していること、その<strong>あとに</strong>飼い主がこの敵を
     * 少なくとも一度攻撃していること、その敵が倒されたことをすべて要求する。
     * 順序を見るのは「たまたま飼い主が先に殴っていた敵に後からオオカミが噛まれた」
     * ケースを救出として数えないためである。</p>
     *
     * <p><strong>時刻の比較ではなく、事実として持つ。</strong>以前は
     * 「飼い主の攻撃時刻 ≧ 最後の被弾時刻」で判定していたが、
     * 被弾 → 救援 → <em>再び被弾</em> → 撃破という流れでは
     * 最後の被弾が救援より後に来るため、実際に救われた戦闘が不成立になっていた。
     * 救援後も攻撃され続けるのは戦闘が長引いた場合、すなわち劣勢だった場合なので、
     * <em>危機的な救援ほど記録から落ちる</em>という偏った欠測になっていた。</p>
     *
     * @return 成立していれば true
     */
    private boolean playerProtectedWolf() {
        return wolfDamageTaken > 0.0
                && ownerIntervenedAfterWolfDamage
                && hostileKilled;
    }

    /**
     * 飼い主を守り切ったか。
     *
     * @return 成立していれば true
     */
    private boolean defendSuccess() {
        return ownerDamageTaken > 0.0 && wolfAttackedHostile && hostileKilled;
    }

    /**
     * 飼い主が関与した記録があるか。
     *
     * <p>被弾も攻撃も含む。戦闘が飼い主の被弾から始まった場合、飼い主が一度も
     * 攻撃していなくても「関与した」とみなす。</p>
     *
     * @return 関与していれば true
     */
    public boolean ownerInvolved() {
        return ownerDamageTaken > 0.0 || ownerAttackedHostileTick != Long.MIN_VALUE;
    }

    /** @return 戦闘 ID */
    public String combatId() {
        return combatId;
    }

    /** @return 相手の識別子 */
    public String hostileId() {
        return hostileId;
    }

    /** @return 相手の種別 ID */
    public String hostileType() {
        return hostileType;
    }

    /** @return 戦闘が始まったきっかけ */
    public CombatTrigger trigger() {
        return trigger;
    }

    /** @return 開始ワールド時刻 */
    public long startTick() {
        return startTick;
    }

    /** @return 最後に活動があったワールド時刻 */
    public long lastActivityTick() {
        return lastActivityTick;
    }

    /** @return オオカミが受けた累積ダメージ */
    public double wolfDamageTaken() {
        return wolfDamageTaken;
    }

    /** @return 飼い主が受けた累積ダメージ */
    public double ownerDamageTaken() {
        return ownerDamageTaken;
    }

    /** @return 開始時のオオカミの HP 比率 */
    public double wolfHealthAtStart() {
        return wolfHealthAtStart;
    }

    /** @return 終了時のオオカミの HP 比率 */
    public double wolfHealthAtEnd() {
        return wolfHealthAtEnd;
    }

    /** @return 飼い主がこの敵を攻撃したか */
    public boolean ownerParticipated() {
        return ownerAttackedHostileTick != Long.MIN_VALUE;
    }

    /**
     * 飼い主がオオカミの被弾よりあとにこの敵を攻撃したか。
     *
     * <p>一度成立したら、その後オオカミが同じ敵から何度被弾しても降りない。
     * 「この戦闘で救援に入った」という事実は失われないためである。</p>
     *
     * @return 救援として介入していれば true
     */
    public boolean ownerIntervenedAfterWolfDamage() {
        return ownerIntervenedAfterWolfDamage;
    }

    /** @return オオカミがこの敵を攻撃対象にしたか */
    public boolean wolfParticipated() {
        return wolfAttackedHostile;
    }

    /** @return 敵が倒されたか */
    public boolean hostileKilled() {
        return hostileKilled;
    }

    /** @return 敵がいなくなったか */
    public boolean hostileRemoved() {
        return hostileRemoved;
    }

    /** @return オオカミが生存したか */
    public boolean wolfSurvived() {
        return wolfSurvived;
    }

    /**
     * 研究ログへ添えるメタデータを返す。
     *
     * @return combatId / hostileType / hostileId を含むマップ
     */
    public List<String> describe() {
        return List.of(
                "combatId=" + combatId,
                "hostile=" + hostileType + "/" + hostileId,
                "trigger=" + trigger.id(),
                "wolfDamage=" + wolfDamageTaken,
                "ownerDamage=" + ownerDamageTaken,
                "ownerParticipated=" + ownerParticipated(),
                "ownerIntervenedAfterWolfDamage=" + ownerIntervenedAfterWolfDamage,
                "wolfParticipated=" + wolfAttackedHostile,
                "hostileKilled=" + hostileKilled,
                "wolfSurvived=" + wolfSurvived);
    }
}
