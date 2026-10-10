package snake;

import java.util.ArrayList;

/**
 * The game loop: moves the snake, resolves collisions and food, repaints the board and sleeps
 * one frame.
 *
 * <p>Deobfuscation notes for this class (the heavily obfuscated original {@code e}):
 * <ul>
 *   <li><b>String encryption.</b> {@code e.<clinit>()V} built a {@code String[2]} from the
 *       encoded literal {@code "sA\FP\!~/0\u0005\u0006i{}bz4"} with a 7-byte cycling XOR key
 *       {@code 48,14,16,15,3,21,110} and a self-describing chunk header (each chunk's length
 *       comes from a header byte that follows the previous chunk). It decodes to
 *       {@code {"COLISION! \n", "Yummy!"}}. The first element really does spell "COLISION" with
 *       one L - that is the genuine plaintext, not a decoding error, so it is reproduced
 *       verbatim.</li>
 *   <li><b>Constant encryption.</b> {@code e.run()V} derived all five of its call arguments from
 *       {@code 81557154710886L} by XOR and shift against large literals. Every one of those
 *       parameters was used by {@code long} comparison guards. They are folded for the recorded
 *       game-loop call context; arbitrary caller arguments are outside this reconstruction's
 *       verified scope.</li>
 *   <li><b>Control flow.</b> Every method was interleaved with tests against the static boolean
 *       {@code e.h}. Bytecode reachability analysis proves its sole writer
 *       ({@code f.<init>(JC)V} index 162) executes but pushes {@code ICONST_0} given the call-site
 *       {@code char} argument 2429, so {@code e.h} is {@code false} in that entry context. Removing those
 *       tests collapses irreducible control flow into plain loops, a plain switch and
 *       straight-line code.</li>
 *   <li><b>Renaming.</b> fields {@code a..g} became {@code grid, head, score, frameDelayMillis,
 *       direction, body, food}; the opaque-predicate field {@code h} was removed entirely; the
 *       encrypted table {@code i} became {@link #MESSAGES}.</li>
 * </ul>
 */
public class GameThread extends Thread {

    /** Recovered plaintext of the encrypted {@code String[2]}. 0 = game-over banner, 1 = food banner. */
    private static final String[] MESSAGES = { "COLISION! \n", "Yummy!" };

    public static final int RIGHT = 1;
    public static final int LEFT = 2;
    public static final int UP = 3;
    public static final int DOWN = 4;

    /** Board shared with {@link SnakeFrame}. */
    private ArrayList<ArrayList<Cell>> grid = SnakeFrame.grid;

    private GridPoint head;
    private int score = 3;
    private long frameDelayMillis = 50L;

    /** Shared with {@link SnakeKeyListener}. */
    public static int direction;

    private final ArrayList<GridPoint> body = new ArrayList<>();
    private GridPoint food;

    /**
     * @param start the snake's initial head position
     */
    GameThread(GridPoint start) {
        grid = SnakeFrame.grid;
        head = new GridPoint(start.x, start.y);
        direction = RIGHT;
        body.add(new GridPoint(head.getX(), head.getY()));
        food = new GridPoint(SnakeFrame.COLS - 1, SnakeFrame.ROWS - 1);
        paintCell(food);
    }

    @Override
    public void run() {
        while (true) {
            move(direction);
            resolveCollisionsAndFood();
            clearBody();
            paintBodySegments();
            trimBodySegments();
            sleepFrame();
        }
    }

    /**
     * Advances the head one cell in the given direction and appends the new head to the body.
     *
     * <p>The four cases come straight from the {@code TABLESWITCH} at bytecode index 9 with
     * targets {@code 1->@180, 2->@110, 3->@41, 4->@10}. Every case ends in its own
     * {@code ArrayList.add} and then returns; case 4 does not fall through into case 1.
     */
    private void move(int dir) {
        switch (dir) {
            case RIGHT:
                head.setPosition(Math.abs(head.x + 1) % SnakeFrame.COLS, head.y);
                body.add(new GridPoint(head.x, head.y));
                return;

            case LEFT:
                if (head.x - 1 < 0) {
                    head.setPosition(SnakeFrame.COLS - 1, head.y);
                } else {
                    head.setPosition(Math.abs(head.x - 1) % SnakeFrame.COLS, head.y);
                }
                body.add(new GridPoint(head.x, head.y));
                return;

            case UP:
                if (head.y - 1 < 0) {
                    head.setPosition(head.x, SnakeFrame.ROWS - 1);
                } else {
                    head.setPosition(head.x, Math.abs(head.y - 1) % SnakeFrame.ROWS);
                }
                body.add(new GridPoint(head.x, head.y));
                return;

            case DOWN:
                head.setPosition(head.x, (head.y + 1) % SnakeFrame.ROWS);
                body.add(new GridPoint(head.x, head.y));
                return;

            default:
                return;
        }
    }

