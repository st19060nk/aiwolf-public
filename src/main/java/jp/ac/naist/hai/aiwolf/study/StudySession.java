package jp.ac.naist.hai.aiwolf.study;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Human Study の実験セッション（Phase 20）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>{@code runId} はサーバ起動単位なので、同じ起動の中で「セッション 1 → 休憩 → セッション 2」を
 * 行うと区別できない。実験者が {@code /wolfai session start <label>} で区間を明示し、
 * その間の全研究ログに {@code sessionId} と {@code phaseId} を付ける。</p>
 *
 * <ul>
 *   <li>{@code sessionId} — 内部で生成する ID（{@code session-20261114T120000Z-a1b2c3}）。解析の結合キー</li>
 *   <li>{@code sessionLabel} — 実験者が付ける識別子（{@code p001-s01} など）。
 *       <strong>参加者の氏名など個人情報を入れてはならない</strong>。文字種と長さを制限している</li>
 *   <li>{@code phaseId} — セッション内の区間（{@link StudyPhase}）。未設定なら {@code null}</li>
 * </ul>
 *
 * <p>状態は永続化しない。サーバを再起動すると必ずセッションなしに戻る（古いセッションが続かない）。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class StudySession {

    /** ラベルに使える文字。英数字・ハイフン・アンダースコア、1〜64 文字。 */
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);

    /**
     * 進行中のセッション。
     *
     * @param sessionId        内部 ID
     * @param label            実験者が付けたラベル
     * @param startWorldTime   開始時のワールド時刻 [ticks]
     * @param startEpochMillis 開始時刻（epoch ミリ秒）
     * @param phase            現在のフェーズ（{@code null} 可）
     */
    public record Active(String sessionId, String label, long startWorldTime, long startEpochMillis,
                         StudyPhase phase) {

        /** @return ログに書くフェーズ名（未設定なら {@code null}） */
        public String phaseId() {
            return phase == null ? null : phase.name();
        }

        /**
         * セッション開始からの経過 tick。
         *
         * @param worldTime 現在のワールド時刻
         * @return 経過 tick（開始前の時刻なら 0）
         */
        public long elapsedTicks(long worldTime) {
            return Math.max(0L, worldTime - startWorldTime);
        }
    }

    private static Active current;

    /**
     * セッションの境界（開始・終了・リセット）で呼ぶ処理（Phase 25 P0-4）。前のセッションの状態
     * （サンプラーの前回位置など）を次のセッションへ持ち越さないために使う。
     */
    private static final java.util.List<Runnable> BOUNDARY_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 実験手順の版（Phase 25A）。サーバ起動時に設定ファイルの値を入れる。 */
    private static int protocolVersion = jp.ac.naist.hai.aiwolf.core.AiWolfParameters.HUMAN_STUDY_PROTOCOL_VERSION;

    /**
     * 実験者へ示す注意（Phase 25C）。ラベル・割付 ID はログにそのまま残るので、個人を特定できる値を入れてはならない。
     */
    public static final String LABEL_PRIVACY_WARNING =
            "DO NOT use participant names, email addresses, student IDs, UUIDs, or other identifying information. "
                    + "Use anonymous labels such as p001-s01.";

    private StudySession() {
        throw new AssertionError("static holder");
    }

    /**
     * ラベルが使えるかを判定する。
     *
     * @param label ラベル
     * @return 使えるなら true
     */
    public static boolean isValidLabel(String label) {
        return label != null && LABEL.matcher(label).matches() && !looksLikeIdentifier(label);
    }

    /**
     * 実験者が入力するラベル・ID が、個人を特定する値の形をしていないかを判定する（Phase 25C）。
     *
     * <p>許す文字を英数字・{@code _ - .} に絞っているので、{@code @}・空白・メールアドレスはそもそも通らない。
     * そのうえで、UUID の形（8-4-4-4-12、またはハイフンなしの 32 桁の 16 進）を拒否する。
     * 学籍番号や氏名の形は一般には判定できないので、コマンドのヘルプと文書で注意する
     * （{@link #LABEL_PRIVACY_WARNING}）。</p>
     *
     * @param value 入力
     * @return 拒否すべき形なら true
     */
    public static boolean looksLikeIdentifier(String value) {
        if (value == null) {
            return false;
        }
        return value.contains("@") || value.chars().anyMatch(Character::isWhitespace)
                || value.matches("(?i).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*")
                || value.matches("(?i).*(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f]).*");
    }

    /** @return 実験手順の版 */
    public static int protocolVersion() {
        return protocolVersion;
    }

    /**
     * 実験手順の版を設定する（サーバ起動時に設定ファイルの値で）。
     *
     * @param version 版（1 以上）
     */
    public static void setProtocolVersion(int version) {
        if (version < 1) {
            throw new IllegalArgumentException("protocol version must be >= 1: " + version);
        }
        protocolVersion = version;
    }

    /**
     * 新しい内部 ID を作る。
     *
     * @param now 時刻
     * @return {@code session-<UTC>-<乱数 6 桁>}
     */
    public static String newSessionId(ZonedDateTime now) {
        return "session-" + ID_TIME.format(now.withZoneSameInstant(ZoneOffset.UTC)) + "-"
                + String.format(Locale.ROOT, "%06x", ThreadLocalRandom.current().nextInt(1 << 24));
    }

    /**
     * セッションを開始する。進行中のセッションがあれば例外。
     *
     * @param label            ラベル
     * @param worldTime        ワールド時刻
     * @param epochMillis      現在時刻
     * @param now              ID 生成用の時刻
     * @return 開始したセッション
     */
    public static Active start(String label, long worldTime, long epochMillis, ZonedDateTime now) {
        if (current != null) {
            throw new IllegalStateException("a session is already active: " + current.sessionId());
        }
        if (!isValidLabel(label)) {
            throw new IllegalArgumentException("invalid session label: " + label);
        }
        current = new Active(newSessionId(now), label, worldTime, epochMillis, null);
        fireBoundary();
        return current;
    }

    /**
     * フェーズを変える。
     *
     * @param phase 新しいフェーズ（{@code null} で解除）
     * @return 変更後のセッション
     * @throws IllegalStateException セッションがない場合
     */
    public static Active setPhase(StudyPhase phase) {
        Active active = requireActive();
        current = new Active(active.sessionId(), active.label(), active.startWorldTime(),
                active.startEpochMillis(), phase);
        return current;
    }

    /**
     * セッションを終える。
     *
     * @return 終えたセッション
     * @throws IllegalStateException セッションがない場合
     */
    public static Active stop() {
        Active active = requireActive();
        current = null;
        fireBoundary();
        return active;
    }

    /** @return 進行中のセッション（なければ {@code null}） */
    public static Active current() {
        return current;
    }

    /** セッションを破棄する（サーバの起動・停止時とテスト）。ログは書かない。 */
    public static void reset() {
        current = null;
        fireBoundary();
    }

    /**
     * セッションの開始・終了・リセットのたびに呼ぶ処理を登録する（同じ処理の二重登録はしない）。
     *
     * @param listener 処理
     */
    public static void addBoundaryListener(Runnable listener) {
        if (!BOUNDARY_LISTENERS.contains(listener)) {
            BOUNDARY_LISTENERS.add(listener);
        }
    }

    /**
     * 登録を外す（テスト用）。
     *
     * @param listener 処理
     */
    public static void removeBoundaryListener(Runnable listener) {
        BOUNDARY_LISTENERS.remove(listener);
    }

    private static void fireBoundary() {
        for (Runnable listener : BOUNDARY_LISTENERS) {
            listener.run();
        }
    }

    private static Active requireActive() {
        if (current == null) {
            throw new IllegalStateException("no active session");
        }
        return current;
    }
}
