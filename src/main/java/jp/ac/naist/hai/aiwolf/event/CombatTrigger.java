package jp.ac.naist.hai.aiwolf.event;

/**
 * 戦闘エピソードが何をきっかけに開始されたか。
 *
 * <p>成功条件の判定そのものには使わない（判定は「誰が何をしたか」の記録に基づく）。
 * 研究ログで戦闘の入り方を分類するために残す。自然な Minecraft の戦闘では
 * 「敵が飼い主を殴る → オオカミが介入」と「敵がオオカミを噛む → 飼い主が介入」で
 * 意味がまるで違うため、どちらから始まったかを後から数えられるようにしておく。</p>
 */
public enum CombatTrigger {

    /** オオカミが敵から被弾して始まった。 */
    WOLF_DAMAGED("wolf_damaged"),

    /** 飼い主が敵から被弾して始まった。 */
    OWNER_DAMAGED("owner_damaged"),

    /** オオカミが敵を攻撃対象にして始まった。 */
    WOLF_TARGETED_HOSTILE("wolf_targeted_hostile");

    private final String id;

    CombatTrigger(String id) {
        this.id = id;
    }

    /** @return 研究ログ用の識別子 */
    public String id() {
        return id;
    }
}
