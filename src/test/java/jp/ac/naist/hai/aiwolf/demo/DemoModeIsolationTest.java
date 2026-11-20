package jp.ac.naist.hai.aiwolf.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import net.minecraft.nbt.NbtCompound;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.log.DecisionLogJson;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.persistence.WolfAiNbt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Demo Mode とデバッグ表示が研究本体へ影響しないことの検証。 */
final class DemoModeIsolationTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @AfterEach
    void resetFlags() {
        DemoMode.reset();
    }

    private static List<BehaviorDecision> run() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        return DemoFixtures.decide(agent, 0, 60);
    }

    @Test
    @DisplayName("Demo Mode の on/off で個体の学習も行動選択も変わらない")
    void demoModeDoesNotAffectResearchLogic() {
        DemoMode.reset();
        List<BehaviorDecision> research = run();
        DemoMode.setEnabled(true);
        List<BehaviorDecision> demo = run();

        assertEquals(research.size(), demo.size());
        for (int i = 0; i < research.size(); i++) {
            assertEquals(research.get(i).selected(), demo.get(i).selected());
            assertEquals(research.get(i).scores(), demo.get(i).scores());
            assertEquals(research.get(i).probabilities(), demo.get(i).probabilities());
            assertEquals(research.get(i).randomValue(), demo.get(i).randomValue());
        }
    }

    @Test
    @DisplayName("Demo Mode 中のログには demoMode=true が付き、既存の項目はそのまま残る")
    void demoLogLinesAreTagged() {
        String line = "{\"wolfId\":\"x\",\"value\":1}";
        assertEquals("{\"demoMode\":true,\"evaluationMode\":false,\"wolfId\":\"x\",\"value\":1}",
                DemoMode.tagLogLine(line, false));
        assertEquals("{\"demoMode\":true,\"evaluationMode\":true}", DemoMode.tagLogLine("{}", true));
        assertEquals("not json", DemoMode.tagLogLine("not json", false));
    }

    @Test
    @DisplayName("評価モードは Demo Mode 中しか有効にならず、Demo Mode を切ると必ず終わる")
    void evaluationRequiresDemoMode() {
        assertFalse(DemoMode.setEvaluationActive(true));
        DemoMode.setEnabled(true);
        assertTrue(DemoMode.setEvaluationActive(true));
        DemoMode.setEnabled(false);
        assertFalse(DemoMode.isEvaluationActive());
    }

    @Test
    @DisplayName("デバッグ表示を何度組み立てても AI の状態は変わらない")
    void debugViewIsReadOnly() {
        WolfAiController controller = new WolfAiController(DemoFixtures.newAgent());
        DemoFixtures.experienceDangerAndRescue(controller.agent());
        BehaviorDecision last = DemoFixtures.decide(controller.agent(), 0, 10).get(9);
        NbtCompound before = WolfAiNbt.write(controller);
        List<String> memoryIdsBefore = controller.agent().lastRelevantMemoryIds();

        for (WolfDebugView.Level level : WolfDebugView.Level.values()) {
            for (int i = 0; i < 5; i++) {
                WolfDebugView.lines(level, controller.agent(), last, DemoFixtures.dangerRegion(), true, true);
            }
        }

        assertEquals(before, WolfAiNbt.write(controller));
        assertEquals(memoryIdsBefore, controller.agent().lastRelevantMemoryIds());
    }

    @Test
    @DisplayName("デバッグ表示の内容：行動・状態・参照記憶・場所の恐怖・上位スコア")
    void debugViewShowsTheDecisionInputs() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        BehaviorDecision last = DemoFixtures.decide(agent, 0, 3).get(2);

        List<String> basic = WolfDebugView.lines(WolfDebugView.Level.BASIC, agent, last,
                DemoFixtures.dangerRegion(), true, false);
        String text = String.join("\n", basic);
        assertTrue(text.contains("Behavior: " + agent.currentBehavior().name()));
        assertTrue(text.contains("Trust") && text.contains("Attach") && text.contains("Fear")
                && text.contains("Curious"));
        assertTrue(text.contains("Memory: ") && !text.contains("Memory: -"));
        assertTrue(text.contains("Location fear"));
        assertTrue(text.contains("[DEMO MODE]"));

        List<String> detailed = WolfDebugView.lines(WolfDebugView.Level.DETAILED, agent, last,
                DemoFixtures.dangerRegion(), true, true);
        String detailedText = String.join("\n", detailed);
        assertTrue(detailed.size() <= WolfDebugView.MAX_LINES);
        assertTrue(detailedText.contains("[EVALUATION]"));
        assertTrue(detailedText.contains("Top scores"));
        assertTrue(detailedText.contains("Decision #" + last.decisionIndex()));
        assertTrue(detailedText.contains("Pref "));
        assertTrue(WolfDebugView.lines(WolfDebugView.Level.OFF, agent, last, null, true, true).isEmpty());
    }

    @Test
    @DisplayName("デバッグ表示にも Demo のログにも生の UUID は出ない")
    void noRawUuidLeaks() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        BehaviorDecision last = DemoFixtures.decide(agent, 0, 5).get(4);

        for (WolfDebugView.Level level : WolfDebugView.Level.values()) {
            for (String line : WolfDebugView.lines(level, agent, last, DemoFixtures.dangerRegion(), true, true)) {
                assertFalse(UUID_PATTERN.matcher(line).find(), "UUID on screen: " + line);
                assertFalse(line.contains(DemoFixtures.ACTOR), "owner id on screen: " + line);
            }
        }

        String logLine = DemoMode.tagLogLine(DecisionLogJson.toJsonLine(agent.wolfId(), agent.ownerResearchId(),
                ExperimentalCondition.ADAPTIVE, 1L, agent.random().seed(), 1L, 0L,
                DemoFixtures.DIMENSION, DemoFixtures.BIOME, 0.0, 0.0, 0.0,
                agent.state(), last, agent.lastRelevantMemoryIds()), false);
        assertFalse(logLine.contains(DemoFixtures.RAW_PLAYER_UUID));
        assertTrue(logLine.contains(DemoFixtures.ACTOR), "the owner is logged only by the anonymous id");
        assertTrue(logLine.startsWith("{\"demoMode\":true"));
    }
}
