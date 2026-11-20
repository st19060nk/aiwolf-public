package jp.ac.naist.hai.aiwolf.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NbtCompound;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.persistence.WolfAiNbt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code /wolfai summary} と {@code /wolfai demo scenario} の検証。 */
final class BehaviorSummaryAndScenarioTest {

    private static final String WOLF = DemoFixtures.WOLF;

    @Test
    @DisplayName("割合は件数ではなく時間で重み付けされる（緊急時の短い間隔を過大に数えない）")
    void proportionsAreTimeWeighted() {
        BehaviorHistory history = new BehaviorHistory();
        history.record(WOLF, 0L, BehaviorType.FOLLOW_CLOSE, true, 2.0);
        // 緊急時：4 ticks ごとに 5 回 RETREAT（合計 20 ticks）
        for (int i = 0; i < 5; i++) {
            history.record(WOLF, 40L + i * 4L, BehaviorType.RETREAT, true, 6.0);
        }
        history.record(WOLF, 60L, BehaviorType.EXPLORE_NEARBY, false, 1_000.0);

        BehaviorHistory.Summary summary = history.summarize(WOLF, 100L, Long.MAX_VALUE);

        assertEquals(100L, summary.coveredTicks());
        assertEquals(7, summary.decisionCount());
        assertEquals(0.40, summary.proportions().get(BehaviorType.FOLLOW_CLOSE), 1e-9);
        assertEquals(0.20, summary.proportions().get(BehaviorType.RETREAT), 1e-9);
        assertEquals(0.40, summary.proportions().get(BehaviorType.EXPLORE_NEARBY), 1e-9);
        // 飼い主が近傍にいた 60 ticks だけの平均：(2.0×40 + 6.0×20) / 60
        assertEquals((2.0 * 40 + 6.0 * 20) / 60.0, summary.meanOwnerDistance(), 1e-9);
    }

    @Test
    @DisplayName("決定が止まっていた時間は 1 件あたり最大 120 ticks までしか数えない")
    void longGapsAreCapped() {
        BehaviorHistory history = new BehaviorHistory();
        history.record(WOLF, 0L, BehaviorType.REST_NEAR_PLAYER, true, 1.0);
        history.record(WOLF, 10_000L, BehaviorType.FOLLOW_NORMAL, true, 7.0);

        BehaviorHistory.Summary summary = history.summarize(WOLF, 10_040L, Long.MAX_VALUE);

        assertEquals(BehaviorHistory.MAX_SEGMENT_TICKS + 40L, summary.coveredTicks());
        assertEquals(120.0 / 160.0, summary.proportions().get(BehaviorType.REST_NEAR_PLAYER), 1e-9);
    }

    @Test
    @DisplayName("直近 1 分・5 分の窓は、窓の外の時間を切り落とす")
    void windowsClipOlderTime() {
        BehaviorHistory history = new BehaviorHistory();
        long now = 10_000L;
        // 5 分以上前から 1 分前までは EXPLORE、直近 1 分は APPROACH（いずれも 40 ticks 間隔）
        for (long t = now - 8_000L; t < now - BehaviorSummaryReport.ONE_MINUTE; t += 40L) {
            history.record(WOLF, t, BehaviorType.EXPLORE_NEARBY, true, 10.0);
        }
        for (long t = now - BehaviorSummaryReport.ONE_MINUTE; t < now; t += 40L) {
            history.record(WOLF, t, BehaviorType.APPROACH_PLAYER, true, 2.0);
        }

        BehaviorHistory.Summary last1 = history.summarize(WOLF, now, BehaviorSummaryReport.ONE_MINUTE);
        BehaviorHistory.Summary last5 = history.summarize(WOLF, now, BehaviorSummaryReport.FIVE_MINUTES);

        assertEquals(1.0, last1.proportions().get(BehaviorType.APPROACH_PLAYER), 1e-9);
        assertEquals(BehaviorSummaryReport.ONE_MINUTE, last1.coveredTicks());
        assertEquals(30, last1.decisionCount());
        assertEquals(0.2, last5.proportions().get(BehaviorType.APPROACH_PLAYER), 1e-9);
        assertEquals(0.8, last5.proportions().get(BehaviorType.EXPLORE_NEARBY), 1e-9);
        assertEquals(2.0 * 0.2 + 10.0 * 0.8, last5.meanOwnerDistance(), 1e-9);
    }

    @Test
    @DisplayName("summary reset は対象個体の記録だけを消し、新しいセッションを始める")
    void resetStartsANewSession() {
        BehaviorHistory history = new BehaviorHistory();
        history.record(WOLF, 0L, BehaviorType.FOLLOW_CLOSE, true, 2.0);
        history.record(DemoFixtures.OTHER_WOLF, 0L, BehaviorType.RETREAT, true, 9.0);
        history.reset(WOLF);

        assertTrue(history.summarize(WOLF, 100L, Long.MAX_VALUE).isEmpty());
        assertEquals(-1L, history.sessionStartTick(WOLF));
        assertFalse(history.summarize(DemoFixtures.OTHER_WOLF, 100L, Long.MAX_VALUE).isEmpty());

        history.record(WOLF, 500L, BehaviorType.EXPLORE_NEARBY, true, 5.0);
        assertEquals(500L, history.sessionStartTick(WOLF));
    }

