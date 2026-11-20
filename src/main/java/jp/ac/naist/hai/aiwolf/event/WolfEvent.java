package jp.ac.naist.hai.aiwolf.event;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * 実際に発生した出来事 1 件。
 *
 * <p>Minecraft の型を含まない純データである。イベント検出（Minecraft 側）と
 * イベント処理（状態更新・記憶形成・選好学習）を分離するための境界となる。</p>
 *
 * <p>{@code actorId} には飼い主の匿名化済み研究 ID、または敵 MOB の種別 ID を入れる。
 * プレイヤー名など個人を特定できる情報を入れてはならない。</p>
 *
 * @param type             出来事の種別
 * @param worldTime        発生時のワールド時刻 [ticks]
 * @param dimension        ディメンション ID
 * @param biome            バイオーム ID
 * @param region           量子化された発生場所
 * @param x                発生位置 X
 * @param y                発生位置 Y
 * @param z                発生位置 Z
 * @param actorId          関係した主体の ID（匿名化済み。存在しなければ {@code null}）
 * @param wolfHealthRatio  発生時のオオカミの HP 比率
 * @param playerHealthRatio 発生時の飼い主の HP 比率
 * @param valence          快・不快の向き [-1, +1]
 * @param arousal          強度 [0, 1]
 * @param metadata         追跡用の付随情報（combatId / hostileType など）。個人情報を入れてはならない
 */
public record WolfEvent(
        WolfEventType type,
        long worldTime,
        String dimension,
        String biome,
        SpatialRegion region,
        double x,
        double y,
        double z,
        String actorId,
        double wolfHealthRatio,
        double playerHealthRatio,
        double valence,
        double arousal,
        Map<String, String> metadata) {

    /** combat episode を識別するメタデータのキー。 */
    public static final String META_COMBAT_ID = "combatId";

    /** 相手の敵 MOB の種別を示すメタデータのキー。 */
    public static final String META_HOSTILE_TYPE = "hostileType";

    /** 相手の敵 MOB の短縮 ID を示すメタデータのキー。MOB は個人ではないので記録して差し支えない。 */
    public static final String META_HOSTILE_ID = "hostileId";

    /** 戦闘が始まったきっかけを表すメタデータのキー。 */
    public static final String META_COMBAT_TRIGGER = "combatTrigger";

    /** その決定区間に受けた合計ダメージ量のメタデータのキー。 */
    public static final String META_DAMAGE_TOTAL = "damageTotal";

    /** 代表として選ばれた加害者から受けたダメージ量のメタデータのキー。 */
    public static final String META_DAMAGE_ATTRIBUTED = "damageAttributed";

    /** その決定区間に関与した加害者の数のメタデータのキー。 */
    public static final String META_DAMAGE_SOURCES = "damageSourceCount";

    /** ダメージの出どころの区分（{@code hostile} / {@code environment}）のメタデータのキー。 */
    public static final String META_DAMAGE_ORIGIN = "damageOrigin";

    /** コンパクトコンストラクタ。値域を正規化し、metadata を不変化する。 */
    public WolfEvent {
        Objects.requireNonNull(type, "type");
        valence = MathSupport.clipSigned(valence);
        arousal = MathSupport.clamp01(arousal);
        metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
    }

    /**
     * 種別の既定 valence / arousal を用いて出来事を生成する。
     *
     * @param type              種別
     * @param worldTime         ワールド時刻
     * @param dimension         ディメンション ID
     * @param biome             バイオーム ID
     * @param x                 位置 X
     * @param y                 位置 Y
     * @param z                 位置 Z
     * @param actorId           関係主体の匿名 ID（{@code null} 可）
     * @param wolfHealthRatio   オオカミの HP 比率
     * @param playerHealthRatio 飼い主の HP 比率
     * @return 出来事
     */
    public static WolfEvent of(WolfEventType type,
                               long worldTime,
                               String dimension,
                               String biome,
                               double x,
                               double y,
                               double z,
                               String actorId,
                               double wolfHealthRatio,
                               double playerHealthRatio) {
        return of(type, worldTime, dimension, biome, x, y, z, actorId,
                wolfHealthRatio, playerHealthRatio, Collections.emptyMap());
    }

    /**
     * 追跡用メタデータを添えて出来事を生成する。
     *
     * <p>戦闘由来のイベントでは、どの combat episode・どの敵によって発火したかを
     * 研究ログから辿れるようにするために使う。</p>
     *
     * @param type              種別
     * @param worldTime         ワールド時刻
     * @param dimension         ディメンション ID
     * @param biome             バイオーム ID
     * @param x                 位置 X
     * @param y                 位置 Y
     * @param z                 位置 Z
     * @param actorId           関係主体の匿名 ID（{@code null} 可）
     * @param wolfHealthRatio   オオカミの HP 比率
     * @param playerHealthRatio 飼い主の HP 比率
     * @param metadata          付随情報
     * @return 出来事
     */
    public static WolfEvent of(WolfEventType type,
                               long worldTime,
                               String dimension,
                               String biome,
                               double x,
                               double y,
                               double z,
                               String actorId,
                               double wolfHealthRatio,
                               double playerHealthRatio,
                               Map<String, String> metadata) {
        return new WolfEvent(type, worldTime, dimension, biome,
                SpatialRegion.of(dimension, x, y, z), x, y, z, actorId,
                wolfHealthRatio, playerHealthRatio,
                type.defaultValence(), type.defaultArousal(), metadata);
    }

    /**
     * この出来事の salience を返す。
     *
     * @return salience [0,1]
     */
    public double salience() {
        return type.salienceOf(valence, arousal);
    }
}
