package jp.ac.naist.hai.aiwolf.core;

/**
 * 適応機構の ON / OFF（アブレーション用の機能フラグ）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>実験条件の違いは<strong>このフラグだけ</strong>である。行動集合・行動の重み・基礎値・
 * 文脈の処理・Softmax の温度・継続ボーナスは、どの条件でも同一。</p>
 *
 * <ul>
 *   <li>{@code stateLearning} — 経験による内部状態の持続的な更新（trust・attachment・curiosity など）。
 *       OFF でも一時的な感情（fear・stress）は出来事に反応する（STATIC 条件の定義）</li>
 *   <li>{@code episodicMemory} — 出来事をエピソード記憶として蓄え、決定時に検索してスコアへ足す</li>
 *   <li>{@code spatialMemory} — 場所ごとの恐怖値・安心値を蓄え、文脈（場所の恐怖）としてスコアへ入れる</li>
 *   <li>{@code preferenceLearning} — 行動選好を学習し、スコアへ足す</li>
 * </ul>
 *
 * @param stateLearning      内部状態の持続的な更新
 * @param episodicMemory     エピソード記憶
 * @param spatialMemory      空間記憶
 * @param preferenceLearning 選好学習
 */
public record AdaptiveFeatures(boolean stateLearning,
                               boolean episodicMemory,
                               boolean spatialMemory,
                               boolean preferenceLearning) {

    /** @return エピソード記憶か空間記憶のどちらかを使うなら true */
    public boolean anyMemory() {
        return episodicMemory || spatialMemory;
    }

    /** @return 人が読める短い表記（例：{@code state+episodic+spatial}） */
    public String describe() {
        StringBuilder text = new StringBuilder();
        append(text, stateLearning, "state");
        append(text, episodicMemory, "episodic");
        append(text, spatialMemory, "spatial");
        append(text, preferenceLearning, "preference");
        return text.length() == 0 ? "none" : text.toString();
    }

    private static void append(StringBuilder text, boolean enabled, String name) {
        if (enabled) {
            if (text.length() > 0) {
                text.append('+');
            }
            text.append(name);
        }
    }
}
