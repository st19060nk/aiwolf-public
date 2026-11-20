package jp.ac.naist.hai.aiwolf.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import jp.ac.naist.hai.aiwolf.core.AnonymousIdMapper;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.DamageAttribution;
import jp.ac.naist.hai.aiwolf.event.DamageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 研究ログにプレイヤーの UUID が残らないことの回帰テスト。
 *
 * <p>Mob の UUID はそのワールド限りの内部識別子で、戦闘エピソードと突き合わせるために要る。
 * プレイヤーの UUID は Minecraft アカウントに紐づく個人情報であり、
 * <strong>短縮しても同一人物の特定に使える</strong>ため、部分文字列であっても
 * 研究データへ残してはならない。README で「プレイヤー名も UUID も残さない」と
 * 説明している以上、実装がそうなっていなければ説明のほうが嘘になる。</p>
 */
final class DamageLogAnonymityTest {

    private static final String WOLF = "11111111-1111-4111-8111-111111111111";
    private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String RAW = "12345678-1234-1234-1234-123456789abc";
    private static final String RAW_PREFIX = "12345678";

    private static final AnonymousIdMapper MAPPER = AnonymousIdMapper.withSalt(20_260_924L);

    private static String line(DamageRecord record) {
        return DamageLogJson.toJsonLine(WOLF, MAPPER.toSubjectId(PLAYER),
                ExperimentalCondition.ADAPTIVE, record);
    }

    private static DamageRecord withAttacker(String attackerType, String attackerId,
                                             String directSource, String causingSource) {
        return new DamageRecord(DamageRecord.ROLE_HOSTILE, attackerType, attackerId,
                directSource, causingSource,
                4.0, 20.0, 16.0, 0.0, 0.0, false, WOLF + "#c0", 1000L);
    }

    // ------------------------------------------------- 識別子の選択規則

    @Test
    @DisplayName("プレイヤーの加害者は匿名 ID になる")
    void playerAttackerIsAnonymised() {
        String anonymised = MAPPER.toSubjectId(PLAYER);

        assertEquals(anonymised, DamageAttribution.attackerId(true, anonymised, RAW_PREFIX));
        assertTrue(anonymised.startsWith(AnonymousIdMapper.PREFIX));
    }

    @Test
    @DisplayName("Mob の加害者は内部識別子のまま")
    void mobAttackerKeepsItsInternalId() {
        assertEquals("a1b2c3d4", DamageAttribution.attackerId(false, null, "a1b2c3d4"),
                "戦闘エピソードとの突き合わせに要る");
    }

    // ------------------------------------------------- 漏洩の回帰テスト

    @Test
    @DisplayName("プレイヤーの近接攻撃：生 UUID も先頭 8 文字もログに出ない")
    void playerMeleeLeaksNothing() {
        String json = line(withAttacker(DamageRecord.ROLE_OWNER,
                MAPPER.toSubjectId(PLAYER), "minecraft:player", "minecraft:player"));

        assertFalse(json.contains(RAW), "生 UUID が研究データへ入ってはならない");
        assertFalse(json.contains(RAW_PREFIX), "先頭 8 文字でも同一人物の特定に使える");
        assertTrue(json.contains(MAPPER.toSubjectId(PLAYER)), "匿名 ID だけが入る");
    }

    @Test
    @DisplayName("プレイヤーの矢：矢ではなく匿名化された射手へ帰属する")
    void playerProjectileIsAttributedToTheAnonymisedShooter() {
        String json = line(withAttacker(DamageRecord.ROLE_OWNER,
                MAPPER.toSubjectId(PLAYER), "minecraft:arrow", "minecraft:player"));

        assertFalse(json.contains(RAW));
        assertFalse(json.contains(RAW_PREFIX));
        assertTrue(json.contains("\"attackerId\":\"" + MAPPER.toSubjectId(PLAYER) + "\""));
        assertTrue(json.contains("\"directSourceType\":\"minecraft:arrow\""),
                "矢であったこと自体は残してよい。個人を特定しないため");
    }

    @Test
    @DisplayName("Skeleton の矢：射手の Mob 識別子で記録される")
    void skeletonProjectileKeepsTheMobId() {
        String json = line(withAttacker(DamageRecord.ROLE_HOSTILE, "a1b2c3d4",
                "minecraft:arrow", "minecraft:skeleton"));

        assertTrue(json.contains("\"attackerId\":\"a1b2c3d4\""));
        assertTrue(json.contains("\"causingSourceType\":\"minecraft:skeleton\""));
    }

    @Test
    @DisplayName("加害者がいない場合も安全に記録できる")
    void environmentalDamageIsSafe() {
        DamageRecord record = new DamageRecord(DamageRecord.ROLE_WOLF,
                DamageRecord.ROLE_ENVIRONMENT, null, null, null,
                4.0, 20.0, 16.0, 0.0, 0.0, false, null, 1000L);

        String json = line(record);

        assertTrue(json.contains("\"attackerId\":null"));
        assertNull(record.attackerId());
        assertFalse(json.contains(RAW));
    }

    @Test
    @DisplayName("ログのどのフィールドにも生 UUID が現れない")
    void noFieldCarriesARawUuid() {
        for (String attackerId : new String[]{MAPPER.toSubjectId(PLAYER), "a1b2c3d4", null}) {
            String json = line(withAttacker(DamageRecord.ROLE_OWNER, attackerId,
                    "minecraft:arrow", "minecraft:player"));
            assertFalse(json.contains(RAW), "attackerId=" + attackerId);
            assertFalse(json.contains(RAW_PREFIX), "attackerId=" + attackerId);
        }
    }

    // ------------------------------------------------- 実装の回帰テスト

    @Test
    @DisplayName("実装がプレイヤーへ短縮 UUID を使っていない")
    void theImplementationAnonymisesPlayers() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++) {
            if (Files.isDirectory(root.resolve("src/main/java"))) {
                break;
            }
            root = root.getParent();
        }
        String source = Files.readString(
                root.resolve("src/main/java/jp/ac/naist/hai/aiwolf/mc/DamageObserver.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("DamageAttribution.attackerId("),
                "識別子の選択は 1 か所の規則を通すこと");
        assertTrue(source.contains("WolfAiRegistry.anonymousId(attacker.getUuid())"),
                "プレイヤーは匿名 ID へ変換すること");
        assertFalse(source.contains("CombatTracker.shortId(attacker.getUuid())")
                        && !source.contains("player ? null"),
                "加害者へ無条件に短縮 UUID を使うと、プレイヤーの UUID が漏れる");
    }
}
