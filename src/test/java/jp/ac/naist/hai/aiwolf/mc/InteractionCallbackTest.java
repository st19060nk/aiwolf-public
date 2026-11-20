package jp.ac.naist.hai.aiwolf.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 撫で・餌が 1 回の右クリックで 1 回だけ処理されることの検証。
 *
 * <p>クライアントは 1 回の右クリックで INTERACT_AT と INTERACT の 2 つのパケットを送り、
 * Fabric はサーバでその両方について {@code UseEntityCallback} を発火する（hitResult の有無で区別できる）。
 * 以前は両方を処理していたため、餌やり 1 回が FEED 2 回として記録されていた。</p>
 */
final class InteractionCallbackTest {

    @Test
    @DisplayName("1 回の右クリック（INTERACT_AT + INTERACT）のうち処理するのは 1 回だけ")
    void oneClickIsHandledOnce() {
        int handled = 0;
        for (boolean atLocation : new boolean[] {true, false}) {   // サーバに届く順
            if (WolfInteractionHandler.handles(false, true, atLocation)) {
                handled++;
            }
        }
        assertEquals(1, handled);
    }

    @Test
    @DisplayName("クライアント側・オフハンドのコールバックは処理しない")
    void clientAndOffHandAreIgnored() {
        assertFalse(WolfInteractionHandler.handles(true, true, false));
        assertFalse(WolfInteractionHandler.handles(false, false, false));
    }
}
