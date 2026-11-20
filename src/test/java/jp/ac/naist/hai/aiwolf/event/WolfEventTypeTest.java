package jp.ac.naist.hai.aiwolf.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link WolfEventType} の単体テスト。 */
class WolfEventTypeTest {

    private static final double EPS = 1.0e-9;

    @Test
    @DisplayName("salience = 0.5|valence| + 0.5 arousal + bonus で計算される")
    void salienceFollowsSpecification() {
        // FEED: valence 0.4, arousal 0.2, bonus 0.0
        assertEquals(0.5 * 0.4 + 0.5 * 0.2, WolfEventType.FEED.defaultSalience(), EPS);
        // NEAR_DEATH: valence -1.0, arousal 0.9, bonus 0.2 -> clamp(1.15) = 1.0
        assertEquals(1.0, WolfEventType.NEAR_DEATH.defaultSalience(), EPS);
    }

    @Test
    @DisplayName("強い出来事ほど salience が高い")
    void strongEventsAreMoreSalient() {
        assertTrue(WolfEventType.NEAR_DEATH.defaultSalience() > WolfEventType.PET.defaultSalience());
        assertTrue(WolfEventType.PLAYER_PROTECTED_WOLF.defaultSalience() > WolfEventType.FEED.defaultSalience());
    }

    @Test
    @DisplayName("valence / arousal / salience が規定の値域に収まる")
    void valuesStayInRange() {
        for (WolfEventType type : WolfEventType.ALL) {
            assertTrue(type.defaultValence() >= -1.0 && type.defaultValence() <= 1.0, type.id());
            assertTrue(type.defaultArousal() >= 0.0 && type.defaultArousal() <= 1.0, type.id());
            assertTrue(type.defaultSalience() >= 0.0 && type.defaultSalience() <= 1.0, type.id());
        }
    }

    @Test
    @DisplayName("ID から種別を解決できる")
    void byIdResolves() {
        assertEquals(WolfEventType.PET, WolfEventType.byId("pet").orElseThrow());
        assertEquals(WolfEventType.PET, WolfEventType.byId(" PET ").orElseThrow());
        assertTrue(WolfEventType.byId("nonexistent").isEmpty());
    }
}
