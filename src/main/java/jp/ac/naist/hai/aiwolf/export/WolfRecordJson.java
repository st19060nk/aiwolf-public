package jp.ac.naist.hai.aiwolf.export;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.memory.RegionMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * 個体の現在状態を {@code wolves.json} として直列化する。
 *
 * <p>Minecraft に依存しないため、出力形式を単体テストで固定できる。
 * 出力スキーマは README の "Research data schema" と対応する。
 * 個人情報は一切含めない（プレイヤー名ではなく匿名化済みの研究 ID のみを扱う）。</p>
 */
public final class WolfRecordJson {

    /** 出力スキーマのバージョン。構造を変えたらインクリメントする。 */
    public static final int SCHEMA_VERSION = 2;

    private WolfRecordJson() {
        throw new AssertionError("utility class");
    }

    /**
     * {@code wolves.json} の内容を生成する。
     *
     * @param agents                個体群
     * @param worldTime             エクスポート時のワールド時刻 [ticks]
     * @param exportedAtEpochMillis エクスポート時刻（epoch ミリ秒）
     * @param experimentSeed        実験シード
     * @return JSON 文字列
     */
    public static String toWolvesJson(Collection<WolfAiAgent> agents,
                                      long worldTime,
                                      long exportedAtEpochMillis,
                                      long experimentSeed) {
        JsonWriter writer = JsonWriter.pretty();
        writer.beginObject()
                .property("schemaVersion", SCHEMA_VERSION)
                .property("parameterSetVersion", AiWolfParameters.PARAMETER_SET_VERSION)
                .property("exportedAtEpochMillis", exportedAtEpochMillis)
                .property("exportedAtIso", Instant.ofEpochMilli(exportedAtEpochMillis).toString())
                .property("worldTime", worldTime)
                .property("experimentSeed", experimentSeed)
                .property("wolfCount", agents.size())
                .beginArray("wolves");
        for (WolfAiAgent agent : agents) {
            writeAgent(writer, agent);
        }
        writer.endArray().endObject();
        return writer.toJson();
    }

    /**
     * 個体 1 件を現在のコンテキストへ書き出す。
     *
     * @param writer 書き出し先
     * @param agent  個体
     */
    public static void writeAgent(JsonWriter writer, WolfAiAgent agent) {
        writer.beginObject()
                .property("wolfUuid", agent.wolfId())
                .property("ownerResearchId", agent.ownerResearchId())
                .property("experimentalCondition", agent.condition().id())
                .property("randomSeed", agent.random().seed())
                .property("decisionIndex", agent.random().decisionIndex())
                .property("createdAtEpochMillis", agent.createdAtEpochMillis())
                .property("lastUpdatedEpochMillis", agent.lastUpdatedEpochMillis())
                .property("memoryCount", agent.memoryStore().size())
                .property("regionCount", agent.spatialMemory().size());

        writer.beginObject("state");
        for (Map.Entry<String, Double> entry : agent.state().toValueMap().entrySet()) {
            writer.property(entry.getKey(), round(entry.getValue()));
        }
        writer.endObject();

        writer.beginObject("learnedPreferences");
        for (Map.Entry<String, Double> entry : agent.preferences().toValueMap().entrySet()) {
            writer.property(entry.getKey(), round(entry.getValue()));
        }
        writer.endObject();

        writer.beginArray("spatialMemory");
        for (Map.Entry<SpatialRegion, RegionMemory> entry : agent.spatialMemory().regions().entrySet()) {
            RegionMemory region = entry.getValue();
            writer.beginObject()
                    .property("region", entry.getKey().key())
                    .property("dimension", entry.getKey().dimension())
                    .property("regionX", entry.getKey().regionX())
                    .property("regionY", entry.getKey().regionY())
                    .property("regionZ", entry.getKey().regionZ())
                    .property("fearValue", round(region.fearValue()))
                    .property("positiveValue", round(region.positiveValue()))
                    .property("effectiveFear", round(region.effectiveFear()))
                    .property("visitCount", region.visitCount())
                    .property("lastVisitedWorldTime", region.lastVisitedWorldTime())
                    .endObject();
        }
        writer.endArray();

        writer.endObject();
    }

    private static double round(double value) {
        return MathSupport.round(value, AiWolfParameters.EXPORT_DECIMALS);
    }
}
