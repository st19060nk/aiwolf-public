package jp.ac.naist.hai.aiwolf.study;

import java.util.Optional;

/**
 * 研究 AI が決定できる状態かの追跡（Phase 22）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>行動の割合が 0 だったとき、「その行動を選ばなかった」のか「AI がそもそも決定していなかった」のかを
 * 区別するため、AI が決定できなくなった区間の<strong>始まりと終わりだけ</strong>を記録する（毎 tick は書かない）。</p>
 *
 * <h2>理由（コード上で区別できるものだけ）</h2>
 * <ul>
 *   <li>{@link Reason#ENTITY_NOT_LOADED} — 個体が登録済みだが、どのワールドにもロードされていない（遠いチャンク・死亡）</li>
 *   <li>{@link Reason#NOT_TAMED} — テイムされていない（{@code AiWolfGoal.canStart} が false を返す）</li>
 *   <li>{@link Reason#SITTING} — 座っている（同上）</li>
 *   <li>{@link Reason#LEASHED} — リードに繋がれている（同上）</li>
 *   <li>{@link Reason#NO_RECENT_DECISION} — 上のどれでもないのに、一定時間（{@link #NO_DECISION_TICKS}）決定がない。
 *       優先度の高い別の行動（バニラの近接戦闘など）が移動の制御を持っていて、研究 AI のゴールが評価されていない場合など。
 *       原因をこれ以上特定できないので、推測の理由は付けない</li>
 * </ul>
 * <p>研究計画の OWNER_TOO_FAR は、飼い主が遠くても決定は止まらない（文脈が変わるだけ）ため、理由として使わない。</p>
 */
public final class AiAvailability {

    /**
     * 決定がないまま「止まっている」とみなすまでの時間 [ticks]。通常の決定間隔（40）と
     * 最小継続時間（60）より十分長い 10 秒にする（一時的な遅れを停止と数えないため）。
     */
    public static final long NO_DECISION_TICKS = 200L;

    /** 停止の理由。 */
    public enum Reason {
        ENTITY_NOT_LOADED, NOT_TAMED, SITTING, LEASHED, NO_RECENT_DECISION
    }

    /**
     * 個体の観測値から、停止の理由を決める（理由がなければ稼働中）。優先順位は列挙の順。
     *
     * @param loaded           ロードされているか
     * @param tamed            テイム済みか
     * @param sitting          座っているか
     * @param leashed          リードに繋がれているか
     * @param lastDecisionTick 最後の決定のワールド時刻（未決定なら {@link Long#MIN_VALUE}）
     * @param worldTime        現在のワールド時刻
     * @return 停止の理由。稼働中なら空
     */
    public static Optional<Reason> classify(boolean loaded, boolean tamed, boolean sitting, boolean leashed,
                                            long lastDecisionTick, long worldTime) {
        if (!loaded) {
            return Optional.of(Reason.ENTITY_NOT_LOADED);
        }
        if (!tamed) {
            return Optional.of(Reason.NOT_TAMED);
        }
        if (sitting) {
            return Optional.of(Reason.SITTING);
        }
        if (leashed) {
            return Optional.of(Reason.LEASHED);
        }
        if (lastDecisionTick == Long.MIN_VALUE || worldTime - lastDecisionTick > NO_DECISION_TICKS) {
            return Optional.of(Reason.NO_RECENT_DECISION);
        }
        return Optional.empty();
    }

    /**
     * 状態の変化。
     *
     * @param event          {@code AI_SUSPENDED} / {@code AI_RESUMED} / {@code AI_SUSPENSION_REASON_CHANGED}
     * @param reason         現在の理由（再開なら {@code null}）
     * @param previousReason 直前の理由（停止の開始なら {@code null}）
     * @param suspendedSinceTick 停止が始まったワールド時刻
     * @param suspendedDurationTicks 再開時の停止の長さ（それ以外は {@code null}）
     */
    public record Transition(String event, Reason reason, Reason previousReason, long suspendedSinceTick,
                             Long suspendedDurationTicks) {
    }

    /** 1 個体分の追跡器。停止 → 再開は必ず 1 組として出る。 */
    public static final class Tracker {
        private boolean observed;
        private Reason current;
        private long suspendedSince;

        /**
         * 新しい観測を与える。変化があったときだけ Transition を返す。
         *
         * <p>最初の観測が稼働中なら何も出さない。最初の観測が停止中なら AI_SUSPENDED を出す。
         * 停止中に理由だけが変わった（座る → リード）場合は AI_SUSPENSION_REASON_CHANGED を出し、
         * 停止の開始時刻は変えない（停止と再開の組はずっと 1 組）。</p>
         *
         * @param reason    停止の理由（稼働中なら {@code null}）
         * @param worldTime ワールド時刻
         * @return 変化
         */
        public Optional<Transition> update(Reason reason, long worldTime) {
            boolean first = !observed;
            observed = true;
            Reason previous = current;
            current = reason;
            if (reason == null && previous == null) {
                return Optional.empty();
            }
            if (previous == null) {
                suspendedSince = worldTime;
                return Optional.of(new Transition("AI_SUSPENDED", reason, null, worldTime, null));
            }
            if (reason == null) {
                return Optional.of(new Transition("AI_RESUMED", null, previous, suspendedSince,
                        Math.max(0L, worldTime - suspendedSince)));
            }
            if (reason != previous && !first) {
                return Optional.of(new Transition("AI_SUSPENSION_REASON_CHANGED", reason, previous,
                        suspendedSince, null));
            }
            return Optional.empty();
        }

        /** @return 現在の停止の理由（稼働中なら {@code null}） */
        public Reason current() {
            return current;
        }
    }

    private AiAvailability() {
        throw new AssertionError("utility class");
    }
}
