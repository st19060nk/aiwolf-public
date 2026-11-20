package jp.ac.naist.hai.aiwolf.log;

import java.util.List;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScore;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.context.ContextFeature;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AdaptiveFeatures;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.ScoredMemory;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * 行動決定 1 件を JSONL の 1 行へ直列化する。
 *
 * <p>「選ばれた行動」だけでなく、全候補のスコア内訳・確率・乱数値・内部状態を
 * 保存することで、Event → State → Memory → Preference → Behavior という因果連鎖を
 * ログだけから完全に追跡できる。</p>
 *
 * <p>プレイヤー名などの個人情報は保存しない。飼い主は匿名化済みの研究用 ID
 * （{@code subjectId}）として渡すこと。</p>
 *
 * <p>Minecraft に依存しないため、出力形式を単体テストで固定できる。</p>
 */
public final class DecisionLogJson {

    private DecisionLogJson() {
        throw new AssertionError("utility class");
    }

    /**
     * 決定ログの追加項目（Phase 19）。すべて {@code null} 可で、{@code null} の項目は書かない。
     *
     * @param wolfSubjectId 個体の匿名 ID（{@code wolf-} で始まる）
     * @param context       決定時の文脈
     * @param retrieved     検索された記憶（検索スコア順）
     * @param preferences   決定時の学習済み選好
     */
    public record Extras(String wolfSubjectId, WolfContextSnapshot context, List<ScoredMemory> retrieved,
                         PreferenceVector preferences) {

        /** 追加項目なし（旧来の呼び出し用）。 */
        public static final Extras NONE = new Extras(null, null, null, null);
    }

    /**
     * 決定 1 件を JSONL の 1 行として直列化する。
     *
     * @param wolfUuid       個体 UUID
     * @param subjectId      匿名化済みの飼い主 ID（{@code null} 可）
     * @param condition      実験条件
     * @param experimentSeed 実験シード
     * @param randomSeed     個体固有シード
     * @param worldTime      ワールド時刻 [ticks]
     * @param epochMillis    実時刻（epoch ミリ秒）
     * @param dimension      ディメンション ID
     * @param biome          バイオーム ID
     * @param x              X 座標
     * @param y              Y 座標
     * @param z              Z 座標
     * @param state          決定時点の内部状態
     * @param decision       決定記録
     * @param relevantMemoryIds 参照された記憶 ID
     * @return 改行を含まない JSON 1 行
     */
    public static String toJsonLine(String wolfUuid,
                                    String subjectId,
                                    ExperimentalCondition condition,
                                    long experimentSeed,
                                    long randomSeed,
                                    long worldTime,
                                    long epochMillis,
                                    String dimension,
                                    String biome,
                                    double x,
                                    double y,
                                    double z,
                                    WolfInternalState state,
                                    BehaviorDecision decision,
                                    List<String> relevantMemoryIds) {
        return toJsonLine(wolfUuid, subjectId, condition, experimentSeed, randomSeed, worldTime,
                epochMillis, dimension, biome, x, y, z, state, decision, relevantMemoryIds, Extras.NONE);
    }

    /**
     * 決定 1 件を JSON 1 行にする（Phase 19 の追加項目つき）。
     *
     * @param extras 追加項目（{@link Extras#NONE} なら旧来と同じ項目だけ）
     * @return JSON 1 行
     */
    public static String toJsonLine(String wolfUuid,
                                    String subjectId,
                                    ExperimentalCondition condition,
                                    long experimentSeed,
                                    long randomSeed,
                                    long worldTime,
                                    long epochMillis,
                                    String dimension,
                                    String biome,
                                    double x,
                                    double y,
                                    double z,
                                    WolfInternalState state,
                                    BehaviorDecision decision,
                                    List<String> relevantMemoryIds,
                                    Extras extras) {
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "decision")
                .property("schemaVersion", AiWolfParameters.PARAMETER_SET_VERSION)
                .property("decisionLogSchemaVersion", AiWolfParameters.DECISION_LOG_SCHEMA_VERSION)
                .property("parameterSetVersion", AiWolfParameters.PARAMETER_SET_VERSION)
                .property("epochMillis", epochMillis)
                .property("worldTime", worldTime)
                .property("wolfUuid", wolfUuid)
                .property("subjectId", subjectId)
                .property("experimentalCondition", condition.id())
                .property("experimentSeed", experimentSeed)
                .property("randomSeed", randomSeed)
                .property("decisionIndex", decision.decisionIndex())
                .property("dimension", dimension)
                .property("biome", biome)
                .property("x", round(x))
                .property("y", round(y))
                .property("z", round(z))
                .property("selectedBehavior", decision.selected().id())
                .property("selectedProbability", round(decision.selectedProbability()))
                .property("randomValue", round(decision.randomValue()))
                .property("temperature", round(decision.temperature()));

