package jp.ac.naist.hai.aiwolf.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import net.minecraft.nbt.NbtCompound;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.memory.RegionMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.persistence.WolfAiNbt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code /wolfai memory spatial} と {@code /wolfai inspect} の出力の検証。 */
final class DemoReportsTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Test
    @DisplayName("memory spatial：危険な場所にいるときは実効恐怖値・経験回数・危険判定を出す")
    void spatialReportAtTheDangerousPlace() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        SpatialRegion region = DemoFixtures.dangerRegion();
        RegionMemory memory = agent.spatialMemory().get(region);

        String text = String.join("\n", SpatialMemoryReport.lines(agent.spatialMemory(), region));

        assertTrue(text.contains(String.format(java.util.Locale.ROOT, "Fear: %.2f", memory.effectiveFear())));
        assertTrue(text.contains("Experience Count: " + memory.visitCount()));
        assertTrue(text.contains("危険な場所として記憶"));
        assertTrue(text.contains("現在地"), "the strongest negative region is the current one");
        assertTrue(text.contains("dangerous (>= 0.30): 1"));
    }

    @Test
    @DisplayName("memory spatial：離れた場所からは近くの危険な場所の座標を示す")
    void spatialReportFromNearby() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        SpatialRegion danger = DemoFixtures.dangerRegion();
        SpatialRegion twoAway = new SpatialRegion(danger.dimension(), danger.regionX() + 2,
                danger.regionY(), danger.regionZ());

        List<String> lines = SpatialMemoryReport.lines(agent.spatialMemory(), twoAway);
        String text = String.join("\n", lines);

        assertTrue(text.contains("この場所での経験はまだありません"));
        assertTrue(text.contains(SpatialMemoryReport.describeLocation(danger)));
        assertTrue(text.contains("blocks 先"));
        Map.Entry<SpatialRegion, RegionMemory> strongest =
                SpatialMemoryReport.strongestNearbyNegative(agent.spatialMemory(), twoAway);
        assertEquals(danger, strongest.getKey());
    }

    @Test
    @DisplayName("memory spatial：範囲外・別ディメンションの領域は「近く」に含めない")
    void spatialReportIgnoresFarRegions() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        SpatialRegion danger = DemoFixtures.dangerRegion();
        SpatialRegion far = new SpatialRegion(danger.dimension(),
                danger.regionX() + SpatialMemoryReport.NEARBY_REGION_RANGE + 1, danger.regionY(), danger.regionZ());
        SpatialRegion nether = new SpatialRegion("minecraft:the_nether",
                danger.regionX(), danger.regionY(), danger.regionZ());

        assertNull(SpatialMemoryReport.strongestNearbyNegative(agent.spatialMemory(), far));
        assertNull(SpatialMemoryReport.strongestNearbyNegative(agent.spatialMemory(), nether));
    }

    @Test
    @DisplayName("inspect：状態・選好・記憶の内訳・空間記憶・行動を出し、生の UUID は出さない")
    void inspectReport() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        BehaviorDecision last = DemoFixtures.decide(agent, 0, 4).get(3);
        agent.setLearningFrozen(true);

        List<String> lines = WolfInspectReport.lines("A (WOLF-abc123)", agent, last);
        String text = String.join("\n", lines);

        assertEquals("Wolf Subject: A (WOLF-abc123)", lines.get(0));
        assertTrue(text.contains("trust ") && text.contains("attachment ") && text.contains("curiosity "));
        assertTrue(text.contains("Preference: follow_close"));
        assertTrue(text.contains("wolf_damaged x"));
        assertTrue(text.contains("Spatial: 1 regions  dangerous=1"));
        assertTrue(text.contains("Behavior: " + agent.currentBehavior().name()));
        assertTrue(text.contains("[EVALUATION: learning frozen]"));
        assertTrue(text.contains("owner=" + DemoFixtures.ACTOR), "owner appears only as the anonymous id");
        for (String line : lines) {
            assertFalse(UUID_PATTERN.matcher(line).find(), "raw UUID in inspect output: " + line);
        }
    }

    @Test
    @DisplayName("inspect と memory spatial は個体を変更しない")
    void reportsAreReadOnly() {
        WolfAiController controller = new WolfAiController(DemoFixtures.newAgent());
        DemoFixtures.experienceDangerAndRescue(controller.agent());
        BehaviorDecision last = DemoFixtures.decide(controller.agent(), 0, 3).get(2);
        NbtCompound before = WolfAiNbt.write(controller);

        WolfInspectReport.lines("x", controller.agent(), last);
        SpatialMemoryReport.lines(controller.agent().spatialMemory(), DemoFixtures.dangerRegion());
        SpatialMemoryReport.lines(controller.agent().spatialMemory(),
                new SpatialRegion(DemoFixtures.DIMENSION, 0, 0, 0));

        assertEquals(before, WolfAiNbt.write(controller));
    }
}
