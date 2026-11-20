package jp.ac.naist.hai.aiwolf.log;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.study.StudySession;

/**
 * 研究ログの全行に共通する項目（封筒）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>ログの行を作るクラス（決定・出来事・被弾・指紋）はそれぞれ別だが、
 * 「どの実行（サーバ起動）で記録されたか」はすべての行に必要なので、
 * {@link ResearchLogger} が書き出す直前にここで 1 か所だけ付ける。</p>
 */
public final class LogEnvelope {

    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);

    private LogEnvelope() {
        throw new AssertionError("utility class");
    }

    /**
     * 新しい実行 ID を作る。サーバ起動ごとに 1 つ。
     *
     * <p>形式：{@code run-20261114T071530Z-a1b2c3}（UTC の起動時刻 + 乱数 6 桁）。
     * 時刻だけだと同じ秒に起動した 2 台のサーバで衝突するため乱数を付ける。
     * 個人を特定する情報は含まない。</p>
     *
     * @param now 起動時刻
     * @return 実行 ID
     */
    public static String newRunId(ZonedDateTime now) {
        return "run-" + RUN_TIME.format(now.withZoneSameInstant(ZoneOffset.UTC)) + "-"
                + String.format(Locale.ROOT, "%06x", ThreadLocalRandom.current().nextInt(1 << 24));
    }

    /**
     * 研究ログ 1 行に共通項目を付ける（{@link ResearchLogger} が全行に使う）。
     *
     * <p>先頭に {@code logEnvelopeVersion}、{@code runId}、{@code sessionId}、{@code phaseId}、{@code logSequence} を足す。
     * セッションがなければ {@code sessionId} と {@code phaseId} は JSON の {@code null}、
     * セッション中でもフェーズ未設定なら {@code phaseId} は {@code null}。
     * 項目を省略せず常に書くので、解析側で列がそろう。</p>
     *
     * <p>{@code logSequence}（Phase 25B）は実行（{@code runId}）の中で、<strong>ファイルの種類をまたいで</strong>
     * 単調増加する通し番号。同じ tick に別ファイルへ書かれた行の前後関係は {@code worldTime} だけでは分からないので、
     * 解析では {@code runId} → {@code logSequence} の順に並べれば記録した順序が復元できる。</p>
     *
     * @param jsonLine    1 行の JSON オブジェクト
     * @param runId       実行 ID
     * @param session     進行中のセッション（{@code null} 可）
     * @param logSequence 実行内の通し番号（1 から）
     * @return 共通項目を付けた行
     */
    public static String envelope(String jsonLine, String runId, StudySession.Active session, long logSequence) {
        if (jsonLine == null || !jsonLine.startsWith("{")) {
            return jsonLine;
        }
        StringBuilder prefix = new StringBuilder("{\"logEnvelopeVersion\":")
                .append(AiWolfParameters.LOG_ENVELOPE_VERSION)
                .append(",\"runId\":").append(quoteOrNull(runId))
                .append(",\"sessionId\":").append(quoteOrNull(session == null ? null : session.sessionId()))
                .append(",\"phaseId\":").append(quoteOrNull(session == null ? null : session.phaseId()))
                .append(",\"logSequence\":").append(logSequence);
        String rest = jsonLine.substring(1);
        return rest.startsWith("}") ? prefix + rest : prefix + "," + rest;
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : JsonWriter.quote(value);
    }

    /**
     * JSON オブジェクト 1 行の先頭に項目を足す。既存の項目は変えない。
     *
     * @param jsonLine 1 行の JSON オブジェクト
     * @param fields   足す項目（名前 → 文字列値）。値が {@code null} の項目は足さない
     * @return 項目を足した行。JSON オブジェクトでなければそのまま返す
     */
    public static String prepend(String jsonLine, Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields");
        if (jsonLine == null || !jsonLine.startsWith("{")) {
            return jsonLine;
        }
        StringBuilder prefix = new StringBuilder("{");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getValue() == null) {
                continue;
            }
            if (prefix.length() > 1) {
                prefix.append(',');
            }
            prefix.append(JsonWriter.quote(field.getKey())).append(':').append(JsonWriter.quote(field.getValue()));
        }
        if (prefix.length() == 1) {
            return jsonLine;
        }
        String rest = jsonLine.substring(1);
        return rest.startsWith("}") ? prefix + rest : prefix + "," + rest;
    }
}
