package jp.ac.naist.hai.aiwolf.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Optional;
import java.util.Properties;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;

/**
 * 実験メタデータの設定。読み書きは Minecraft に依存しない。
 *
 * <p>形式は properties にしている。JSON より編集しやすく、外部ライブラリも要らないためで、
 * 実験者が手で書き換えることを前提にしている。</p>
 *
 * <h2>experimentSalt について</h2>
 *
 * <p>参加者の匿名 ID は {@code SHA-256(experimentSalt + playerUUID)} から作る。
 * salt が実験を通じて同じであれば、<strong>同じ参加者は常に同じ ID になり</strong>、
 * 複数セッション・複数ワールドを跨いでも履歴を追跡できる。
 * salt が変わると同じ参加者が別 ID になり、追跡が切れる。</p>
 *
 * <p>そのため salt は<strong>実験開始時に一度決めて、以後変更しない</strong>。
 * 秘密である必要はないが、固定の participant identifier を Git へ置かないよう、
 * 初回起動時にランダム生成してローカルの設定ファイルへ書き出す方式にしている。</p>
 *
 * @param experimentSalt    匿名 ID 生成に使う salt
 * @param experimentSeed    行動選択の再現に使う実験シード
 * @param defaultCondition  新規個体へ割り当てる既定の実験条件
 * @param humanStudyProtocolVersion Human Study の実験手順の版（Phase 25A。AI のパラメータの版とは別）
 */
public record AiWolfConfig(long experimentSalt,
                           long experimentSeed,
                           ExperimentalCondition defaultCondition,
                           int humanStudyProtocolVersion) {

    /**
     * 実験手順の版を既定値にした設定（Phase 25A 以前の 3 項目の形）。
     *
     * @param experimentSalt   salt
     * @param experimentSeed   実験シード
     * @param defaultCondition 既定の実験条件
     */
    public AiWolfConfig(long experimentSalt, long experimentSeed, ExperimentalCondition defaultCondition) {
        this(experimentSalt, experimentSeed, defaultCondition, AiWolfParameters.HUMAN_STUDY_PROTOCOL_VERSION);
    }

    /** 設定ファイル名。 */
    public static final String FILE_NAME = "aiwolf.properties";

    private static final String KEY_SALT = "experimentSalt";
    private static final String KEY_SEED = "experimentSeed";
    private static final String KEY_CONDITION = "defaultCondition";
    private static final String KEY_PROTOCOL = "humanStudyProtocolVersion";

    private static final String COMMENT = """
            AI Wolf Research Mod - experiment metadata
            
            experimentSalt   : participant ID (SHA-256) generation salt.
                               Keep this value for the whole experiment. Changing it
                               makes the same participant appear as a different subject
                               and breaks the continuity of their interaction history.
            experimentSeed   : seed for reproducible behaviour selection.
            defaultCondition : adaptive | state_only | static, assigned to new wolves.
            humanStudyProtocolVersion : version of the Human Study procedure (phases, timing,
                               instructions). Not the AI parameter version. Increase it
                               when the procedure changes; it is logged at SESSION_START.""";

    /**
     * 既定値の設定を返す。salt はランダムに生成する。
     *
     * @return 既定設定
     */
    public static AiWolfConfig createDefault() {
        return new AiWolfConfig(new java.util.Random().nextLong(),
                AiWolfParameters.DEFAULT_EXPERIMENT_SEED,
                AiWolfParameters.DEFAULT_EXPERIMENTAL_CONDITION);
    }

    /**
     * properties 形式から読み込む。壊れた値や欠損した値は既定値へフォールバックする。
     *
     * @param reader 読み込み元
     * @return 設定
     * @throws IOException 読み込みに失敗した場合
     */
    public static AiWolfConfig load(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        AiWolfConfig defaults = createDefault();
        return new AiWolfConfig(
                parseLong(properties.getProperty(KEY_SALT)).orElse(defaults.experimentSalt()),
                parseLong(properties.getProperty(KEY_SEED)).orElse(defaults.experimentSeed()),
                ExperimentalCondition.byId(properties.getProperty(KEY_CONDITION))
                        .orElse(defaults.defaultCondition()),
                parseLong(properties.getProperty(KEY_PROTOCOL))
                        .filter(value -> value >= 1 && value <= Integer.MAX_VALUE)
                        .map(Long::intValue)
                        .orElse(defaults.humanStudyProtocolVersion()));
    }

    /**
     * properties 形式へ書き出す。
     *
     * @param writer 書き出し先
     * @throws IOException 書き出しに失敗した場合
     */
    public void store(Writer writer) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(KEY_SALT, Long.toString(experimentSalt));
        properties.setProperty(KEY_SEED, Long.toString(experimentSeed));
        properties.setProperty(KEY_CONDITION, defaultCondition.id());
        properties.setProperty(KEY_PROTOCOL, Integer.toString(humanStudyProtocolVersion));
        properties.store(writer, COMMENT);
    }

    private static Optional<Long> parseLong(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
