package jp.ac.naist.hai.aiwolf.log;

import java.util.Map;
import java.util.TreeSet;

import jp.ac.naist.hai.aiwolf.agent.EventOutcome;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.state.StateParameter;

/**
 * 出来事 1 件を JSONL の 1 行へ直列化する。
 *
 * <p>出来事そのものと、適用の<strong>前後</strong>の内部状態・選好を残す。
 * 適用後の値だけでは「その出来事がどれだけ効いたか」を測れない。
 * trust が 0.60 になったと分かっても、0.52 から上がったのか 0.59 から上がったのかで
 * 意味がまるで違い、飽和しかけた個体では後者ばかりになるためである。</p>
 *
 * <p>差分（{@code stateDelta} / {@code preferenceDelta}）も併せて書く。
 * 解析側で引き算できる冗長な情報だが、記録漏れや二重適用をログ内で検算できる。</p>
 *
 * <p>戦闘由来のイベントには {@code metadata} として combatId / hostileType / hostileId /
 * combatTrigger を添える。combatId があれば「1 回の戦闘から何件のイベントが発火したか」を
 * 解析側で検証できる。</p>
 *
 * <p>飼い主は匿名化済みの研究 ID としてのみ記録する。metadata に個人情報を入れてはならない。</p>
 */
public final class EventLogJson {

    private EventLogJson() {
        throw new AssertionError("utility class");
    }

    /**
     * 出来事を JSONL の 1 行として直列化する。
     *
     * @param wolfUuid    個体 UUID
     * @param subjectId   匿名化済みの飼い主 ID（{@code null} 可）
     * @param condition   実験条件
     * @param event       出来事
     * @param outcome     出来事が個体へ及ぼした変化
     * @param epochMillis 実時刻（epoch ミリ秒）
     * @return 改行を含まない JSON 1 行
     */
    public static String toJsonLine(String wolfUuid,
                                    String subjectId,
                                    ExperimentalCondition condition,
                                    WolfEvent event,
                                    EventOutcome outcome,
                                    long epochMillis) {
        return toJsonLine(wolfUuid, subjectId, condition, event, outcome, epochMillis, Extras.NONE);
    }

    /**
     * 出来事ログの追加項目（Phase 19）。{@code null} の項目は書かない。
     *
     * @param wolfSubjectId  個体の匿名 ID
     * @param experimentSeed 実験シード（{@code null} 可）
     * @param randomSeed     個体の乱数シード（{@code null} 可）
     */
    public record Extras(String wolfSubjectId, Long experimentSeed, Long randomSeed) {

        /** 追加項目なし。 */
        public static final Extras NONE = new Extras(null, null, null);
    }

    /**
     * 出来事 1 件を JSON 1 行にする（Phase 19 の追加項目つき）。
     *
     * @param extras 追加項目
     * @return JSON 1 行
     */
    public static String toJsonLine(String wolfUuid,
                                    String subjectId,
                                    ExperimentalCondition condition,
                                    WolfEvent event,
                                    EventOutcome outcome,
                                    long epochMillis,
                                    Extras extras) {
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "event")
                .property("schemaVersion", AiWolfParameters.EVENT_LOG_SCHEMA_VERSION)
                .property("parameterSetVersion", AiWolfParameters.PARAMETER_SET_VERSION)
                .property("epochMillis", epochMillis)
                .property("worldTime", event.worldTime())
                .property("wolfUuid", wolfUuid)
                .property("subjectId", subjectId)
                .property("experimentalCondition", condition.id())
                .property("eventType", event.type().id())
                .property("dimension", event.dimension())
                .property("biome", event.biome())
                .property("region", event.region().key())
                .property("x", round(event.x()))
                .property("y", round(event.y()))
                .property("z", round(event.z()))
                .property("actorId", event.actorId())
                .property("wolfHealthRatio", round(event.wolfHealthRatio()))
                .property("playerHealthRatio", round(event.playerHealthRatio()))
                .property("valence", round(event.valence()))
                .property("arousal", round(event.arousal()))
                .property("salience", round(event.salience()))
                .property("createdMemoryId", outcome.createdMemoryId());
        if (extras.wolfSubjectId() != null) {
            writer.property("wolfSubjectId", extras.wolfSubjectId());
        }
        if (extras.experimentSeed() != null) {
            writer.property("experimentSeed", extras.experimentSeed());
        }
        if (extras.randomSeed() != null) {
            writer.property("randomSeed", extras.randomSeed());
        }
        writer.beginObject("adaptiveFeatures")
                .property("stateLearning", condition.features().stateLearning())
                .property("episodicMemory", condition.features().episodicMemory())
                .property("spatialMemory", condition.features().spatialMemory())
                .property("preferenceLearning", condition.features().preferenceLearning())
                .endObject();

        // 戦闘由来のイベントは combatId でひとまとめにできる。これがないと
        // 「1 回の戦闘から何件のイベントが出たか」をログから検証できず、
        // 二重計上が起きても気づけない。
        if (!event.metadata().isEmpty()) {
            writer.beginObject("metadata");
            for (String key : new TreeSet<>(event.metadata().keySet())) {
                writer.property(key, event.metadata().get(key));
            }
            writer.endObject();
        }

        writeStates(writer, "stateBefore", outcome.stateBefore());
        writeStates(writer, "stateAfter", outcome.stateAfter());
        writeStates(writer, "stateDelta", outcome.stateDelta());
        writePreferences(writer, "preferenceBefore", outcome.preferenceBefore());
        writePreferences(writer, "preferenceAfter", outcome.preferenceAfter());
        writePreferences(writer, "preferenceDelta", outcome.preferenceDelta());

        writer.endObject();
        return writer.toJson();
    }

    private static void writeStates(JsonWriter writer, String name,
                                    Map<StateParameter, Double> values) {
        writer.beginObject(name);
        for (StateParameter parameter : StateParameter.ALL) {
            writer.property(parameter.key(), round(values.getOrDefault(parameter, 0.0)));
        }
        writer.endObject();
    }

    private static void writePreferences(JsonWriter writer, String name,
                                         Map<BehaviorType, Double> values) {
        writer.beginObject(name);
        for (BehaviorType behavior : BehaviorType.values()) {
            writer.property(behavior.id(), round(values.getOrDefault(behavior, 0.0)));
        }
        writer.endObject();
    }

    private static double round(double value) {
        return MathSupport.round(value, AiWolfParameters.EXPORT_DECIMALS);
    }
}
