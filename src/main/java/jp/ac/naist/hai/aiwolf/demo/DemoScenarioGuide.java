package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;

/**
 * {@code /wolfai demo scenario <name>} のガイド。<strong>Minecraft に依存しない。</strong>
 *
 * <p><strong>手順を表示するだけで、結果を作らない。</strong>行動を直接決めることも、
 * 出来事を合成することも、状態や選好を書き換えることもしない。経験は撮影者が
 * ゲーム内で実際に起こし、研究イベント処理を通して個体に入る。
 * 「進み具合」の行は、個体の記憶を数えて表示するだけである（読み取り専用）。</p>
 *
 * <p>手順の詳細と見積もりの根拠は {@code docs/DEMO_RECORDING.md} にある。</p>
 */
public final class DemoScenarioGuide {

    /** シナリオ。 */
    public enum Scenario {
        /** Demo A：飼い主との距離感の変化。 */
        BONDING("bonding"),
        /** Demo B：危険経験と救援。 */
        DANGER("danger"),
        /** Demo C：危険な場所の記憶。 */
        SPATIAL("spatial"),
        /** Demo D：同じ初期状態からの個体差。 */
        INDIVIDUALITY("individuality");

        private final String id;

        Scenario(String id) {
            this.id = id;
        }

        /** @return コマンドで使う ID */
        public String id() {
            return id;
        }

        /**
         * ID から解決する。
         *
         * @param id ID
         * @return シナリオ
         */
        public static Optional<Scenario> byId(String id) {
            for (Scenario scenario : values()) {
                if (scenario.id.equalsIgnoreCase(id)) {
                    return Optional.of(scenario);
                }
            }
            return Optional.empty();
        }
    }

    private DemoScenarioGuide() {
        throw new AssertionError("utility class");
    }

    /**
     * 手順を返す。
     *
     * @param scenario シナリオ
     * @return 番号付きの手順（先頭はタイトル）
     */
    public static List<String> steps(Scenario scenario) {
        return switch (scenario) {
            case BONDING -> List.of(
                    "[Demo A] 飼い主との距離感の変化（交流 1.5〜5 分、撮影は編集で 30〜60 秒）",
                    "1. /wolfai select → /wolfai demo reset → /wolfai snapshot save before",
                    "2. Before：一緒に歩き、サイドバーの EXPLORE と APPROACH の P を映す",
                    "3. しゃがんで素手で右クリック（撫でる）を 10 秒ごとに 6 回以上",
                    "4. 体力が減っているときに肉を与える（満タンだと記録されません）",
                    "5. （任意）/wolfai demo scenario danger の救援を 1 回入れる",
                    "6. After：Before と同じ歩き方で撮り、/wolfai summary と /wolfai inspect で比べる");
            case DANGER -> List.of(
                    "[Demo B] 危険経験と救援（1 戦闘）",
                    "1. /wolfai select → /wolfai snapshot save before",
                    "2. /summon minecraft:husk ~8 ~ ~ を出し、Wolf を Hostile に攻撃させてください（2 秒以上あけて 2〜3 回被弾）",
                    "3. Owner が剣でその Hostile を攻撃してください（Wolf が先に被弾していることが条件）",
                    "4. Hostile を倒してください（約 2 秒後に player_protected_wolf が記録されます）",
                    "5. 状態変化を確認してください：/wolfai inspect（fear・Memory）",
                    "6. After：もう一度 husk を出し、RETREAT・FOLLOW_CLOSE の P が上がったことを映す");
            case SPATIAL -> List.of(
                    "[Demo C] 危険な場所の記憶（Video 1）",
                    "1. 洞窟の部屋で /wolfai select → /wolfai demo reset → /wolfai debug detailed",
                    "2. 部屋で husk を出し、2 秒以上あけて 3〜4 回被弾させる（同じ 24 blocks 立方体の中で）",
                    "3. /wolfai memory spatial で Fear が 0.30 以上（危険な場所として記憶）を確認",
                    "4. 救援は部屋の外で行う（同じ場所で救援すると場所の恐怖が下がります）",
                    "5. 50 blocks 以上離れて 30 秒〜1 分過ごす → /wolfai snapshot save after",
                    "6. 同じ部屋へ戻る：アクションバーの Spatial memory activated と AVOID_LOCATION を映す");
            case INDIVIDUALITY -> List.of(
                    "[Demo D] 同じ初期状態からの個体差（Video 2、各 10 分の育成）",
                    "1. 2 匹をテイムし名札で A / B と命名。それぞれ select → demo reset → snapshot save initial",
                    "2. 両方の /wolfai inspect で State・Preference が同一であることを確認",
                    "3. 2 匹を 32 blocks 以上離す。A：撫で・餌・救援。B：撫でず座らせず、12〜40 blocks 離れて探索させる",
                    "4. 約 10 分後、柵の囲いに 2 匹を入れて /wolfai evaluation start",
                    "5. A と B を select で切り替えて撮影し、/wolfai summary を比べる",
                    "6. 各個体の隣で /wolfai signature A_eval / B_eval → /wolfai evaluation stop");
        };
    }

