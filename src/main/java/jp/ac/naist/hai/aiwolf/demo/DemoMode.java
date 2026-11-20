package jp.ac.naist.hai.aiwolf.demo;

/**
 * Research Demo Mode のグローバルなフラグ。<strong>Minecraft に依存しない。</strong>
 *
 * <p>Demo Mode は教授向けデモ動画の撮影補助（デバッグ表示・リセット・スナップショット・
 * 評価モード）のためだけに使う。<strong>行動の計算式・学習則・研究パラメータには
 * 一切関与しない。</strong>このクラスは {@code WolfAiAgent} からも {@code BehaviorScorer}
 * からも参照されない。</p>
 *
 * <p>Demo Mode 中の研究ログは本実験データと混ざらないよう
 * {@code aiwolf-research/demo/} へ別に書き出され、各行の先頭に
 * {@code "demoMode":true} が付く（{@link #tagLogLine}）。</p>
 *
 * <p>どちらのフラグも永続化しない。サーバを再起動すると必ず off に戻るので、
 * 消し忘れたまま本実験を始めてしまうことがない。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class DemoMode {

    /** Demo Mode 中の研究ログを書き出すサブディレクトリ名。 */
    public static final String LOG_SUBDIRECTORY = "demo";

    private static boolean enabled;
    private static boolean evaluationActive;

    private DemoMode() {
        throw new AssertionError("static holder");
    }

    /** @return Demo Mode が有効なら true */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Demo Mode を切り替える。無効にすると評価モードも同時に終了する
     * （学習が止まったまま本実験へ戻ることを防ぐ）。
     *
     * @param value 有効にするなら true
     */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            evaluationActive = false;
        }
    }

    /** @return 評価モード（学習凍結）が有効なら true */
    public static boolean isEvaluationActive() {
        return evaluationActive;
    }

    /**
     * 評価モードを切り替える。Demo Mode か Human Study のセッションの中でしか有効化できない
     * （本実験の通常の育成中に誤って学習を止めないため）。
     *
     * @param value 有効にするなら true
     * @return 実際に設定された値
     */
    public static boolean setEvaluationActive(boolean value) {
        evaluationActive = value && (enabled || jp.ac.naist.hai.aiwolf.study.StudySession.current() != null);
        return evaluationActive;
    }

    /** 両方のフラグを off に戻す。サーバ停止時とテストで使う。 */
    public static void reset() {
        enabled = false;
        evaluationActive = false;
    }

    /**
     * Demo Mode 中のログ 1 行に、デモ由来であることを示す項目を先頭へ付ける。
     *
     * <p>既存のスキーマの項目は一切変えず、先頭に {@code demoMode} と
     * {@code evaluationMode} を足すだけにしている。本実験のログ（Demo Mode off）には
     * このメソッドは使われないので、本実験データのスキーマは変わらない。</p>
     *
     * @param jsonLine     1 行の JSON オブジェクト
     * @param evaluation   評価モード中なら true
     * @return 項目を足した行。JSON オブジェクトでなければそのまま返す
     */
    public static String tagLogLine(String jsonLine, boolean evaluation) {
        if (jsonLine == null || !jsonLine.startsWith("{")) {
            return jsonLine;
        }
        String prefix = "{\"demoMode\":true,\"evaluationMode\":" + evaluation;
        String rest = jsonLine.substring(1);
        return rest.startsWith("}") ? prefix + rest : prefix + "," + rest;
    }
}
