package jp.ac.naist.hai.aiwolf.study;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;

/**
 * {@code ai-availability.jsonl} の 1 行（AI の停止・再開）。<strong>Minecraft に依存しない。</strong>
 */
public final class AiAvailabilityLogJson {

    private AiAvailabilityLogJson() {
        throw new AssertionError("utility class");
    }

    /**
     * 1 行を作る。
     *
     * @param transition       変化
     * @param subjectId        飼い主の匿名 ID（未設定なら {@code null}）
     * @param wolfSubjectId    個体の匿名 ID
     * @param condition        実験条件
     * @param session          進行中のセッション（{@code null} 可）
     * @param worldTime        ワールド時刻
     * @param epochMillis      現在時刻
     * @param lastDecisionTick 最後の決定のワールド時刻（未決定なら {@link Long#MIN_VALUE}）
     * @return JSON 1 行
     */
    public static String toJsonLine(AiAvailability.Transition transition, String subjectId, String wolfSubjectId,
                                    ExperimentalCondition condition, StudySession.Active session,
                                    long worldTime, long epochMillis, long lastDecisionTick) {
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "ai_availability")
                .property("aiAvailabilityLogSchemaVersion", AiWolfParameters.AI_AVAILABILITY_LOG_SCHEMA_VERSION)
                .property("event", transition.event());
        if (transition.reason() != null) {
            writer.property("reason", transition.reason().name());
        }
        if (transition.previousReason() != null) {
            writer.property("previousReason", transition.previousReason().name());
        }
        writer.property("subjectId", subjectId)
                .property("wolfSubjectId", wolfSubjectId)
                .property("experimentalCondition", condition.id())
                .property("epochMillis", epochMillis)
                .property("worldTime", worldTime)
                .property("suspendedSinceWorldTime", transition.suspendedSinceTick());
        if (session != null) {
            writer.property("elapsedTicksFromSessionStart", session.elapsedTicks(worldTime));
        }
        if (transition.suspendedDurationTicks() != null) {
            writer.property("suspendedDurationTicks", transition.suspendedDurationTicks());
        }
        if (lastDecisionTick != Long.MIN_VALUE) {
            writer.property("lastDecisionWorldTime", lastDecisionTick);
        }
        writer.endObject();
        return writer.toJson();
    }
}
