package jp.ac.naist.hai.aiwolf.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 依存ライブラリを持たない最小限の JSON 書き出しユーティリティ。
 *
 * <p>Gson 等を使わないのは、研究データ出力部分を Minecraft 実行環境の外でも
 * そのままテストできるようにするためである。整形出力（{@code pretty}）と
 * 1 行出力（JSONL 用）を切り替えられる。</p>
 *
 * <p>呼び出し側はネストの開始／終了を対応させる責任を持つ。スレッド安全ではない。</p>
 */
public final class JsonWriter {

    private static final String INDENT_UNIT = "  ";

    private final StringBuilder builder = new StringBuilder();
    private final boolean pretty;
    private final Deque<Boolean> parentFirstFlags = new ArrayDeque<>();

    private boolean first = true;
    private int depth;

    private JsonWriter(boolean pretty) {
        this.pretty = pretty;
    }

    /**
     * 人間可読な整形出力用のライタを生成する（{@code wolves.json} など）。
     *
     * @return ライタ
     */
    public static JsonWriter pretty() {
        return new JsonWriter(true);
    }

    /**
     * 1 行出力用のライタを生成する（JSONL の 1 レコードなど）。
     *
     * @return ライタ
     */
    public static JsonWriter compact() {
        return new JsonWriter(false);
    }

    /**
     * オブジェクトを開始する（配列要素／ルート用）。
     *
     * @return this
     */
    public JsonWriter beginObject() {
        return beginContainer(null, '{');
    }

    /**
     * 名前付きオブジェクトを開始する。
     *
     * @param name プロパティ名
     * @return this
     */
    public JsonWriter beginObject(String name) {
        return beginContainer(name, '{');
    }

    /**
     * オブジェクトを終了する。
     *
     * @return this
     */
    public JsonWriter endObject() {
        return endContainer('}');
    }

    /**
     * 配列を開始する（配列要素／ルート用）。
     *
     * @return this
     */
    public JsonWriter beginArray() {
        return beginContainer(null, '[');
    }

    /**
     * 名前付き配列を開始する。
     *
     * @param name プロパティ名
     * @return this
     */
    public JsonWriter beginArray(String name) {
        return beginContainer(name, '[');
    }

    /**
     * 配列を終了する。
     *
     * @return this
     */
    public JsonWriter endArray() {
        return endContainer(']');
    }

    /**
     * 文字列プロパティを書き出す。{@code null} は JSON null になる。
     *
     * @param name  プロパティ名
     * @param value 値（{@code null} 可）
     * @return this
     */
    public JsonWriter property(String name, String value) {
        writeSeparator();
        writeName(name);
        builder.append(value == null ? "null" : quote(value));
        return this;
    }

    /**
     * 数値プロパティを書き出す。非有限値は JSON null になる。
     *
     * @param name  プロパティ名
     * @param value 値
     * @return this
     */
    public JsonWriter property(String name, double value) {
        writeSeparator();
        writeName(name);
        builder.append(formatDouble(value));
        return this;
    }

    /**
     * 整数プロパティを書き出す。
     *
     * @param name  プロパティ名
     * @param value 値
     * @return this
     */
    public JsonWriter property(String name, long value) {
        writeSeparator();
        writeName(name);
        builder.append(value);
        return this;
    }

    /**
     * 真偽値プロパティを書き出す。
     *
     * @param name  プロパティ名
     * @param value 値
     * @return this
     */
    public JsonWriter property(String name, boolean value) {
        writeSeparator();
        writeName(name);
        builder.append(value);
        return this;
    }

    /**
     * 配列要素として文字列を書き出す。
     *
     * @param value 値（{@code null} 可）
     * @return this
     */
    public JsonWriter value(String value) {
        writeSeparator();
        builder.append(value == null ? "null" : quote(value));
        return this;
    }

    /**
     * 配列要素として数値を書き出す。
     *
     * @param value 値
     * @return this
     */
    public JsonWriter value(double value) {
        writeSeparator();
        builder.append(formatDouble(value));
        return this;
    }

    /**
     * 生成された JSON 文字列を返す。
     *
     * @return JSON 文字列
     */
    public String toJson() {
        return builder.toString();
    }

    @Override
    public String toString() {
        return toJson();
    }

    // ------------------------------------------------------------------
    // internal
    // ------------------------------------------------------------------

    private JsonWriter beginContainer(String name, char open) {
        writeSeparator();
        if (name != null) {
            writeName(name);
        }
        builder.append(open);
        parentFirstFlags.push(Boolean.FALSE);
        first = true;
        depth++;
        return this;
    }

    private JsonWriter endContainer(char close) {
        depth--;
        if (!first && pretty) {
            builder.append('\n');
            appendIndent();
        }
        builder.append(close);
        Boolean parentFirst = parentFirstFlags.poll();
        first = parentFirst != null && parentFirst;
        return this;
    }

    private void writeSeparator() {
        if (!first) {
            builder.append(',');
        }
        first = false;
        if (pretty && depth > 0) {
            builder.append('\n');
            appendIndent();
        }
    }

    private void writeName(String name) {
        builder.append(quote(name)).append(pretty ? ": " : ":");
    }

    private void appendIndent() {
        builder.append(INDENT_UNIT.repeat(Math.max(0, depth)));
    }

    private static String formatDouble(double value) {
        if (!Double.isFinite(value)) {
            return "null";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1.0e15) {
            return Long.toString((long) value) + ".0";
        }
        return Double.toString(value);
    }

    /**
     * JSON 文字列リテラルとしてエスケープする。
     *
     * @param raw 生文字列
     * @return 引用符付きのエスケープ済み文字列
     */
    public static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2);
        out.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
