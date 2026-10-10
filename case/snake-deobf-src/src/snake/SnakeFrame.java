package snake;

import java.awt.GridLayout;
import java.util.ArrayList;
import javax.swing.JFrame;

/**
 * The game window: builds the 20x20 grid of cells and starts the game thread.
 *
 * <p>Deobfuscation notes:
 * <ul>
 *   <li>Obfuscated class {@code f}; fields {@code a} -&gt; {@link #grid}, {@code b} -&gt;
 *       {@link #ROWS}, {@code c} -&gt; {@link #COLS}.</li>
 *   <li>The original constructor signature was {@code f(long, char)}. Both parameters only fed a
 *       constant-folding expression that produced the dead {@code long} key handed to the game
 *       thread; neither was ever read for a decision. Both parameters, and the arithmetic in
 *       {@code Snake.main} that fed them, have been removed.</li>
 *   <li>The original constructor body was a deeply nested set of loops interleaved with
 *       opaque-predicate tests against {@code d.e} and {@code e.h}. Both of those statics are
 *       proven to be constantly {@code false}, which collapses the nesting into two plain loops
 *       and drops a dead early return.</li>
 * </ul>
 */
public class SnakeFrame extends JFrame {

    private static final long serialVersionUID = -2542001418764869760L;

    /** Row-major board: {@code grid.get(row).get(column)}. */
    public static ArrayList<ArrayList<Cell>> grid;

    public static final int ROWS = 20;
    public static final int COLS = 20;

    public SnakeFrame() {
        super();

        grid = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            ArrayList<Cell> cells = new ArrayList<>();
            for (int column = 0; column < COLS; column++) {
                cells.add(new Cell(Cell.STATE_HEAD));
            }
            grid.add(cells);
        }

        getContentPane().setLayout(new GridLayout(ROWS, COLS, 0, 0));
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLS; column++) {
                getContentPane().add(grid.get(row).get(column).getPanel());
            }
        }

        GridPoint start = new GridPoint(10, 10);
        GameThread game = new GameThread(start);
        game.start();

        addKeyListener(new SnakeKeyListener());
    }
}
