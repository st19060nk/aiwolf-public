package jp.ac.naist.hai.aiwolf.study;

import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.AdaptiveFeatures;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;

/**
 * Study 2（記憶を覚えていると人は感じるか）のための記憶 ON / OFF（Phase 24）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>記憶の条件を比べるとき、<strong>内部状態・選好・文脈は同じまま、エピソード記憶と空間記憶の寄与だけ</strong>
 * を変えたい。そこで個体の実験条件を、状態の学習と選好学習のフラグは保ったまま、記憶のフラグだけが違う
 * 既存の条件へ切り替える。<strong>記憶そのもの（記憶ストア・空間記憶）は消さない</strong>ので、ON に戻せば元どおり効く。</p>
 *
 * <table>
 *   <caption>切り替え先</caption>
 *   <tr><th>今の条件（状態・選好）</th><th>記憶 ON</th><th>記憶 OFF</th></tr>
 *   <tr><td>状態 ○・選好 ○</td><td>ADAPTIVE</td><td>STATE_PREFERENCE</td></tr>
 *   <tr><td>状態 ○・選好 −</td><td>STATE_MEMORY</td><td>STATE_ONLY</td></tr>
 * </table>
 *
 * <p>状態の学習をしない STATIC は対象外（Study 2 の比較に使わない）。エピソード記憶だけ・空間記憶だけの ON / OFF は、
 * 選好学習を伴う既存の条件がないため（STATE_EPISODIC / STATE_SPATIAL は選好なし）、この切り替えでは扱わない。</p>
 */
public final class MemoryCondition {

    private MemoryCondition() {
        throw new AssertionError("utility class");
    }

    /**
     * 記憶だけを ON / OFF した条件を返す。
     *
     * @param current 今の条件
     * @param memory  記憶を使うなら true
     * @return 切り替え先。対応する条件がなければ空
     */
    public static Optional<ExperimentalCondition> withMemory(ExperimentalCondition current, boolean memory) {
        AdaptiveFeatures features = current.features();
        if (!features.stateLearning()) {
            return Optional.empty();
        }
        AdaptiveFeatures target = new AdaptiveFeatures(true, memory, memory, features.preferenceLearning());
        for (ExperimentalCondition condition : ExperimentalCondition.values()) {
            if (condition.features().equals(target)) {
                return Optional.of(condition);
            }
        }
        return Optional.empty();
    }
}
