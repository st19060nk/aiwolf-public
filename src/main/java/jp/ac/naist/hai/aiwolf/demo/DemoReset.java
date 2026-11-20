package jp.ac.naist.hai.aiwolf.demo;

import java.util.Objects;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;

/**
 * デモ撮影用のリセット。個体を研究で定義された初期状態へ戻す。
 *
 * <p><strong>デモ専用の性格値は作らない。</strong>内部状態・選好は
 * {@code WolfInternalState.createInitial()} / {@code PreferenceVector.createInitial()}
 * の値（全個体共通の研究初期値）へ戻り、記憶・空間記憶・現在行動・決定タイミング・
 * 戦闘エピソード・観測器のタイマー（撫でのクールダウン、訪問済みバイオーム、分離判定）は
 * すべて空になる。</p>
 *
 * <p>個体を区別するために残すのは次の 3 つだけで、どれも行動の計算には使われない。</p>
 * <ul>
 *   <li>個体固有シード（決定インデックスは 0 へ戻す。同じ個体は同じ乱数列から再出発する）</li>
 *   <li>実験条件（リセットで条件が勝手に変わらないように）</li>
 *   <li>記憶 ID と戦闘 ID の採番カウンタ（ログ上でリセット前の記録と ID が衝突しないように）</li>
 * </ul>
 */
public final class DemoReset {

    private DemoReset() {
        throw new AssertionError("utility class");
    }

    /**
     * 既存のランタイムから、研究初期状態の新しいランタイムを作る。元のランタイムは変更しない。
     *
     * @param previous リセット前のランタイム
     * @return 初期状態のランタイム
     */
    public static WolfAiController freshController(WolfAiController previous) {
        Objects.requireNonNull(previous, "previous");
        WolfAiAgent old = previous.agent();
        WolfAiAgent agent = WolfAiAgent.restore(old.wolfId(), old.condition(), old.random().seed(), 0L);
        agent.setOwnerResearchId(old.ownerResearchId());
        agent.restoreMemorySequence(old.memorySequence());
        agent.setLearningFrozen(old.isLearningFrozen());

        WolfAiController controller = new WolfAiController(agent);
        controller.observer().combatTracker()
                .restoreCombatSequence(previous.observer().combatTracker().combatSequence());
        return controller;
    }
}
