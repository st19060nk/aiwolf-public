package jp.ac.naist.hai.aiwolf.event;

/**
 * ダメージを「誰のせいか」へ帰属させる規則。Minecraft に依存しない。
 *
 * <p><strong>矢は矢のせいではない。</strong>Skeleton が射た矢が当たったとき、
 * 直接ぶつかったのは矢だが、戦闘の相手は Skeleton である。矢へ帰属させると、
 * 矢 1 本ごとに別の戦闘エピソードが開き、そのどれもが「相手が倒された」ことを
 * 知らないまま時間切れで消える。Skeleton との戦闘は研究データから丸ごと失われる。</p>
 *
 * <p>Minecraft のダメージ源は「原因となった主体」と「直接ぶつかったもの」を
 * 別々に持っている。矢なら前者が射手、後者が矢。近接攻撃なら両者が同じ。
 * 爆発や溶岩のように主体がいない場合だけ後者へ落とす。</p>
 *
 * <p>この規則をここに 1 つだけ置いておくのは、近接と遠距離で別々の判定を
 * 書くと必ずどちらかが取り残されるためである。</p>
 */
public final class DamageAttribution {

    private DamageAttribution() {
        throw new AssertionError("utility class");
    }

    /**
     * ダメージの原因となった主体を選ぶ。
     *
     * @param <T>          主体の型（実機では {@code Entity}）
     * @param attacker     原因となった主体。矢なら射手。いなければ {@code null}
     * @param directSource 直接ぶつかったもの。矢なら矢自身。なければ {@code null}
     * @return 帰属先。どちらも無ければ {@code null}
     */
    public static <T> T causingAttacker(T attacker, T directSource) {
        return attacker != null ? attacker : directSource;
    }

    /**
     * 研究ログへ書く加害者の識別子を選ぶ。
     *
     * <p><strong>プレイヤーだけは必ず匿名 ID を使う。</strong>Mob の UUID は
     * そのワールド限りの内部識別子で、戦闘エピソードと突き合わせるために必要だが、
     * プレイヤーの UUID は Minecraft アカウントに紐づく個人情報である。
     * 短縮しても——先頭 8 文字でも——同一人物の特定に使えるため、
     * 部分文字列であっても研究データへ残してはならない。</p>
     *
     * @param player       加害者がプレイヤーか
     * @param anonymisedId 匿名化済みの研究 ID（プレイヤーの場合に使う）
     * @param mobShortId   Mob の短縮識別子（プレイヤー以外の場合に使う）
     * @return 研究ログへ書く識別子
     */
    public static String attackerId(boolean player, String anonymisedId, String mobShortId) {
        return player ? anonymisedId : mobShortId;
    }

    /**
     * 帰属先が戦闘の相手として扱えるかを返す。
     *
     * @param attributed     帰属先（{@code null} 可）
     * @param hostile        帰属先が敵性 MOB か
     * @param selfInflicted  帰属先が被害者自身か（サボテン・自爆など）
     * @return 戦闘の相手として記録してよければ true
     */
    public static boolean isCombatOpponent(Object attributed, boolean hostile, boolean selfInflicted) {
        return attributed != null && hostile && !selfInflicted;
    }
}
