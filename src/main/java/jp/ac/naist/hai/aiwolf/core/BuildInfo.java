package jp.ac.naist.hai.aiwolf.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 実験に使ったソフトウェアの版（Phase 25A）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>{@code parameterSetVersion} は研究パラメータの版で、同じパラメータセットの中でのコード修正
 * （ログの不具合の修正など）を区別できない。そこで、ビルド時に Gradle の {@code generateBuildInfo} が
 * Git の commit を {@value #RESOURCE} に書き込み、セッション開始の行に記録する。
 * 実行時に git を呼ばないので、Git のない実験 PC や配布した jar でも同じ値が出る。</p>
 *
 * <p>Git 情報を取れなかったビルドや、リソースのない環境（IDE から直接起動など）では、
 * 値を省略せず {@value #UNKNOWN} を返す。</p>
 *
 * @param modVersion    {@code gradle.properties} の {@code mod_version}
 * @param buildRevision Git の commit（短縮 7 桁）。不明なら {@value #UNKNOWN}
 * @param buildDirty    ビルド時に未コミットの変更があったか（{@code true} / {@code false} / {@value #UNKNOWN}）
 */
public record BuildInfo(String modVersion, String buildRevision, String buildDirty) {

    /** クラスパス上のリソース名。 */
    public static final String RESOURCE = "/aiwolf-build.properties";

    /** 取得できなかった値。 */
    public static final String UNKNOWN = "UNKNOWN";

    private static final BuildInfo CURRENT = loadFromClasspath();

    /** @return この jar の版 */
    public static BuildInfo current() {
        return CURRENT;
    }

    /**
     * properties から読む。欠けた値・空の値は {@value #UNKNOWN} にする。
     *
     * @param reader 読み込み元（{@code null} ならすべて UNKNOWN）
     * @return 版
     */
    public static BuildInfo load(Reader reader) {
        Properties properties = new Properties();
        if (reader != null) {
            try {
                properties.load(reader);
            } catch (IOException exception) {
                properties.clear();
            }
        }
        return new BuildInfo(value(properties, "modVersion"), value(properties, "buildRevision"),
                value(properties, "buildDirty"));
    }

    private static BuildInfo loadFromClasspath() {
        try (InputStream stream = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return load(null);
            }
            return load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return load(null);
        }
    }

    private static String value(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? UNKNOWN : value.trim();
    }
}