        if (extras.wolfSubjectId() != null) {
            writer.property("wolfSubjectId", extras.wolfSubjectId());
        }
        writeFeatures(writer, condition.features());

        writer.beginObject("state");
        for (StateParameter parameter : StateParameter.ALL) {
            writer.property(parameter.key(), round(state.get(parameter)));
        }
        writer.endObject();

        if (extras.preferences() != null) {
            writer.beginObject("preferences");
            for (BehaviorType behavior : BehaviorType.ALL) {
                writer.property(behavior.id(), round(extras.preferences().get(behavior)));
            }
            writer.endObject();
        }
        if (extras.context() != null) {
            writeContext(writer, extras.context());
        }
        if (extras.retrieved() != null) {
            writer.beginArray("retrievedMemories");
            for (ScoredMemory scored : extras.retrieved()) {
                writer.beginObject()
                        .property("memoryId", scored.memory().memoryId())
                        .property("eventType", scored.memory().type().id())
                        .property("worldTime", scored.memory().worldTime())
                        .property("score", round(scored.score()))
                        .property("recency", round(scored.recency()))
                        .property("salience", round(scored.salience()))
                        .property("relevance", round(scored.relevance()))
                        .endObject();
            }
            writer.endArray();
        }

        writer.beginArray("relevantMemoryIds");
        for (String memoryId : relevantMemoryIds) {
            writer.value(memoryId);
        }
        writer.endArray();

        writer.beginArray("behaviorScores");
        for (Map.Entry<BehaviorType, BehaviorScore> entry : decision.scores().entrySet()) {
            BehaviorScore score = entry.getValue();
            Double probability = decision.probabilities().get(entry.getKey());
            writer.beginObject()
                    .property("behavior", entry.getKey().id())
                    .property("base", round(score.base()))
                    .property("state", round(score.state()))
                    .property("context", round(score.context()))
                    .property("contextSpatialMemory", round(score.spatialMemory()))
                    .property("contextEmergency", round(score.emergency()))
                    .property("contextOther", round(score.contextExcludingMemoryAndEmergency()))
                    .property("memory", round(score.memory()))
                    .property("preference", round(score.preference()))
                    .property("persistence", round(score.persistence()))
                    .property("penalty", round(score.penalty()))
                    .property("finalScore", round(score.finalScore()))
                    .property("componentSum", round(score.componentSum()))
                    .property("probability", probability == null ? 0.0 : round(probability))
                    .property("selected", entry.getKey() == decision.selected())
                    .endObject();
        }
        writer.endArray();

        writer.endObject();
        return writer.toJson();
    }

    /** どの適応機構が有効だったか。条件 ID だけでなく中身も残す（条件の定義が将来変わっても読めるように）。 */
    private static void writeFeatures(JsonWriter writer, AdaptiveFeatures features) {
        writer.beginObject("adaptiveFeatures")
                .property("stateLearning", features.stateLearning())
                .property("episodicMemory", features.episodicMemory())
                .property("spatialMemory", features.spatialMemory())
                .property("preferenceLearning", features.preferenceLearning())
                .endObject();
    }

    /** 決定時の文脈：生の値と、スコア計算に使った正規化済みの特徴量の両方。 */
    private static void writeContext(JsonWriter writer, WolfContextSnapshot context) {
        writer.beginObject("context")
                .property("ownerPresent", context.ownerPresent())
                .property("ownerDistance", round(context.ownerDistance()))
                .property("wolfHealthRatio", round(context.wolfHealthRatio()))
                .property("ownerHealthRatio", round(context.ownerHealthRatio()))
                .property("hostileMobCount", context.hostileMobCount())
                .property("nearestHostileDistance", round(context.nearestHostileDistance()))
                .property("night", context.night())
                .property("underground", context.underground())
                .property("ownerMoving", context.ownerMoving())
                .property("regionFear", round(context.regionFear()))
                .property("recentCombat", round(context.recentCombat()))
                .property("ownerUnderAttack", context.ownerUnderAttack())
                .property("wolfUnderAttack", context.wolfUnderAttack())
                .endObject();
        writer.beginObject("contextFeatures");
        for (Map.Entry<ContextFeature, Double> feature : context.toFeatureVector().entrySet()) {
            writer.property(feature.getKey().name().toLowerCase(java.util.Locale.ROOT), round(feature.getValue()));
        }
        writer.endObject();
    }

    private static double round(double value) {
        return MathSupport.round(value, AiWolfParameters.EXPORT_DECIMALS);
    }
}
