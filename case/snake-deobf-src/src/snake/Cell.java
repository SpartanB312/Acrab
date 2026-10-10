package snake;

import java.awt.Color;
import java.util.ArrayList;

/**
 * One grid cell: a three-entry colour palette plus the Swing component that draws it.
 *
 * <p>Deobfuscation note: obfuscated class {@code b} with fields {@code a} (palette),
 * {@code b} (initial state) and {@code c} (panel), and method {@code a(I)V} renamed to
 * {@link #setState(int)}. The palette literal was recovered from {@code b.<init>(I)V}: the
 * entry order is darkGray, BLUE, white, which fixes the meaning of the state indices used by
 * the rest of the game (0 = empty, 1 = food, 2 = snake head).
 */
public class Cell {

    public static final int STATE_EMPTY = 0;
    public static final int STATE_FOOD = 1;
    public static final int STATE_HEAD = 2;

    private final ArrayList<Color> palette = new ArrayList<>();

    /** Only read by the constructor to pick the initial panel colour. */
    private final int initialState;

    private final CellPanel panel;

    public Cell(int state) {
        palette.add(Color.darkGray);
        palette.add(Color.BLUE);
        palette.add(Color.white);
        this.initialState = state;
        this.panel = new CellPanel(palette.get(this.initialState));
    }

    public void setState(int state) {
        panel.setColor(palette.get(state));
    }

    public CellPanel getPanel() {
        return panel;
    }
}
