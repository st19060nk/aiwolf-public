package jp.ac.naist.hai.aiwolf.study;

import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.core.AdaptiveFeatures;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * プレイヤー側のログ（Phase 21）の 1 行。<strong>Minecraft に依存しない。</strong>
 *
 * <ul>
 *   <li>{@code player-events.jsonl} — プレイヤーの行動 1 件（{@link #event}）</li>
 *   <li>{@code player-samples.jsonl} — プレイヤーとオオカミの関係の定期サンプル 1 件（{@link #sample}）</li>
 * </ul>
 *
 * <h2>個人情報</h2>
 * <p>プレイヤーを表す項目は匿名の {@code subjectId}（{@code subject-} + 12 桁）だけ。
 * プレイヤー名・UUID（一部も含む）・IP・チャット・スキン・座標は<strong>受け取る引数自体がない</strong>。
 * 位置に関わる情報は距離と移動量（スカラー）だけを書く。</p>
 *
 * <h2>時刻</h2>
 * <p>{@code worldTime} は決定ログ・出来事ログと同じワールド時刻 [ticks]（20 ticks = 1 秒）。
 * {@code runId} / {@code sessionId} / {@code worldTime} で決定ログと時系列に結合できる。</p>
 */
public final class PlayerLogJson {

    /** プレイヤーの行動の種類。 */
    public enum PlayerEventType {
        /** 撫でた（研究イベント PET が成立したとき。クールダウン中は記録しない）。 */
        PLAYER_PET,
        /** 餌を与えた（研究イベント FEED が成立したとき）。 */
        PLAYER_FEED,
        /** 敵を攻撃した（実際にダメージを与えたとき。飛び道具は射手に帰属）。 */
        PLAYER_ATTACK_HOSTILE,
        /** 救援が確定した（研究イベント PLAYER_PROTECTED_WOLF と同じ判定・同じ時刻）。 */
        PLAYER_PROTECTED_WOLF,
        /** プレイヤー自身が実際にダメージを受けた。 */
        PLAYER_DAMAGED,
        /** プレイヤーが敵を倒した。 */
        PLAYER_KILLED_HOSTILE
    }

    /**
     * プレイヤーとオオカミの関係（イベントとサンプルで共通）。距離は同じワールドにいなければ {@code null}。
     *
     * @param ownerWolfDistance 飼い主とオオカミの距離 [blocks]
     * @param wolfHealthRatio   オオカミの体力の割合 [0-1]
     * @param playerHealthRatio プレイヤーの体力の割合 [0-1]（プレイヤーがオフラインなら {@code null}）
     * @param hostileCount      オオカミから半径 HOSTILE_SCAN_RADIUS 内の敵の数
     * @param wolfInCombat      オオカミが追跡中の戦闘があるか
     */
    public record Relation(Double ownerWolfDistance, double wolfHealthRatio, Double playerHealthRatio,
                           int hostileCount, boolean wolfInCombat) {

        /** @return 敵が近くにいれば true */
        public boolean hostileNearby() {
            return hostileCount > 0;
        }
    }

    /**
     * 誰と誰の記録か（匿名 ID と、その時点の実験条件）。
     *
     * @param subjectId      プレイヤーの匿名 ID
     * @param wolfSubjectId  オオカミの匿名 ID
     * @param condition      オオカミの実験条件
     */
    public record Subjects(String subjectId, String wolfSubjectId, ExperimentalCondition condition) {
        public Subjects {
            Objects.requireNonNull(condition, "condition");
        }
    }

    private PlayerLogJson() {
        throw new AssertionError("utility class");
    }

    /**
     * プレイヤーの行動 1 件。
     *
     * @param type        種類
     * @param subjects    匿名 ID と条件
     * @param session     進行中のセッション（{@code null} 可）
     * @param worldTime   ワールド時刻
     * @param epochMillis 現在時刻
     * @param relation    プレイヤーとオオカミの関係
     * @param details     種類ごとの追加項目（{@code null} の値は省略）
     * @return JSON 1 行
     */
    public static String event(PlayerEventType type, Subjects subjects, StudySession.Active session,
                               long worldTime, long epochMillis, Relation relation, Map<String, Object> details) {
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "player_event")
                .property("playerLogSchemaVersion", AiWolfParameters.PLAYER_LOG_SCHEMA_VERSION)
                .property("eventType", type.name());
        common(writer, subjects, session, worldTime, epochMillis);
        writeRelation(writer, relation);
        if (details != null && !details.isEmpty()) {
            writer.beginObject("details");
            SessionLogJson.writeDetails(writer, details);
            writer.endObject();
        }
        writer.endObject();
        return writer.toJson();
    }

    /**
     * プレイヤーとオオカミの関係の定期サンプル 1 件。
     *
     * @param subjects            匿名 ID と条件
     * @param session             進行中のセッション（{@code null} 可）
     * @param worldTime           ワールド時刻
     * @param epochMillis         現在時刻
     * @param relation            関係
     * @param motion              前回のサンプルからの移動
     * @param wolfCurrentBehavior オオカミの現在行動（未決定なら {@code null}）
     * @param aiStatus            AI の状態（{@code ACTIVE} / {@code SUSPENDED}）
     * @return JSON 1 行
     */
    public static String sample(Subjects subjects, StudySession.Active session, long worldTime, long epochMillis,
                                Relation relation, Motion motion, String wolfCurrentBehavior, String aiStatus) {
        JsonWriter writer = JsonWriter.compact();
        writer.beginObject()
                .property("recordType", "player_sample")
                .property("playerLogSchemaVersion", AiWolfParameters.PLAYER_LOG_SCHEMA_VERSION);
        common(writer, subjects, session, worldTime, epochMillis);
        writeRelation(writer, relation);
        writer.beginObject("motion");
        // Phase 25 P0-4：比較の基準（同じセッションの直前のサンプル）があるかを明示する。
        // ない場合は移動量・速さを書かず、その理由を書く（0 と区別するため、値を推測で埋めない）。
        writer.property("hasBaseline", motion.hasBaseline());
        if (!motion.hasBaseline()) {
            writer.property("noBaselineReason", motion.noBaselineReason());
        }
        if (motion.intervalTicks() > 0) {
            writer.property("intervalTicks", motion.intervalTicks());
        }
        nullable(writer, "playerDistanceMoved", motion.playerDistanceMoved());
        nullable(writer, "wolfDistanceMoved", motion.wolfDistanceMoved());
        nullable(writer, "playerSpeed", motion.playerSpeed());
        nullable(writer, "wolfSpeed", motion.wolfSpeed());
        if (motion.playerDistanceMoved() != null) {
            writer.property("playerMoving", motion.playerMoving());
        }
        if (motion.wolfDistanceMoved() != null) {
            writer.property("wolfMoving", motion.wolfMoving());
        }
        writer.endObject();
        if (wolfCurrentBehavior != null) {
            writer.property("wolfCurrentBehavior", wolfCurrentBehavior);
        }
        if (aiStatus != null) {
            writer.property("wolfAiStatus", aiStatus);
        }
        writer.endObject();
        return writer.toJson();
    }

    /**
     * 前回のサンプルからの移動。比較の基準がなければ移動量は {@code null} で、理由を持つ。
     *
     * @param intervalTicks        前回のサンプルからの tick 数（基準がなければ 0）
     * @param playerDistanceMoved  プレイヤーの移動距離 [blocks]
     * @param wolfDistanceMoved    オオカミの移動距離 [blocks]
     * @param noBaselineReason     基準がない理由（{@link MotionBaseline.Missing} の名前。基準があれば {@code null}）
     */
    public record Motion(long intervalTicks, Double playerDistanceMoved, Double wolfDistanceMoved,
                         String noBaselineReason) {

        /**
         * 基準のある移動。
         *
         * @param intervalTicks       前回からの tick 数
         * @param playerDistanceMoved プレイヤーの移動距離（同じワールドにいなければ {@code null}）
         * @param wolfDistanceMoved   オオカミの移動距離
         */
        public Motion(long intervalTicks, Double playerDistanceMoved, Double wolfDistanceMoved) {
            this(intervalTicks, playerDistanceMoved, wolfDistanceMoved, null);
        }

        /** @return 基準のない最初のサンプル */
        public static Motion first() {
            return none(MotionBaseline.Missing.FIRST_SAMPLE);
        }

        /**
         * 基準のないサンプル。
         *
         * @param reason 理由
         * @return 移動量のない Motion
         */
        public static Motion none(MotionBaseline.Missing reason) {
            return new Motion(0L, null, null, reason.name());
        }

        /** @return 比較の基準があれば true */
        public boolean hasBaseline() {
            return noBaselineReason == null;
        }

        /** 「移動中」とみなす速さ [blocks/秒]。歩きの約 1/16。立ち止まって向きを変えた程度は含めない。 */
        public static final double MOVING_SPEED = 0.25;

        /** @return プレイヤーの速さ [blocks/秒] */
        public Double playerSpeed() {
            return speed(playerDistanceMoved);
        }

        /** @return オオカミの速さ [blocks/秒] */
        public Double wolfSpeed() {
            return speed(wolfDistanceMoved);
        }

        /** @return プレイヤーが動いていれば true */
        public boolean playerMoving() {
            Double speed = playerSpeed();
            return speed != null && speed >= MOVING_SPEED;
        }

        /** @return オオカミが動いていれば true */
        public boolean wolfMoving() {
            Double speed = wolfSpeed();
            return speed != null && speed >= MOVING_SPEED;
        }

        private Double speed(Double distance) {
            return distance == null || intervalTicks <= 0 ? null : distance / (intervalTicks / 20.0);
        }
    }

    private static void common(JsonWriter writer, Subjects subjects, StudySession.Active session,
                               long worldTime, long epochMillis) {
        writer.property("subjectId", subjects.subjectId())
                .property("wolfSubjectId", subjects.wolfSubjectId())
                .property("epochMillis", epochMillis)
                .property("worldTime", worldTime);
        if (session != null) {
            writer.property("elapsedTicksFromSessionStart", session.elapsedTicks(worldTime));
        }
        ExperimentalCondition condition = subjects.condition();
        AdaptiveFeatures features = condition.features();
        writer.property("experimentalCondition", condition.id())
                .property("parameterSetVersion", AiWolfParameters.PARAMETER_SET_VERSION)
                .beginObject("adaptiveFeatures")
                .property("stateLearning", features.stateLearning())
                .property("episodicMemory", features.episodicMemory())
                .property("spatialMemory", features.spatialMemory())
                .property("preferenceLearning", features.preferenceLearning())
                .endObject();
    }

    private static void writeRelation(JsonWriter writer, Relation relation) {
        writer.beginObject("context");
        nullable(writer, "ownerWolfDistance", relation.ownerWolfDistance());
        writer.property("wolfHealthRatio", round(relation.wolfHealthRatio()));
        nullable(writer, "playerHealthRatio", relation.playerHealthRatio());
        writer.property("hostileNearby", relation.hostileNearby())
                .property("hostileCount", relation.hostileCount())
                .property("wolfInCombat", relation.wolfInCombat())
                .endObject();
    }

    private static void nullable(JsonWriter writer, String name, Double value) {
        if (value != null && Double.isFinite(value)) {
            writer.property(name, round(value));
        }
    }

    private static double round(double value) {
        return MathSupport.round(value, AiWolfParameters.EXPORT_DECIMALS);
    }
}
