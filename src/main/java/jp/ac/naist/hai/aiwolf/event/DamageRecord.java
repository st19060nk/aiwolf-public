package jp.ac.naist.hai.aiwolf.event;

/**
 * 1 回の物理的な被弾の記録。Minecraft に依存しない。
 *
 * <h2>研究上の「ダメージ」の定義</h2>
 *
 * <p>この研究で扱うダメージは {@link #EFFECTIVE_HEALTH_LOSS} である。</p>
 *
 * <pre>
 * actualDamage = (health + absorption)適用前 − (health + absorption)適用後
 * </pre>
 *
 * <p><strong>攻撃の威力ではなく、実際に失われた耐久である。</strong>防具・エンチャント・
 * Resistance による軽減をすべて反映した後の値になる。研究上の関心は「どれだけ強く
 * 攻撃されたか」ではなく「どれだけ傷ついたか」だからである。同じゾンビの一撃でも、
 * 防具を着けたオオカミとそうでないオオカミでは経験の重さが違う。</p>
 *
 * <p>Absorption（金のリンゴなどで得る黄色いハート）を含めるのは、
 * HP が減らなくても傷つけられた事実は起きているためである。
 * ただし {@code healthBefore} と {@code absorptionBefore} を別々に残してあるので、
 * 解析時に「HP のみの損失」へ組み替えることもできる。</p>
 *
 * @param targetType        被害者の区分（{@code wolf} / {@code owner} / {@code hostile}）
 * @param attackerType      加害者の区分（{@code hostile} / {@code owner} / {@code environment} など）
 * @param attackerId        加害者の短い識別子。加害者がいなければ {@code null}
 * @param directSourceType  直接ぶつかったものの種別 ID。矢なら {@code minecraft:arrow}
 * @param causingSourceType 原因となった主体の種別 ID。矢を射た側
 * @param actualDamage      実際に失われた耐久（health + absorption の減少）
 * @param healthBefore      攻撃前の HP
 * @param healthAfter       攻撃後の HP
 * @param absorptionBefore  攻撃前の Absorption
 * @param absorptionAfter   攻撃後の Absorption
 * @param fatal             この攻撃で死亡したか
 * @param combatId          対応する戦闘の ID。戦闘外なら {@code null}
 * @param worldTime         発生ワールド時刻
 */
public record DamageRecord(String targetType,
                           String attackerType,
                           String attackerId,
                           String directSourceType,
                           String causingSourceType,
                           double actualDamage,
                           double healthBefore,
                           double healthAfter,
                           double absorptionBefore,
                           double absorptionAfter,
                           boolean fatal,
                           String combatId,
                           long worldTime) {

    /** 研究上のダメージの定義。研究ログに毎行書き出し、後から意味を復元できるようにする。 */
    public static final String DAMAGE_DEFINITION = "EFFECTIVE_HEALTH_LOSS";

    /** 被害者・加害者の区分：研究対象のオオカミ。 */
    public static final String ROLE_WOLF = "wolf";

    /** 被害者・加害者の区分：飼い主。 */
    public static final String ROLE_OWNER = "owner";

    /** 被害者・加害者の区分：敵性 MOB。 */
    public static final String ROLE_HOSTILE = "hostile";

    /** 加害者の区分：加害者のいない環境ダメージ。 */
    public static final String ROLE_ENVIRONMENT = "environment";

    /** 加害者の区分：上のどれにも当てはまらないもの。 */
    public static final String ROLE_OTHER = "other";

    /**
     * 前後の耐久から実ダメージを計算する。
     *
     * @param healthBefore     攻撃前の HP
     * @param absorptionBefore 攻撃前の Absorption
     * @param healthAfter      攻撃後の HP
     * @param absorptionAfter  攻撃後の Absorption
     * @return 実際に失われた耐久。回復が挟まった場合は 0 に丸める
     */
    public static double effectiveLoss(double healthBefore, double absorptionBefore,
                                       double healthAfter, double absorptionAfter) {
        double before = healthBefore + absorptionBefore;
        double after = healthAfter + absorptionAfter;
        return Math.max(0.0, before - after);
    }

    /** @return HP のみの損失（Absorption を含めない解析用） */
    public double healthLoss() {
        return Math.max(0.0, healthBefore - healthAfter);
    }

    /** @return Absorption の損失 */
    public double absorptionLoss() {
        return Math.max(0.0, absorptionBefore - absorptionAfter);
    }

    /** @return 加害者のいない環境ダメージなら true */
    public boolean environmental() {
        return ROLE_ENVIRONMENT.equals(attackerType);
    }
}
