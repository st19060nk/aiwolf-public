package jp.ac.naist.hai.aiwolf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ダメージ集計のテスト。
 *
 * <p>守っているのは 3 つ。加害者ごとにダメージを分けること、研究イベントの発火頻度を
 * 決定ごと 1 件に保つこと、そして複数の相手から受けたときの代表の選び方が
 * 完全に決定論的であることである。</p>
 */
final class DamageAccumulatorTest {

    private static final double SIGNIFICANT = AiWolfParameters.MIN_SIGNIFICANT_DAMAGE;
    private static final String ZOMBIE = "zombie-a";
    private static final String SKELETON = "skelet-b";

    // ------------------------------------------------- 加害者ごとの区別

    @Test
    @DisplayName("異なる相手からのダメージを合算しない")
    void damageFromDifferentHostilesIsKeptApart() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 2.0, 0.9, 100L);
        accumulator.add(SKELETON, 3.0, 0.7, 120L);

        assertEquals(2, accumulator.pendingSources());
        assertEquals(2.0, accumulator.pendingFrom(ZOMBIE), 1e-9);
        assertEquals(3.0, accumulator.pendingFrom(SKELETON), 1e-9,
                "Zombie の 2 と Skeleton の 3 を「Skeleton から 5」にしてはならない");
        assertEquals(5.0, accumulator.pendingTotal(), 1e-9);
    }

    @Test
    @DisplayName("同じ相手からの複数回はその相手の下でまとまる")
    void repeatedHitsFromOneHostileAccumulateTogether() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 2.0, 0.9, 100L);
        accumulator.add(ZOMBIE, 3.0, 0.7, 120L);
        accumulator.add(SKELETON, 1.0, 0.6, 130L);

        assertEquals(5.0, accumulator.pendingFrom(ZOMBIE), 1e-9);
        assertEquals(1.0, accumulator.pendingFrom(SKELETON), 1e-9);
        assertEquals(2, accumulator.pendingSources());
    }

    @Test
    @DisplayName("環境ダメージは加害者のいない出どころとして分けて扱う")
    void environmentalDamageIsItsOwnSource() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(null, 4.0, 0.8, 100L);
        accumulator.add(ZOMBIE, 1.0, 0.7, 110L);

        assertEquals(4.0, accumulator.pendingFrom(null), 1e-9);
        assertEquals(2, accumulator.pendingSources());
    }

    // ------------------------------------------------- 代表の選択規則

    @Test
    @DisplayName("規則 1：合計ダメージが最も大きい相手を代表にする")
    void largestTotalWins() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 2.0, 0.9, 100L);
        accumulator.add(SKELETON, 3.0, 0.7, 120L);

        DamageAccumulator.Drained drained = accumulator.drain().orElseThrow();

        assertEquals(SKELETON, drained.hostileId());
        assertEquals(3.0, drained.selectedTotal(), 1e-9);
        assertEquals(5.0, drained.totalDamage(), 1e-9, "区間全体の量も残す");
        assertEquals(2, drained.sourceCount());
    }

    @Test
    @DisplayName("規則 2：合計が同点なら最後に攻撃してきた相手を代表にする")
    void mostRecentBreaksTheTie() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 3.0, 0.9, 100L);
        accumulator.add(SKELETON, 3.0, 0.7, 200L);

        assertEquals(SKELETON, accumulator.drain().orElseThrow().hostileId());
    }

    @Test
    @DisplayName("規則 3：合計も時刻も同点なら識別子の辞書順で一意に決める")
    void identifierOrderMakesItDeterministic() {
        DamageAccumulator first = new DamageAccumulator();
        first.add(ZOMBIE, 3.0, 0.9, 100L);
        first.add(SKELETON, 3.0, 0.9, 100L);

        DamageAccumulator reversed = new DamageAccumulator();
        reversed.add(SKELETON, 3.0, 0.9, 100L);
        reversed.add(ZOMBIE, 3.0, 0.9, 100L);

        assertEquals(first.drain().orElseThrow().hostileId(),
                reversed.drain().orElseThrow().hostileId(),
                "投入順で帰属先が変わると、同じ状況でも実行のたびに結果が変わる");
        assertEquals(SKELETON, new java.util.TreeSet<>(java.util.Set.of(ZOMBIE, SKELETON)).first(),
                "辞書順で小さいほうが選ばれる");
    }

    @Test
    @DisplayName("環境ダメージが最大なら代表は加害者なしになる")
    void environmentalDamageCanBeSelected() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(null, 8.0, 0.4, 100L);
        accumulator.add(ZOMBIE, 2.0, 0.3, 110L);

        DamageAccumulator.Drained drained = accumulator.drain().orElseThrow();

        assertTrue(drained.environmental());
        assertNull(drained.hostileId());
        assertEquals(8.0, drained.selectedTotal(), 1e-9);
    }

    // ------------------------------------------------- 傷の重さ

    @Test
    @DisplayName("重さの判定には区間内で最も低かった HP 比率を使う")
    void severityUsesTheLowestHealthInTheWindow() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 8.0, 0.1, 100L);
        accumulator.add(SKELETON, 1.0, 0.6, 120L);

        DamageAccumulator.Drained drained = accumulator.drain().orElseThrow();

        assertEquals(0.1, drained.lowestHealthRatio(), 1e-9,
                "誰に殴られたかとは無関係に、どこまで追い込まれたかが重さ");
        assertEquals(ZOMBIE, drained.hostileId());
    }

    @Test
    @DisplayName("代表が最後の相手でも、最も低かった HP が使われる")
    void lowestHealthIsIndependentOfTheSelectedSource() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 1.0, 0.05, 100L);
        accumulator.add(SKELETON, 9.0, 0.9, 200L);

        DamageAccumulator.Drained drained = accumulator.drain().orElseThrow();

        assertEquals(SKELETON, drained.hostileId());
        assertEquals(0.05, drained.lowestHealthRatio(), 1e-9);
    }

    // ------------------------------------------------- 発火頻度

    @Test
    @DisplayName("複数の相手から受けても取り出しは 1 件")
    void oneDrainPerWindowEvenWithManyHostiles() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 2.0, 0.9, 100L);
        accumulator.add(SKELETON, 3.0, 0.7, 120L);
        accumulator.add("creeper-c", 4.0, 0.4, 130L);

        assertTrue(accumulator.drain().isPresent());
        assertEquals(Optional.empty(), accumulator.drain(),
                "同じ被弾から 2 回出来事が出ると、1 回の経験で二重に学習する");
    }

    @Test
    @DisplayName("取り出すと空になり、次の区間は独立に溜まる")
    void drainEmptiesAndTheNextWindowIsFresh() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 5.0, 0.8, 100L);
        accumulator.drain();

        assertEquals(0.0, accumulator.pendingTotal(), 0.0);
        assertEquals(0, accumulator.pendingSources());

        accumulator.add(SKELETON, 3.0, 0.6, 200L);
        DamageAccumulator.Drained second = accumulator.drain().orElseThrow();
        assertEquals(SKELETON, second.hostileId());
        assertEquals(3.0, second.totalDamage(), 1e-9);
    }

    @Test
    @DisplayName("何も溜まっていなければ空を返す")
    void emptyAccumulatorDrainsNothing() {
        assertEquals(Optional.empty(), new DamageAccumulator().drain());
    }

    // ------------------------------------------------- 閾値

    @Test
    @DisplayName("ごく小さなダメージは出来事にしない")
    void insignificantDamageIsNotAnEvent() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, SIGNIFICANT / 4.0, 0.99, 100L);

        assertEquals(Optional.empty(), accumulator.drain());
    }

    @Test
    @DisplayName("閾値は区間の合計に対して見る（相手ごとではない）")
    void thresholdAppliesToTheWindowTotal() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, SIGNIFICANT * 0.6, 0.95, 100L);
        accumulator.add(SKELETON, SIGNIFICANT * 0.6, 0.90, 110L);

        assertTrue(accumulator.drain().isPresent(),
                "相手ごとに閾値を課すと、刻むように攻撃してくる敵の被害が丸ごと消える");
    }

    @Test
    @DisplayName("閾値未満の内容は持ち越さずに捨てる")
    void insignificantDamageIsNotCarriedOver() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, SIGNIFICANT / 4.0, 0.99, 100L);
        accumulator.drain();

        assertEquals(0.0, accumulator.pendingTotal(), 0.0,
                "いつの被弾か分からないダメージが後から効いてはならない");
        assertEquals(0, accumulator.pendingSources());
    }

    // ------------------------------------------------- 無効な入力

    @Test
    @DisplayName("0 以下のダメージは無視する")
    void nonPositiveDamageIsIgnored() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 0.0, 1.0, 100L);
        accumulator.add(ZOMBIE, -5.0, 1.0, 110L);

        assertEquals(0, accumulator.pendingSources(),
                "完全に防いだ攻撃を被害として学習してはならない");
        assertEquals(Optional.empty(), accumulator.drain());
    }

    @Test
    @DisplayName("clear で捨てた内容は出来事にならない")
    void clearDiscardsEverything() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 10.0, 0.2, 100L);
        accumulator.clear();

        assertEquals(Optional.empty(), accumulator.drain());
        assertFalse(accumulator.pendingSources() > 0);
    }

    @Test
    @DisplayName("記録の無い相手を問い合わせても 0 を返す")
    void unknownSourceHasNoPendingDamage() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(ZOMBIE, 3.0, 0.8, 100L);

        assertEquals(0.0, accumulator.pendingFrom(SKELETON), 0.0);
        assertEquals(0.0, accumulator.pendingFrom(null), 0.0);
    }
}