    @Test
    @DisplayName("記録は上限件数を超えると古いものから捨てる")
    void historyIsBounded() {
        BehaviorHistory history = new BehaviorHistory();
        for (int i = 0; i < BehaviorHistory.CAPACITY + 10; i++) {
            history.record(WOLF, i * 40L, BehaviorType.FOLLOW_NORMAL, true, 7.0);
        }
        long now = (BehaviorHistory.CAPACITY + 10) * 40L;
        assertEquals(BehaviorHistory.CAPACITY, history.summarize(WOLF, now, Long.MAX_VALUE).decisionCount());
    }

    @Test
    @DisplayName("summary の表：3 区間を「1 分 / 5 分 / セッション」の順で並べ、記録のない区間は - にする")
    void summaryReportFormat() {
        BehaviorHistory history = new BehaviorHistory();
        history.record(WOLF, 0L, BehaviorType.FOLLOW_CLOSE, true, 3.0);
        history.record(WOLF, 40L, BehaviorType.EXPLORE_NEARBY, true, 9.0);
        // 最後の決定から 10 分経過：直近 1 分と 5 分には時間が入らない（120 ticks で打ち切り）
        List<String> lines = BehaviorSummaryReport.lines(history, WOLF, 12_000L);
        String text = String.join("\n", lines);

        assertTrue(lines.get(0).contains("last 1 min / last 5 min / session"));
        assertTrue(text.contains("FOLLOW_CLOSE:  - / - / 25%"), text);
        assertTrue(text.contains("EXPLORE_NEARBY:  - / - / 75%"), text);
        assertTrue(text.contains("Mean Owner Distance [blocks]:  - / - / 7.5"), text);
        assertFalse(text.contains("RETREAT"), "behaviours never selected are omitted");

        assertTrue(BehaviorSummaryReport.lines(new BehaviorHistory(), WOLF, 0L).get(0).contains("まだ記録がありません"));
    }

    @Test
    @DisplayName("scenario danger は依頼どおりの 4 手順をガイドとして表示するだけ")
    void dangerScenarioGuide() {
        String text = String.join("\n", DemoScenarioGuide.steps(DemoScenarioGuide.Scenario.DANGER));
        assertTrue(text.contains("Wolf を Hostile に攻撃させてください"));
        assertTrue(text.contains("Owner が剣でその Hostile を攻撃してください"));
        assertTrue(text.contains("Hostile を倒してください"));
        assertTrue(text.contains("状態変化を確認してください"));
    }

    @Test
    @DisplayName("どのシナリオも行動の直接指定や性格の設定を案内しない")
    void scenariosNeverShortcutTheResearchLogic() {
        for (DemoScenarioGuide.Scenario scenario : DemoScenarioGuide.Scenario.values()) {
            assertEquals(scenario, DemoScenarioGuide.Scenario.byId(scenario.id()).orElseThrow());
            for (String step : DemoScenarioGuide.steps(scenario)) {
                String lower = step.toLowerCase(java.util.Locale.ROOT);
                assertFalse(lower.contains("make-") || lower.contains("brave") || lower.contains("shy")
                        || lower.contains("friendly") || lower.contains("personality"), step);
            }
        }
    }

    @Test
    @DisplayName("scenario の進み具合は記憶を数えるだけで、個体を変更しない")
    void scenarioProgressIsReadOnly() {
        WolfAiController controller = new WolfAiController(DemoFixtures.newAgent());
        DemoFixtures.experienceDangerAndRescue(controller.agent());
        NbtCompound before = WolfAiNbt.write(controller);

        Map<DemoScenarioGuide.Scenario, String> progress = new java.util.EnumMap<>(DemoScenarioGuide.Scenario.class);
        for (DemoScenarioGuide.Scenario scenario : DemoScenarioGuide.Scenario.values()) {
            progress.put(scenario, String.join("\n", DemoScenarioGuide.progress(scenario, controller.agent(),
                    DemoFixtures.dangerRegion())));
        }

        assertEquals(before, WolfAiNbt.write(controller));
        assertTrue(progress.get(DemoScenarioGuide.Scenario.DANGER).contains("被弾 5 件 / 救援 1 回"),
                progress.get(DemoScenarioGuide.Scenario.DANGER));
        assertTrue(progress.get(DemoScenarioGuide.Scenario.BONDING).contains("撫で 1 回 / 餌 1 回 / 救援 1 回"));
        assertTrue(progress.get(DemoScenarioGuide.Scenario.SPATIAL).contains("危険と記憶済み"));
        assertTrue(DemoScenarioGuide.progress(DemoScenarioGuide.Scenario.DANGER, null, null).isEmpty());
    }
}
