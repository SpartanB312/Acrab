package snake;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * Arrow-key direction handler with 180-degree reversal protection.
 *
 * <p>Deobfuscation note: obfuscated class {@code a}. The original routed all four keys through a
 * chain of opaque-predicate blocks testing the never-true static boolean {@code e.h}; with that
 * boolean proven constantly {@code false} the chain collapses into the {@code TABLESWITCH} at
 * bytecode index 8 and four straight-line blocks.
 *
 * <p>The recovered mapping is unusual and is preserved verbatim (verified against the switch
 * targets {@code 37->@32, 38->@21, 39->@9, 40->@43}):
 * <pre>
 *   VK_LEFT  (37) -&gt; {@link GameThread#LEFT}  (2), refused when the current direction is RIGHT (1)
 *   VK_UP    (38) -&gt; {@link GameThread#UP}    (3), refused when the current direction is DOWN  (4)
 *   VK_RIGHT (39) -&gt; {@link GameThread#RIGHT} (1), refused when the current direction is LEFT  (2)
 *   VK_DOWN  (40) -&gt; {@link GameThread#DOWN}  (4), refused when the current direction is UP    (3)
 * </pre>
 * Each guard refuses exactly the opposite direction (1&lt;-&gt;2 and 3&lt;-&gt;4), so this is a
 * correct 180-degree reversal check despite the non-obvious value assignment.
 */
public class SnakeKeyListener extends KeyAdapter {

    @Override
    public void keyPressed(KeyEvent event) {
        int current = GameThread.direction;

        switch (event.getKeyCode()) {
            case KeyEvent.VK_LEFT:
                if (current == GameThread.RIGHT) {
                    return;
                }
                GameThread.direction = GameThread.LEFT;
                return;

            case KeyEvent.VK_UP:
                if (current == GameThread.DOWN) {
                    return;
                }
                GameThread.direction = GameThread.UP;
                return;

            case KeyEvent.VK_RIGHT:
                if (current == GameThread.LEFT) {
                    return;
                }
                GameThread.direction = GameThread.RIGHT;
                return;

            case KeyEvent.VK_DOWN:
                if (current == GameThread.UP) {
                    return;
                }
                GameThread.direction = GameThread.DOWN;
                return;

            default:
                return;
        }
    }
}
