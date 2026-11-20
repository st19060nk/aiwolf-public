package jp.ac.naist.hai.aiwolf.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link AnonymousIdMapper} の単体テスト。 */
class AnonymousIdMapperTest {

    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    @DisplayName("同じ salt なら同じプレイヤーは常に同じ ID になる")
    void sameSaltGivesStableId() {
        AnonymousIdMapper first = AnonymousIdMapper.withSalt(42L);
        AnonymousIdMapper second = AnonymousIdMapper.withSalt(42L);
        assertEquals(first.toSubjectId(PLAYER_A), second.toSubjectId(PLAYER_A));
    }

    @Test
    @DisplayName("salt が違えば同じプレイヤーでも別 ID になる")
    void differentSaltGivesDifferentId() {
        assertFalse(AnonymousIdMapper.withSalt(1L).toSubjectId(PLAYER_A)
                .equals(AnonymousIdMapper.withSalt(2L).toSubjectId(PLAYER_A)));
    }

    @Test
    @DisplayName("プレイヤーが違えば ID も違う")
    void differentPlayersGetDifferentIds() {
        AnonymousIdMapper mapper = AnonymousIdMapper.withSalt(7L);
        assertFalse(mapper.toSubjectId(PLAYER_A).equals(mapper.toSubjectId(PLAYER_B)));
    }

    @Test
    @DisplayName("ID から元の UUID が読み取れない")
    void idDoesNotLeakTheUuid() {
        String id = AnonymousIdMapper.withSalt(7L).toSubjectId(PLAYER_A);
        assertTrue(id.startsWith(AnonymousIdMapper.PREFIX));
        assertFalse(id.contains("1111"), "UUID の断片が残ってはならない");
        assertFalse(id.contains(PLAYER_A.toString()));
    }

    @Test
    @DisplayName("多数のプレイヤーでも衝突しない")
    void idsDoNotCollide() {
        AnonymousIdMapper mapper = AnonymousIdMapper.withSalt(99L);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(mapper.toSubjectId(new UUID(i, i * 31L)));
        }
        assertEquals(10_000, ids.size());
    }

    @Test
    @DisplayName("飼い主が未設定なら null を返す")
    void nullPlayerGivesNullId() {
        assertNull(AnonymousIdMapper.withSalt(1L).toSubjectId(null));
    }
}
