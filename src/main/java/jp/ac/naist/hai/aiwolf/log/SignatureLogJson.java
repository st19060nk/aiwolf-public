package jp.ac.naist.hai.aiwolf.log;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.simulation.BehaviouralSignature;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * 行動指紋 1 件を JSONL の 1 行へ直列化する。{@code signatures.jsonl} 用。
 *
 * <p>測定時点の内部状態も一緒に残す。「どの状態のときにどの指紋だったか」を
 * 対応付けられるので、状態の変化と行動傾向の変化を突き合わせて解析できる。</p>
 */
public final class SignatureLogJson {

    private SignatureLogJson() {
        throw new AssertionError("utility class");
    }

    /**
     * 指紋を JSONL の 1 行として直列化する。
     *
     * @param wolfUuid     個体 UUID
     * @param subjectId    匿名化済みの飼い主 ID（{@code null} 可）
     * @param condition    実験条件
     * @param label        測定に付けるラベル（フェーズ名など。{@code null} 可）
     * @param sessionIndex 測定セッション番号
     * @param worldTime    測定時のワールド時刻
     * @param epochMillis  実時刻（epoch ミリ秒）
     * @param state        測定時点の内部状態
     * @param signature    行動指紋
     * @return 改行を含まない JSON 1 行
     */
    public static String toJsonLine(String wolfUuid,
                                    String subjectId,
                                    ExperimentalCondition condition,
                                    String label,
                                    long sessionIndex,
                                    long worldTime,
                                    long epochMillis,
                                    WolfInternalState state,
                                    BehaviouralSignature signature) {
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "signature")
                .property("schemaVersion", AiWolfParameters.PARAMETER_SET_VERSION)
                .property("epochMillis", epochMillis)
                .property("worldTime", worldTime)
                .property("wolfUuid", wolfUuid)
                .property("subjectId", subjectId)
                .property("experimentalCondition", condition.id())
                .property("label", label)
                .property("sessionIndex", sessionIndex)
                .property("sampleCount", signature.sampleCount())
                .property("meanOwnerDistance", round(signature.meanOwnerDistance()));

        writer.beginObject("behaviourProportions");
        for (BehaviorType behavior : BehaviorType.ALL) {
            writer.property(behavior.id(), round(signature.proportionOf(behavior)));
        }
        writer.endObject();

        writer.beginObject("state");
        for (StateParameter parameter : StateParameter.ALL) {
            writer.property(parameter.key(), round(state.get(parameter)));
        }
        writer.endObject();

        writer.endObject();
        return writer.toJson();
    }

    private static double round(double value) {
        return MathSupport.round(value, AiWolfParameters.EXPORT_DECIMALS);
    }
}