    /**
     * Scans the body for self-collision, then handles the food pickup.
     *
     * <p>Fidelity notes:
     * <ul>
     *   <li>The head used for both comparisons is {@code body.get(body.size() - 1)}, not the
     *       {@link #head} field directly. {@link #move} appends the new head as the last entry,
     *       so the two agree in practice; the original form is kept so behaviour is unchanged
     *       even if {@code move} ever returns without appending.</li>
     *   <li>The self-collision scan deliberately excludes the last body entry (the head itself),
     *       so the loop runs {@code i <= body.size() - 2}.</li>
     *   <li>The food comparison is <em>transposed</em>: it tests {@code head.x == food.y} and
     *       {@code head.y == food.x}. That is a genuine quirk of the original program (bytecode
     *       indexes 83-104) and is reproduced rather than corrected.</li>
     *   <li>{@link #paintCell(GridPoint)} is called only on the food-hit path, not on every
     *       invocation (the not-eaten path jumps straight to the return at index 142).</li>
     * </ul>
     */
    private void resolveCollisionsAndFood() {
        GridPoint currentHead = body.get(body.size() - 1);

        for (int i = 0; i <= body.size() - 2; i++) {
            GridPoint segment = body.get(i);
            if (currentHead.getX() == segment.getX() && currentHead.getY() == segment.getY()) {
                gameOver();
            }
        }

        if (currentHead.getX() == food.getY() && currentHead.getY() == food.getX()) {
            System.out.println(MESSAGES[1]);
            score++;
            food = spawnFood();
            paintCell(food);
        }
    }

    /** Prints the game-over banner and parks the game thread forever. */
    private void gameOver() {
        System.out.println(MESSAGES[0]);
        while (true) {
            sleepFrame();
        }
    }

    /**
     * Paints one board cell as the food cell.
     *
     * <p>Fidelity note: the original indexes the board {@code grid.get(point.x).get(point.y)}
     * (indexes 3 and 7 of this method), i.e. x outer and y inner, while
     * {@link #clearBody()} and {@link #paintBodySegments()} index it
     * {@code grid.get(point.y).get(point.x)}. That inconsistency is present in the original
     * program and is preserved rather than silently corrected. The board is square, so the call
     * still resolves to a valid cell.
     */
    private void paintCell(GridPoint point) {
        grid.get(point.getX()).get(point.getY()).setState(Cell.STATE_FOOD);
    }

    /** Paints every body cell back to the empty colour. */
    private void clearBody() {
        for (GridPoint segment : body) {
            grid.get(segment.getY()).get(segment.getX()).setState(Cell.STATE_EMPTY);
        }
    }

    /**
     * Repaints the leading {@code body.size() - score} body segments in the snake colour.
     *
     * <p>The original walks the body from the tail end backwards while counting a local copy of
     * the score down to zero; once the counter reaches zero every remaining segment is painted.
     * The counter is a snapshot of the score field, and the field itself is never written here,
     * so with the game's initial score of 3 this paints {@code body.size() - 3} segments and
     * leaves the three newest (including the head) in the empty colour.
     */
    private void paintBodySegments() {
        int remaining = score;
        for (int i = body.size() - 1; i >= 0; i--) {
            if (remaining == 0) {
                GridPoint segment = body.get(i);
                grid.get(segment.getY()).get(segment.getX()).setState(Cell.STATE_HEAD);
            } else {
                remaining--;
            }
        }
    }

    /**
     * Second loop of the original {@code e.e(J)V}: trims body entries from the tail end.
     *
     * <p>The loop walks the body backwards while counting a local copy of the score down to zero;
     * the counter is decremented on every iteration that does <em>not</em> remove, but is left
     * untouched on the iterations that do. So the counter reaches zero and then stays there, and
     * every remaining iteration removes an entry.
     *
     * <p>Concretely, with score {@code S} and body size {@code N}, the first {@code S}
     * iterations only decrement the counter and the remaining {@code max(0, N - S)} iterations
     * remove at descending indices. Bytecode: {@code ILOAD 4} at index 68, the counter test
     * {@code IFNE ->@86} at index 73 jumping straight to the {@code IINC 4 -1} at index 87, and
     * the removal guard {@code IFEQ ->@88} at index 85 skipping that decrement.
     *
     * <p>This is what keeps the snake a constant length: {@link #move} appends one segment per
     * frame and this loop removes {@code max(0, N - S)} entries, retaining at most {@code S}.
     * With the initial score of 3 the body settles at three segments until the score changes.
     */
    private void trimBodySegments() {
        int counter = score;
        for (int i = body.size() - 1; i >= 0; i--) {
            if (counter != 0) {
                counter--;
            } else {
                body.remove(i);
            }
        }
    }

    /**
     * Picks a random board cell, re-rolling while it overlaps the body.
     *
     * <p>Fidelity note: the original's overlap test is transposed - it compares the candidate's
     * Y against each segment's X and the candidate's X against each segment's Y (bytecode
     * indexes 35/41 and 50/56). This is a genuine quirk of the original program and is reproduced
     * rather than corrected. After a re-roll the original resets the loop index to 0 and then
     * increments it, so the rescan resumes at index 1 and never re-tests index 0.
     */
    private GridPoint spawnFood() {
        GridPoint candidate = new GridPoint((int) (Math.random() * 19.0), (int) (Math.random() * 19.0));

        for (int i = 0; i <= body.size() - 1; i++) {
            GridPoint segment = body.get(i);

            boolean overlaps = candidate.getY() == segment.getX();
            if (overlaps) {
                overlaps = candidate.getX() == segment.getY();
            }

            if (overlaps) {
                candidate = new GridPoint((int) (Math.random() * 19.0), (int) (Math.random() * 19.0));
                i = 0;      // restart the scan; the loop increment resumes at 1, as in the original
                continue;
            }
        }

        return candidate;
    }

    private void sleepFrame() {
        try {
            sleep(frameDelayMillis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
