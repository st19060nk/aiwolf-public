package jp.ac.naist.hai.aiwolf.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jp.ac.naist.hai.aiwolf.core.AnonymousIdMapper;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.CombatEpisode;
import jp.ac.naist.hai.aiwolf.event.CombatTrigger;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 21：プレイヤー側のログ（イベントとサンプル）の検証。 */
final class PlayerLogTest {

    private static final UUID RAW_PLAYER = UUID.fromString("99999999-1234-4abc-8def-999999999999");
    private static final String PLAYER_NAME = "Researcher";
    private static final AnonymousIdMapper MAPPER = AnonymousIdMapper.withSalt(20_261_114L);

    @AfterEach
    void clean() {
        StudySession.reset();
    }

    private static PlayerLogJson.Subjects subjects() {
        return new PlayerLogJson.Subjects(MAPPER.toSubjectId(RAW_PLAYER), "wolf-ba9876543210",
                ExperimentalCondition.STATE_MEMORY);
    }

    @Test
    @DisplayName("プレイヤーのログに生の UUID・UUID の一部・プレイヤー名・座標が出ない")
    void noPersonalData() {
        StudySession.Active session = StudySession.start("p001-s01", 0L, 0L, ZonedDateTime.now(ZoneOffset.UTC));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hostileType", "minecraft:zombie");
        String event = PlayerLogJson.event(PlayerLogJson.PlayerEventType.PLAYER_ATTACK_HOSTILE, subjects(), session,
                40L, 0L, new PlayerLogJson.Relation(4.0, 0.8, 0.9, 1, true), details);
        String sample = PlayerLogJson.sample(subjects(), session, 60L, 0L,
                new PlayerLogJson.Relation(4.0, 0.8, 0.9, 1, true),
                new PlayerLogJson.Motion(20L, 3.0, 2.0), "follow_close", null);

        String raw = RAW_PLAYER.toString();
        for (String line : List.of(event, sample)) {
            assertFalse(line.contains(raw), line);
            assertFalse(line.contains(raw.substring(0, 8)), "UUID の先頭 8 桁も出してはならない: " + line);
            assertFalse(line.contains(raw.replace("-", "").substring(0, 12)), line);
            assertFalse(line.contains(PLAYER_NAME), line);
            for (String coordinate : List.of("\"x\"", "\"y\"", "\"z\"", "playerX", "playerY", "playerZ")) {
                assertFalse(line.contains(coordinate), "座標は書かない: " + coordinate);
            }
            assertTrue(line.contains("\"subjectId\":\"subject-"), line);
        }
    }

    @Test
    @DisplayName("イベントの必須項目：種類・時刻・経過 tick・条件・適応機構・パラメータ版・文脈")
    void eventFields() {
        StudySession.Active session = StudySession.start("p001-s01", 1_000L, 0L, ZonedDateTime.now(ZoneOffset.UTC));
        String line = PlayerLogJson.event(PlayerLogJson.PlayerEventType.PLAYER_FEED, subjects(), session,
                1_600L, 123L, new PlayerLogJson.Relation(2.5, 0.6, null, 0, false), Map.of());
        for (String key : List.of("\"eventType\":\"PLAYER_FEED\"", "\"subjectId\"", "\"wolfSubjectId\"",
                "\"epochMillis\":123", "\"worldTime\":1600", "\"elapsedTicksFromSessionStart\":600",
                "\"experimentalCondition\":\"state_memory\"", "\"adaptiveFeatures\"", "\"preferenceLearning\":false",
                "\"parameterSetVersion\"", "\"ownerWolfDistance\":2.5", "\"hostileNearby\":false",
                "\"wolfInCombat\":false", "\"playerLogSchemaVersion\":2")) {
            assertTrue(line.contains(key), "missing " + key + " in " + line);
        }
        assertFalse(line.contains("playerHealthRatio"), "オフラインのプレイヤーの体力は書かない（NaN を出さない）");
        assertFalse(line.contains("NaN"));
    }

    @Test
    @DisplayName("サンプル：20 ticks ごとの移動量から速さ・移動中を計算し、初回は移動量を書かない")
    void sampleMotion() {
        PlayerLogJson.Motion motion = new PlayerLogJson.Motion(20L, 3.0, 0.1);
        assertEquals(3.0, motion.playerSpeed(), 1e-12);
        assertEquals(0.1, motion.wolfSpeed(), 1e-12);
        assertTrue(motion.playerMoving());
        assertFalse(motion.wolfMoving(), "0.1 blocks/秒は移動中とみなさない");

        PlayerLogJson.Motion first = PlayerLogJson.Motion.first();
        assertNull(first.playerSpeed());
        String line = PlayerLogJson.sample(subjects(), null, 0L, 0L,
                new PlayerLogJson.Relation(7.25, 1.0, 1.0, 0, false), first, null, null);
        assertTrue(line.contains("\"ownerWolfDistance\":7.25"));
        assertFalse(line.contains("playerMoving"));
        assertFalse(line.contains("elapsedTicksFromSessionStart"), "セッション外では経過 tick を書かない");
    }

    @Test
    @DisplayName("サンプルは 20 ticks に 1 回だけ（毎 tick は書かない）")
    void samplingPeriod() throws Exception {
        // PlayerStateSampler は Minecraft のクラスに依存するので、判定だけを反射で呼ぶ。
        Class<?> sampler = Class.forName("jp.ac.naist.hai.aiwolf.mc.PlayerStateSampler");
        java.lang.reflect.Method isSampleTick = sampler.getMethod("isSampleTick", long.class);
        int samples = 0;
        for (long tick = 1; tick <= 1_200; tick++) {
            if ((boolean) isSampleTick.invoke(null, tick)) {
                samples++;
            }
        }
        assertEquals(60, samples, "1 分（1,200 ticks）で 60 回 = 1 秒に 1 回");
    }

    @Test
    @DisplayName("救援のログは CombatTracker の判定と同じ：飛び道具でも成立し、無関係な敵への攻撃では成立せず、1 回だけ")
    void protectionSemanticsMatchCombatTracker() {
        // PLAYER_PROTECTED_WOLF のプレイヤーログは、CombatEpisode.resolve() がこの種類を返した
        // その 1 回だけ書かれる（WolfEventObserver.resolveCombat）。判定そのものをここで確かめる。
        CombatEpisode rescued = CombatEpisode.open("wolf-1#c0", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 1000L, 1.0);
        rescued.recordWolfDamage(4.0, 0.7, 1000L);
        rescued.recordOwnerAttack(1040L);        // 矢でも DamageAttribution が射手に帰属させる
        rescued.markHostileRemoved(true, 1080L);
        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), rescued.resolve());
        assertEquals(Optional.empty(), rescued.resolve(), "同じ戦闘から 2 回目は出ない（重複なし）");

        CombatEpisode attacked = CombatEpisode.open("wolf-1#c1", "zombie-A", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 1000L, 1.0);
        attacked.recordWolfDamage(4.0, 0.7, 1000L);
        CombatEpisode unrelated = CombatEpisode.open("wolf-1#c2", "zombie-B", "minecraft:zombie",
                CombatTrigger.WOLF_TARGETED_HOSTILE, 1000L, 1.0);
        unrelated.recordOwnerAttack(1040L);      // 飼い主は別の敵を攻撃
        attacked.markHostileRemoved(true, 1080L);
        assertEquals(Optional.empty(), attacked.resolve(), "無関係な敵への攻撃は救援ではない");
    }
}
