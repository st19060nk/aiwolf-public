package jp.ac.naist.hai.aiwolf.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 戦闘 ID の一意性のテスト。
 *
 * <p>採番カウンタが再起動で 0 へ戻ると、同じ個体の別セッションの戦闘が
 * 同じ ID を持つ。解析では combatId を戦闘の同一性の根拠に使うため、
 * 無関係な 2 件が 1 件へ結合され、存在しない重複発火として現れる。</p>
 */
final class CombatIdSequenceTest {

    private static final String WOLF = "11111111-1111-4111-8111-111111111111";

    @Test
    @DisplayName("同じセッション内で ID が重複しない")
    void idsAreUniqueWithinASession() {
        CombatIdSequence sequence = new CombatIdSequence(WOLF);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            assertTrue(seen.add(sequence.next()), "同じ ID が二度出た");
        }
    }

    @Test
    @DisplayName("セーブ・復元を跨いでも ID が衝突しない")
    void idsDoNotCollideAcrossRestarts() {
        List<String> all = new ArrayList<>();

        CombatIdSequence first = new CombatIdSequence(WOLF);
        for (int i = 0; i < 5; i++) {
            all.add(first.next());
        }
        long saved = first.sequence();

        CombatIdSequence afterRestart = new CombatIdSequence(WOLF);
        afterRestart.restore(saved);
        for (int i = 0; i < 5; i++) {
            all.add(afterRestart.next());
        }

        assertEquals(all.size(), new HashSet<>(all).size(),
                "再起動後の最初の戦闘が以前の ID と衝突している");
        assertFalse(all.get(5).equals(all.get(0)));
        assertEquals(WOLF + "#c5", all.get(5));
    }

    @Test
    @DisplayName("復元しなければ衝突する（永続化が必要な理由）")
    void withoutRestoreTheIdsCollide() {
        CombatIdSequence first = new CombatIdSequence(WOLF);
        String beforeRestart = first.next();

        CombatIdSequence afterRestart = new CombatIdSequence(WOLF);

        assertEquals(beforeRestart, afterRestart.next(),
                "だから combatSequence を NBT へ保存する");
    }

    @Test
    @DisplayName("復元値が現在より小さくても巻き戻らない")
    void restoreNeverRewinds() {
        CombatIdSequence sequence = new CombatIdSequence(WOLF);
        sequence.next();
        sequence.next();

        sequence.restore(0L);
        sequence.restore(-5L);

        assertEquals(WOLF + "#c2", sequence.next());
    }

    @Test
    @DisplayName("個体が違えば ID も違う")
    void differentWolvesGetDifferentIds() {
        CombatIdSequence a = new CombatIdSequence(WOLF);
        CombatIdSequence b = new CombatIdSequence("22222222-2222-4222-8222-222222222222");

        assertFalse(a.next().equals(b.next()));
    }

    @Test
    @DisplayName("採番は決定論的で、同じ実験を再実行すれば同じ ID になる")
    void idsAreReproducible() {
        assertEquals(new CombatIdSequence(WOLF).next(), new CombatIdSequence(WOLF).next(),
                "時刻やランダム値を混ぜるとログ同士を突き合わせられなくなる");
    }
}
