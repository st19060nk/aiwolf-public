package jp.ac.naist.hai.aiwolf.study;

import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.BuildInfo;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;

/**
 * {@code sessions.jsonl} の 1 行（セッションの開始・終了・フェーズ変更）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>封筒の {@code sessionId} / {@code phaseId} は {@code ResearchLogger} が付けるが、
 * 開始・終了の行では封筒の値（記録時点の状態）だけだと境界が分かりにくいので、
 * 対象のセッション ID・ラベル・フェーズの前後をこの行自身にも書く。</p>
 */
public final class SessionLogJson {

    /** セッション開始。 */
    public static final String SESSION_START = "SESSION_START";
    /** セッション終了。 */
    public static final String SESSION_STOP = "SESSION_STOP";
    /** フェーズ変更（解除を含む）。 */
    public static final String PHASE_CHANGED = "PHASE_CHANGED";

    private SessionLogJson() {
        throw new AssertionError("utility class");
    }

    /**
     * 1 行を作る。
     *
     * @param event       {@link #SESSION_START} など
     * @param session     対象のセッション
     * @param worldTime   ワールド時刻 [ticks]
     * @param epochMillis 現在時刻
     * @param details     追加の項目（文字列 → 文字列 / 数値 / 真偽値 / {@code null}）。順序どおりに書く
     * @return JSON 1 行
     */
    public static String toJsonLine(String event, StudySession.Active session, long worldTime, long epochMillis,
                                    Map<String, Object> details) {
        Objects.requireNonNull(session, "session");
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "session")
                .property("sessionLogSchemaVersion", AiWolfParameters.SESSION_LOG_SCHEMA_VERSION)
                .property("event", event)
                .property("targetSessionId", session.sessionId())
                .property("sessionLabel", session.label())
                .property("epochMillis", epochMillis)
                .property("worldTime", worldTime)
                .property("sessionStartWorldTime", session.startWorldTime())
                .property("elapsedTicksFromSessionStart", session.elapsedTicks(worldTime));
        if (SESSION_START.equals(event)) {
            writeVersions(writer, BuildInfo.current(), StudySession.protocolVersion());
        }
        writeDetails(writer, details);
        writer.endObject();
        return writer.toJson();
    }

    /**
     * 実験に使ったソフトウェアと手順の版を書く（Phase 25A、SESSION_START の行だけ）。
     *
     * <p>同じ {@code parameterSetVersion} の中でもコードは直されうるので、Git の commit（{@code buildRevision}）と、
     * ビルド時に未コミットの変更があったか（{@code buildDirty}）も残す。取得できなかった値は {@code UNKNOWN} と書き、
     * 省略しない。</p>
     *
     * @param writer          書き出し先
     * @param build           ビルドの版
     * @param protocolVersion 実験手順の版
     */
    static void writeVersions(JsonWriter writer, BuildInfo build, int protocolVersion) {
        writer.property("modVersion", build.modVersion())
                .property("buildRevision", build.buildRevision())
                .property("buildDirty", build.buildDirty())
                .property("protocolVersion", protocolVersion)
                .property("parameterSetVersion", AiWolfParameters.PARAMETER_SET_VERSION);
    }

    /**
     * セッションに関わる操作 1 件（割付・記憶条件の切り替え・Snapshot・評価モード）。
     * セッションの外で行われた操作（割付の設定など）も記録するため、{@code session} は {@code null} でもよい。
     *
     * @param event       操作の種類（{@code ASSIGNMENT_SET} など）
     * @param session     進行中のセッション（{@code null} 可）
     * @param worldTime   ワールド時刻
     * @param epochMillis 現在時刻
     * @param details     追加の項目
     * @return JSON 1 行
     */
    public static String controlLine(String event, StudySession.Active session, long worldTime, long epochMillis,
                                     Map<String, Object> details) {
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "study_control")
                .property("sessionLogSchemaVersion", AiWolfParameters.SESSION_LOG_SCHEMA_VERSION)
                .property("event", event)
                .property("epochMillis", epochMillis)
                .property("worldTime", worldTime);
        if (session != null) {
            writer.property("elapsedTicksFromSessionStart", session.elapsedTicks(worldTime));
        }
        writeDetails(writer, details);
        writer.endObject();
        return writer.toJson();
    }

    /**
     * 詳細の項目を書く。値の型に応じて JSON の型を選ぶ（{@code null} は省略）。
     *
     * @param writer  書き出し先
     * @param details 項目
     */
    static void writeDetails(JsonWriter writer, Map<String, Object> details) {
        if (details == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof Boolean bool) {
                writer.property(entry.getKey(), bool);
            } else if (value instanceof Integer || value instanceof Long) {
                writer.property(entry.getKey(), ((Number) value).longValue());
            } else if (value instanceof Number number) {
                writer.property(entry.getKey(), number.doubleValue());
            } else {
                writer.property(entry.getKey(), value.toString());
            }
        }
    }
}
