package jp.ac.naist.hai.aiwolf.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.DamageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 被弾ログの形式のテスト。
 *
 * <p>このログは学習には一切使われない。決定ごとに集約される研究イベントの前に、
 * 物理的に何が起きていたかを後から検証するためだけに残す。</p>
 */
final class DamageLogJsonTest {

    private static final String WOLF = "11111111-1111-4111-8111-111111111111";

    private static String line(DamageRecord record) {
        return DamageLogJson.toJsonLine(WOLF, "subject-abc123def456",
                ExperimentalCondition.ADAPTIVE, record);
    }

    @Test
    @DisplayName("必要な項目がすべて入る")
    void everyRequiredFieldIsPresent() {
        DamageRecord record = new DamageRecord(DamageRecord.ROLE_WOLF, DamageRecord.ROLE_HOSTILE,
                "skelet-b", "minecraft:arrow", "minecraft:skeleton",
                3.0, 20.0, 17.0, 0.0, 0.0, false, WOLF + "#c2", 12345L);

        String json = line(record);

        assertFalse(json.contains("\n"));
        for (String key : List.of("\"recordType\":\"damage\"", "\"damageDefinition\"",
                "\"targetType\"", "\"attackerType\"", "\"attackerId\"",
                "\"directSourceType\"", "\"causingSourceType\"", "\"actualDamage\"",
                "\"healthBefore\"", "\"healthAfter\"", "\"absorptionBefore\"",
                "\"absorptionAfter\"", "\"fatal\"", "\"combatId\"", "\"worldTime\"")) {
            assertTrue(json.contains(key), "missing: " + key);
        }
    }

    @Test
    @DisplayName("ダメージの意味が毎行に残る")
    void theDefinitionTravelsWithEveryLine() {
        DamageRecord record = new DamageRecord(DamageRecord.ROLE_WOLF, DamageRecord.ROLE_HOSTILE,
                "zombie-a", "minecraft:zombie", "minecraft:zombie",
                2.0, 20.0, 18.0, 0.0, 0.0, false, WOLF + "#c0", 100L);

        assertTrue(line(record).contains("\"damageDefinition\":\"EFFECTIVE_HEALTH_LOSS\""),
                "後から damageAmount の意味が分からなくなるのを防ぐ");
    }

    @Test
    @DisplayName("矢は矢と射手の両方が残る")
    void projectileKeepsBothSources() {
        DamageRecord record = new DamageRecord(DamageRecord.ROLE_WOLF, DamageRecord.ROLE_HOSTILE,
                "skelet-b", "minecraft:arrow", "minecraft:skeleton",
                3.0, 20.0, 17.0, 0.0, 0.0, false, WOLF + "#c2", 100L);

        String json = line(record);
        assertTrue(json.contains("\"directSourceType\":\"minecraft:arrow\""));
        assertTrue(json.contains("\"causingSourceType\":\"minecraft:skeleton\""));
    }

    @Test
    @DisplayName("致死攻撃が記録に残る")
    void fatalHitIsFlagged() {
        DamageRecord record = new DamageRecord(DamageRecord.ROLE_WOLF, DamageRecord.ROLE_HOSTILE,
                "zombie-a", "minecraft:zombie", "minecraft:zombie",
                3.0, 3.0, 0.0, 0.0, 0.0, true, WOLF + "#c0", 100L);

        String json = line(record);
        assertTrue(json.contains("\"fatal\":true"));
        assertTrue(json.contains("\"healthAfter\":0"));
    }

    @Test
    @DisplayName("環境ダメージでは加害者と戦闘が null になる")
    void environmentalDamageHasNullsWhereItShould() {
        DamageRecord record = new DamageRecord(DamageRecord.ROLE_WOLF,
                DamageRecord.ROLE_ENVIRONMENT, null, null, null,
                4.0, 20.0, 16.0, 0.0, 0.0, false, null, 100L);

        String json = line(record);
        assertTrue(json.contains("\"attackerType\":\"environment\""));
        assertTrue(json.contains("\"attackerId\":null"));
        assertTrue(json.contains("\"combatId\":null"));
    }

    @Test
    @DisplayName("HP のみの損失と Absorption の損失を別に残す")
    void healthAndAbsorptionLossAreBothWritten() {
        DamageRecord record = new DamageRecord(DamageRecord.ROLE_OWNER, DamageRecord.ROLE_HOSTILE,
                "zombie-a", "minecraft:zombie", "minecraft:zombie",
                5.0, 20.0, 17.0, 2.0, 0.0, false, WOLF + "#c0", 100L);

        String json = line(record);
        assertTrue(json.contains("\"healthLoss\":3"), "解析時に定義を組み替えられるようにする");
        assertTrue(json.contains("\"absorptionLoss\":2"));
        assertTrue(json.contains("\"actualDamage\":5"));
    }

    @Test
    @DisplayName("プレイヤー名も UUID も残らない")
    void noPersonalInformation() {
        DamageRecord record = new DamageRecord(DamageRecord.ROLE_OWNER, DamageRecord.ROLE_OWNER,
                "aabbccdd", "minecraft:player", "minecraft:player",
                1.0, 20.0, 19.0, 0.0, 0.0, false, null, 100L);

        String json = line(record);
        assertTrue(json.contains("subject-"));
        assertFalse(json.contains("playerName"));
        assertFalse(json.contains("ownerUuid"));
    }
}
