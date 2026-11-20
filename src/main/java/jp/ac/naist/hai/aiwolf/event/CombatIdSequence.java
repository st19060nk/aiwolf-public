package jp.ac.naist.hai.aiwolf.event;

import java.util.Objects;

/**
 * 戦闘 ID の採番。Minecraft に依存しない。
 *
 * <p><strong>再起動を跨いで一意でなければならない。</strong>カウンタが 0 へ戻ると、
 * 同じ個体の別セッションの戦闘が {@code <wolfId>#c0} という同じ ID を持つ。
 * 研究解析では combatId を戦闘の同一性の根拠に使うため、
 * 無関係な 2 つの戦闘が 1 件へ結合され、「1 戦闘から 2 件のイベントが出た」という
 * 存在しない重複発火として現れる。</p>
 *
 * <p>採番は連番のままにしてある。時刻やランダム値を混ぜると一意にはなるが、
 * 同じ実験を再実行したときに ID が変わり、ログ同士を突き合わせられなくなる。
 * カウンタを永続化するほうが、一意性と再現性の両方を満たせる。</p>
 */
public final class CombatIdSequence {

    private final String wolfId;
    private long next;

    /**
     * 採番器を生成する。
     *
     * @param wolfId 個体 ID
     */
    public CombatIdSequence(String wolfId) {
        this.wolfId = Objects.requireNonNull(wolfId, "wolfId");
    }

    /**
     * 次の戦闘 ID を発行する。
     *
     * @return 戦闘 ID
     */
    public String next() {
        return wolfId + "#c" + (next++);
    }

    /** @return 次に発行される連番（永続化用） */
    public long sequence() {
        return next;
    }

    /**
     * 永続化した連番を復元する。
     *
     * <p>現在値より小さい値では巻き戻さない。復元の順序が前後しても
     * 発行済みの ID を二度と使わないようにするためである。</p>
     *
     * @param sequence 保存されていた連番
     */
    public void restore(long sequence) {
        this.next = Math.max(this.next, Math.max(0L, sequence));
    }
}
