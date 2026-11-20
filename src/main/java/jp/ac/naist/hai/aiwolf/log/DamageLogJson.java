package jp.ac.naist.hai.aiwolf.log;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.event.DamageRecord;

/**
 * 被弾 1 回を JSONL の 1 行へ直列化する。{@code damage.jsonl} 用。
 *
 * <p><strong>これは学習用の出来事ログではない。</strong>研究上の出来事
 * （{@code WOLF_DAMAGED} など）は決定ごとに最大 1 件へ集約されるが、
 * こちらは物理的な被弾 1 発につき 1 行を残す。集約の前に何が起きていたかを
 * 後から検証できるようにするためで、内部状態も選好もここからは動かない。</p>
 *
 * <p>ダメージの意味を毎行 {@code damageDefinition} として書く。
 * 解析の時点で「この damageAmount は何だったのか」が分からなくなるのを防ぐためである。</p>
 *
 * <p>プレイヤー名も UUID も残さない。飼い主は匿名化済みの研究 ID としてのみ記録する。</p>
 */
public final class DamageLogJson {

    private DamageLogJson() {
        throw new AssertionError("utility class");
    }

    /**
     * 被弾を JSONL の 1 行として直列化する。
     *
     * @param wolfUuid  観測した個体の UUID
     * @param subjectId 匿名化済みの飼い主 ID（{@code null} 可）
     * @param condition 実験条件
     * @param record    被弾の記録
     * @return 改行を含まない JSON 1 行
     */
    public static String toJsonLine(String wolfUuid, String subjectId,
                                    ExperimentalCondition condition, DamageRecord record) {
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "damage")
                .property("schemaVersion", AiWolfParameters.EVENT_LOG_SCHEMA_VERSION)
                .property("damageDefinition", DamageRecord.DAMAGE_DEFINITION)
                .property("worldTime", record.worldTime())
                .property("wolfUuid", wolfUuid)
                .property("subjectId", subjectId)
                .property("experimentalCondition", condition.id())
                .property("targetType", record.targetType())
                .property("attackerType", record.attackerType())
                .property("attackerId", record.attackerId())
                .property("directSourceType", record.directSourceType())
                .property("causingSourceType", record.causingSourceType())
                .property("actualDamage", round(record.actualDamage()))
                .property("healthLoss", round(record.healthLoss()))
                .property("absorptionLoss", round(record.absorptionLoss()))
                .property("healthBefore", round(record.healthBefore()))
                .property("healthAfter", round(record.healthAfter()))
                .property("absorptionBefore", round(record.absorptionBefore()))
                .property("absorptionAfter", round(record.absorptionAfter()))
                .property("fatal", record.fatal())
                .property("combatId", record.combatId())
                .endObject();
        return writer.toJson();
    }

    private static double round(double value) {
        return MathSupport.round(value, AiWolfParameters.EXPORT_DECIMALS);
    }
}
