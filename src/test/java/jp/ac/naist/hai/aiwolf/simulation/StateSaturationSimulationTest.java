package jp.ac.naist.hai.aiwolf.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 15：天井効果シミュレーションの再現性と指標の検証。 */
final class StateSaturationSimulationTest {

    @Test
    @DisplayName("同じシード・同じパターンなら出来事列も状態の軌跡も完全に一致する")
    void runsAreReproducible() {
        for (StateSaturationSimulation.Pattern pattern : StateSaturationSimulation.Pattern.values()) {
            StateSaturationSimulation.Run a = StateSaturationSimulation.run(pattern,
                    StateSaturationSimulation.Intensity.TYPICAL, 99L, 15);
            StateSaturationSimulation.Run b = StateSaturationSimulation.run(pattern,
                    StateSaturationSimulation.Intensity.TYPICAL, 99L, 15);
            assertEquals(StateSaturationSimulation.schedule(pattern, StateSaturationSimulation.Intensity.TYPICAL, 99L, 15),
                    StateSaturationSimulation.schedule(pattern, StateSaturationSimulation.Intensity.TYPICAL, 99L, 15));
            for (int minute = 0; minute <= 15; minute++) {
                assertArrayEquals(a.minuteStates()[minute], b.minuteStates()[minute], pattern + " minute " + minute);
            }
            assertArrayEquals(a.secondsAtCeiling(), b.secondsAtCeiling());
            assertEquals(a.eventCounts(), b.eventCounts());
        }
    }

    @Test
    @DisplayName("シードが違えば出来事列が変わる")
    void seedsMatter() {
        assertNotEquals(
                StateSaturationSimulation.schedule(StateSaturationSimulation.Pattern.MIXED,
                        StateSaturationSimulation.Intensity.TYPICAL, 1L, 10),
                StateSaturationSimulation.schedule(StateSaturationSimulation.Pattern.MIXED,
                        StateSaturationSimulation.Intensity.TYPICAL, 2L, 10));
    }

    @Test
    @DisplayName("出来事の頻度は 10 秒枠の刻みで表せる範囲（6 回/分以下）で、平均回数は頻度に近い")
    void ratesAreRealisable() {
        for (StateSaturationSimulation.Pattern pattern : StateSaturationSimulation.Pattern.values()) {
            for (StateSaturationSimulation.Intensity intensity : StateSaturationSimulation.Intensity.values()) {
                for (double rate : StateSaturationSimulation.rates(pattern, intensity).values()) {
                    assertTrue(rate > 0.0 && rate <= 6.0, pattern + " " + intensity + " " + rate);
                }
            }
        }
        List<Map.Entry<Long, WolfEventType>> events = StateSaturationSimulation.schedule(
                StateSaturationSimulation.Pattern.OWNER_ORIENTED, StateSaturationSimulation.Intensity.TYPICAL, 5L, 600);
        long pets = events.stream().filter(e -> e.getValue() == WolfEventType.PET).count();
        assertEquals(2.0, pets / 600.0, 0.15, "PET rate is 2/min");
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i - 1).getKey() <= events.get(i).getKey(), "events must be in time order");
        }
    }

    @Test
    @DisplayName("指標の定義：初期値・到達時刻・張り付き時間が軌跡と矛盾しない")
    void metricsAreConsistent() {
        StateSaturationSimulation.Run run = StateSaturationSimulation.run(
                StateSaturationSimulation.Pattern.OWNER_ORIENTED, StateSaturationSimulation.Intensity.INTENSIVE, 3L, 30);
        int attachment = StateParameter.ATTACHMENT.ordinal();
        assertEquals(AiWolfParameters.INITIAL_ATTACHMENT, run.minuteStates()[0][attachment]);
        long reach99 = run.firstReach()[attachment][2];
        assertTrue(reach99 > 0, "intensive owner-oriented rearing must reach 0.99 within 30 min");
        assertTrue(run.firstReach()[attachment][0] <= run.firstReach()[attachment][1]
                && run.firstReach()[attachment][1] <= reach99, "0.90 ≤ 0.95 ≤ 0.99 の順に到達する");
        assertTrue(run.secondsAtCeiling()[attachment] <= run.totalSeconds() - reach99 + 1);
        assertEquals(30L * 60L, run.totalSeconds());
    }

    @Test
    @DisplayName("レポートと CSV に全パターン・全状態・パラメータのバージョンが入る")
    void outputsAreComplete() {
        List<StateSaturationSimulation.Run> runs = List.of(
                StateSaturationSimulation.run(StateSaturationSimulation.Pattern.OWNER_ORIENTED,
                        StateSaturationSimulation.Intensity.TYPICAL, 1L, 2),
                StateSaturationSimulation.run(StateSaturationSimulation.Pattern.EXPLORATION_ORIENTED,
                        StateSaturationSimulation.Intensity.TYPICAL, 1L, 2));
        String csv = StateSaturationSimulation.trajectoryCsv(runs);
        assertEquals(1 + 2 * 3, csv.split("\n").length, "header + 2 runs × (0..2 min)");
        assertTrue(csv.startsWith("parameterSetVersion,pattern,intensity,seed,minute,trust,attachment"));
        String report = StateSaturationSimulation.report(runs, 1, 2);
        assertTrue(report.contains("PARAMETER_SET_VERSION=" + AiWolfParameters.PARAMETER_SET_VERSION));
        for (StateParameter parameter : StateParameter.values()) {
            assertTrue(report.contains("| " + parameter.key() + " |"), parameter.key());
        }
    }
}