    /**
     * 個体の進み具合を返す。個体は変更しない。
     *
     * @param scenario シナリオ
     * @param agent    対象個体（{@code null} なら空）
     * @param region   個体の現在の領域（{@code null} 可）
     * @return 進み具合の行
     */
    public static List<String> progress(Scenario scenario, WolfAiAgent agent, SpatialRegion region) {
        List<String> lines = new ArrayList<>();
        if (agent == null) {
            return lines;
        }
        Map<WolfEventType, Integer> counts = countMemories(agent);
        switch (scenario) {
            case BONDING -> lines.add(String.format(Locale.ROOT,
                    "進み具合：撫で %d 回 / 餌 %d 回 / 救援 %d 回（目安：撫で 6 回以上）",
                    count(counts, WolfEventType.PET), count(counts, WolfEventType.FEED),
                    count(counts, WolfEventType.PLAYER_PROTECTED_WOLF)));
            case DANGER -> lines.add(String.format(Locale.ROOT,
                    "進み具合：被弾 %d 件 / 救援 %d 回 / fear %.2f（目安：被弾 2 件以上・救援 1 回）",
                    damageCount(counts), count(counts, WolfEventType.PLAYER_PROTECTED_WOLF),
                    agent.state().fear()));
            case SPATIAL -> {
                double fear = agent.regionFear(region);
                lines.add(String.format(Locale.ROOT,
                        "進み具合：この場所の恐怖 %.2f（%s）/ 危険な場所の数 %d",
                        fear, fear >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD ? "危険と記憶済み" : "閾値 0.30 未満",
                        dangerousRegions(agent)));
            }
            case INDIVIDUALITY -> lines.add(String.format(Locale.ROOT,
                    "進み具合：飼い主との経験 %d 件（撫で・餌・救援）/ 単独探索の経験 %d 件（安全な探索・新バイオーム）",
                    count(counts, WolfEventType.PET) + count(counts, WolfEventType.FEED)
                            + count(counts, WolfEventType.PLAYER_PROTECTED_WOLF),
                    count(counts, WolfEventType.SAFE_SOLO_EXPLORATION) + count(counts, WolfEventType.NEW_BIOME)));
        }
        if (agent.isLearningFrozen()) {
            lines.add("注意：Evaluation Mode 中は経験が記録されません（/wolfai evaluation stop）");
        }
        return lines;
    }

    private static Map<WolfEventType, Integer> countMemories(WolfAiAgent agent) {
        Map<WolfEventType, Integer> counts = new EnumMap<>(WolfEventType.class);
        for (WolfMemory memory : agent.memoryStore().memories()) {
            counts.merge(memory.type(), 1, Integer::sum);
        }
        return counts;
    }

    private static int count(Map<WolfEventType, Integer> counts, WolfEventType type) {
        return counts.getOrDefault(type, 0);
    }

    private static int damageCount(Map<WolfEventType, Integer> counts) {
        return count(counts, WolfEventType.WOLF_DAMAGED) + count(counts, WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION)
                + count(counts, WolfEventType.WOLF_LOW_HEALTH) + count(counts, WolfEventType.NEAR_DEATH);
    }

    private static int dangerousRegions(WolfAiAgent agent) {
        int dangerous = 0;
        for (var memory : agent.spatialMemory().regions().values()) {
            if (memory.effectiveFear() >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD) {
                dangerous++;
            }
        }
        return dangerous;
    }
}
