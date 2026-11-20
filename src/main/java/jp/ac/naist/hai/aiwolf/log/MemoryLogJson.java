package jp.ac.naist.hai.aiwolf.log;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;

/**
 * エピソード記憶 1 件を JSONL の 1 行へ直列化する。{@code memories.jsonl} 用。
 *
 * <p>研究ログの {@code relevantMemoryIds} と {@code memoryId} で突き合わせられるため、
 * 「どの記憶がどの決定に効いたか」を解析側で復元できる。</p>
 */
public final class MemoryLogJson {

    private MemoryLogJson() {
        throw new AssertionError("utility class");
    }

    /**
     * 記憶を JSONL の 1 行として直列化する。
     *
     * @param wolfUuid 個体 UUID
     * @param memory   記憶
     * @return 改行を含まない JSON 1 行
     */
    public static String toJsonLine(String wolfUuid, WolfMemory memory) {
        return JsonWriter.compact()
                .beginObject()
                .property("recordType", "memory")
                .property("schemaVersion", AiWolfParameters.PARAMETER_SET_VERSION)
                .property("wolfUuid", wolfUuid)
                .property("memoryId", memory.memoryId())
                .property("worldTime", memory.worldTime())
                .property("eventType", memory.type().id())
                .property("dimension", memory.dimension())
                .property("biome", memory.biome())
                .property("region", memory.region().key())
                .property("x", round(memory.x()))
                .property("y", round(memory.y()))
                .property("z", round(memory.z()))
                .property("actorId", memory.actorId())
                .property("wolfHealthRatio", round(memory.wolfHealthRatio()))
                .property("playerHealthRatio", round(memory.playerHealthRatio()))
                .property("valence", round(memory.valence()))
                .property("arousal", round(memory.arousal()))
                .property("salience", round(memory.salience()))
                .endObject()
                .toJson();
    }

    private static double round(double value) {
        return MathSupport.round(value, AiWolfParameters.EXPORT_DECIMALS);
    }
}
