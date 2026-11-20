package jp.ac.naist.hai.aiwolf.study;

import java.util.Locale;
import java.util.Optional;

/**
 * Human Study のセッション内の区間（フェーズ）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>解析で揃えやすいよう、任意の文字列ではなく固定の値にしている。
 * ログには {@link #name()}（例：{@code MEMORY_TEST}）がそのまま入る。</p>
 */
public enum StudyPhase {
    /** 介入前の基準測定。 */
    BASELINE,
    /** 育成（参加者とオオカミが一緒に過ごす）。 */
    TRAINING,
    /** 記憶の知覚テスト（Study 2）。 */
    MEMORY_TEST,
    /** 評価（学習を凍結して観察するなど）。 */
    EVALUATION,
    /** 自由行動。 */
    FREE_PLAY;

    /**
     * 名前から解決する（大文字小文字・ハイフンは無視）。
     *
     * @param name 名前
     * @return フェーズ
     */
    public static Optional<StudyPhase> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (StudyPhase phase : values()) {
            if (phase.name().equals(normalized)) {
                return Optional.of(phase);
            }
        }
        return Optional.empty();
    }
}
