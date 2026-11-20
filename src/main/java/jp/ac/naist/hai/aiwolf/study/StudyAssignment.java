package jp.ac.naist.hai.aiwolf.study;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;

/**
 * 実験条件の割付の記録（Phase 23）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>「誰がどの条件・どの順序だったか」を外部の表だけに頼らず、研究ログから再現できるようにする。
 * 個人情報は持たない。</p>
 *
 * <ul>
 *   <li>{@code assignmentId} — 実験者が付ける割付の識別子（{@code condition-A-order-BA} など）</li>
 *   <li>{@code conditionOrder} — 条件の順序（Study 2 の Memory ON → OFF と OFF → ON のカウンターバランス用）。
 *       実験条件の ID の列</li>
 *   <li>{@code assignmentTableHash} — 割付表ファイル（{@value #TABLE_FILE_NAME}、設定フォルダ）の SHA-256。
 *       <strong>表の中身はログに書かない</strong>。どの版の表で実験したかを後から照合するためのもの</li>
 * </ul>
 *
 * <p>状態は永続化しない（サーバを再起動したら設定し直す）。</p>
 */
public final class StudyAssignment {

    /** 割付表のファイル名（Fabric の設定フォルダ {@code config/} に置く）。 */
    public static final String TABLE_FILE_NAME = "aiwolf-assignment.csv";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private static String assignmentId;
    private static List<ExperimentalCondition> conditionOrder = List.of();
    private static String tableHash;

    private StudyAssignment() {
        throw new AssertionError("static holder");
    }

    /**
     * 割付 ID として使えるか（英数字・{@code _ . -}、64 文字以内、UUID の形は不可）。
     *
     * @param id ID
     * @return 使えるなら true
     */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches() && !StudySession.looksLikeIdentifier(id);
    }

    /**
     * 条件の順序を解析する（区切りは {@code , > 空白}）。
     *
     * @param text 例：{@code adaptive>state_preference}
     * @return 条件の列。知らない ID が 1 つでもあれば空
     */
    public static Optional<List<ExperimentalCondition>> parseOrder(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<ExperimentalCondition> order = new ArrayList<>();
        for (String token : text.trim().split("[,>\\s]+")) {
            Optional<ExperimentalCondition> condition = ExperimentalCondition.byId(token);
            if (condition.isEmpty()) {
                return Optional.empty();
            }
            order.add(condition.get());
        }
        return Optional.of(List.copyOf(order));
    }

    /**
     * ファイルの SHA-256 を {@code sha256:<64 桁>} の形で返す。
     *
     * @param file ファイル
     * @return ハッシュ。ファイルがなければ空
     * @throws IOException 読めない場合
     */
    public static Optional<String> hashFile(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return Optional.of(hex.toString());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", exception);
        }
    }

    /** @param id 割付 ID（{@link #isValidId} を満たすこと） */
    public static void setId(String id) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("invalid assignment id: " + id);
        }
        assignmentId = id;
    }

    /** @param order 条件の順序 */
    public static void setOrder(List<ExperimentalCondition> order) {
        conditionOrder = List.copyOf(order);
    }

    /** @param hash 割付表のハッシュ（{@code null} = 表なし） */
    public static void setTableHash(String hash) {
        tableHash = hash;
    }

    /** @return 割付 ID（未設定なら {@code null}） */
    public static String id() {
        return assignmentId;
    }

    /** @return 条件の順序（未設定なら空） */
    public static List<ExperimentalCondition> order() {
        return conditionOrder;
    }

    /** @return {@code adaptive>state_preference} の形（未設定なら {@code null}） */
    public static String orderText() {
        if (conditionOrder.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (ExperimentalCondition condition : conditionOrder) {
            ids.add(condition.id());
        }
        return String.join(">", ids);
    }

    /** @return 割付表のハッシュ（なければ {@code null}） */
    public static String tableHash() {
        return tableHash;
    }

    /** 割付 ID と順序を消す（表のハッシュは残す）。 */
    public static void clear() {
        assignmentId = null;
        conditionOrder = List.of();
    }

    /** すべて消す（サーバの起動・停止時とテスト）。 */
    public static void reset() {
        clear();
        tableHash = null;
    }
}
