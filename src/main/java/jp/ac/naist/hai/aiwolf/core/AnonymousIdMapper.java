package jp.ac.naist.hai.aiwolf.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * プレイヤー UUID を研究用の匿名 ID へ変換する。
 *
 * <p>研究データにプレイヤー UUID をそのまま残さないための層である。UUID は
 * Minecraft のアカウントに結び付いた識別子なので、そのまま論文の補足データとして
 * 公開すると個人の追跡が可能になってしまう。</p>
 *
 * <p>ワールドごとにランダムな salt を持ち、{@code SHA-256(salt + uuid)} の先頭を ID とする。
 * 同じ salt のもとでは同じプレイヤーが常に同じ ID になるため、
 * 「同一参加者との共有経験」という研究上の同一性は保たれる。
 * 一方 salt を破棄すれば、以後 ID から元の UUID を辿ることはできない。</p>
 *
 * <p>Minecraft に依存しないため、対応の安定性を単体テストで固定できる。</p>
 */
public final class AnonymousIdMapper {

    /** 匿名 ID の接頭辞。 */
    public static final String PREFIX = "subject-";

    /** 匿名 ID に使うハッシュの 16 進桁数。 */
    private static final int ID_HEX_LENGTH = 12;

    private final long salt;

    private AnonymousIdMapper(long salt) {
        this.salt = salt;
    }

    /**
     * salt を指定してマッパを生成する。
     *
     * @param salt ワールドごとの salt
     * @return マッパ
     */
    public static AnonymousIdMapper withSalt(long salt) {
        return new AnonymousIdMapper(salt);
    }

    /** @return この マッパの salt（永続化用） */
    public long salt() {
        return salt;
    }

    /**
     * プレイヤー UUID を匿名 ID へ変換する。
     *
     * @param playerUuid プレイヤー UUID（{@code null} 可）
     * @return 匿名 ID。{@code null} が渡されたら {@code null}
     */
    public String toSubjectId(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        byte[] digest = sha256(salt + ":" + playerUuid);
        StringBuilder hex = new StringBuilder(PREFIX);
        for (int i = 0; i < ID_HEX_LENGTH / 2; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return hex.toString();
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(input).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", exception);
        }
    }
}
