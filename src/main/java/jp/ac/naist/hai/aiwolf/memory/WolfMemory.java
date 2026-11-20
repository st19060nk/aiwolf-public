package jp.ac.naist.hai.aiwolf.memory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;

/**
 * エピソード記憶 1 件。
 *
 * <p>「いつ・どこで・誰と・何が起き・どれくらい強く感じたか」を保持する。
 * 位置は正確な座標に加えて量子化領域も持ち、場所の一致判定は領域単位で行う。</p>
 *
 * <p>{@code actorId} には匿名化済みの研究用 ID または MOB の種別 ID のみを入れる。
 * プレイヤー名など個人を特定できる情報を保存してはならない。</p>
 *
 * @param memoryId          記憶 ID（研究ログの {@code relevantMemoryIds} と対応）
 * @param worldTime         形成時のワールド時刻 [ticks]
 * @param type              出来事の種別
 * @param dimension         ディメンション ID
 * @param biome             バイオーム ID
 * @param region            量子化された場所
 * @param x                 X 座標
 * @param y                 Y 座標
 * @param z                 Z 座標
 * @param actorId           関係した主体の匿名 ID（{@code null} 可）
 * @param wolfHealthRatio   形成時のオオカミの HP 比率
 * @param playerHealthRatio 形成時の飼い主の HP 比率
 * @param valence           快・不快の向き [-1,+1]
 * @param arousal           強度 [0,1]
 * @param salience          長期保持のされやすさ [0,1]
 * @param metadata          追加情報（不変マップ）
 */
public record WolfMemory(
        String memoryId,
        long worldTime,
        WolfEventType type,
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
        double salience,
        Map<String, String> metadata) {

    /** コンパクトコンストラクタ。値域の正規化と metadata の不変化を行う。 */
    public WolfMemory {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(type, "type");
        valence = MathSupport.clipSigned(valence);
        arousal = MathSupport.clamp01(arousal);
        salience = MathSupport.clamp01(salience);
        metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
    }

    /**
     * 出来事から記憶を形成する。
     *
     * @param event    出来事
     * @param memoryId 記憶 ID（再現性が必要な場面では決定論的に生成すること）
     * @return 記憶
     */
    public static WolfMemory fromEvent(WolfEvent event, String memoryId) {
        Objects.requireNonNull(event, "event");
        return new WolfMemory(memoryId, event.worldTime(), event.type(), event.dimension(), event.biome(),
                event.region(), event.x(), event.y(), event.z(), event.actorId(),
                event.wolfHealthRatio(), event.playerHealthRatio(),
                event.valence(), event.arousal(), event.salience(), event.metadata());
    }

    /**
     * ランダムな ID を付けて出来事から記憶を形成する。
     *
     * @param event 出来事
     * @return 記憶
     */
    public static WolfMemory fromEvent(WolfEvent event) {
        return fromEvent(event, UUID.randomUUID().toString());
    }

    /**
     * 指定時刻における recency（新しさ）を返す。
     *
     * <p>{@code exp(-dt / tau)}。salience の高い記憶は recency が落ちても
     * {@link MemoryStore} の剪定で残りやすい。</p>
     *
     * @param currentWorldTime 現在のワールド時刻
     * @param tauTicks         時定数 [ticks]
     * @return [0,1] の recency
     */
    public double recency(long currentWorldTime, double tauTicks) {
        long elapsed = Math.max(0L, currentWorldTime - worldTime);
        return MathSupport.proximity(elapsed, tauTicks);
    }
}
