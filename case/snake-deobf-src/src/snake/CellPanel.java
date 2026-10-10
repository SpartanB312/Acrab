package snake;

import java.awt.Color;
import javax.swing.JPanel;

/**
 * The Swing component that renders one grid cell.
 *
 * <p>Deobfuscation note: obfuscated class {@code g}, method {@code a(Ljava/awt/Color;)V}
 * renamed to {@link #setColor(Color)}.
 */
public class CellPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    public CellPanel(Color color) {
        setBackground(color);
    }

    public void setColor(Color color) {
        setBackground(color);
        repaint();
    }
}
