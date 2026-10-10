package snake;

/**
 * Integer grid coordinate pair: the snake head, every body segment and the food are all
 * instances of this type.
 *
 * <p>Deobfuscation notes:
 * <ul>
 *   <li>The obfuscated original class {@code d} carried a {@code public static boolean e} that
 *       served as an opaque-predicate carrier. Its reachable writer assigns false under the
 *       recorded constructor argument 2429. It is removed for that observed entry context;
 *       this does not establish a global invariant for arbitrary callers.</li>
 *   <li>Fields {@code x2}/{@code y2} (original {@code c}/{@code d}) and their getters are never
 *       written and never called anywhere in the original program. They are preserved here purely
 *       for structural fidelity; they carry no behaviour.</li>
 * </ul>
 */
public class GridPoint {

    public int x;
    public int y;

    /** Never assigned or read by the original program. See class note. */
    public int x2;

    /** Never assigned or read by the original program. See class note. */
    public int y2;

    public GridPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getX2() {
        return x2;
    }

    public int getY2() {
        return y2;
    }
}
