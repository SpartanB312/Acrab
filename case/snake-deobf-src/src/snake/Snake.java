package snake;

import javax.swing.JFrame;

/**
 * Entry point of the snake game.
 *
 * <p>Deobfuscation notes:
 * <ul>
 *   <li>Obfuscated class {@code c}; the static final field {@code a} was an XOR-encrypted string
 *       that decrypted to {@code "Snake"}.</li>
 *   <li>The string-encryption table in {@code c.<clinit>()V} decoded {@code ">\u0015l<\u0019"}
 *       against the 7-byte cycling key {@code 109,123,13,87,124,101,116} and has been replaced
 *       by the plaintext literal.</li>
 *   <li>{@code main} derived its two window-constructor arguments from
 *       {@code 116018857672281L ^ 21598883182372L}; both arguments were dead (see
 *       {@link SnakeFrame}) and the arithmetic has been removed.</li>
 * </ul>
 */
public class Snake {

    private static final String TITLE = "Snake";

    public static void main(String[] args) {
        SnakeFrame frame = new SnakeFrame();
        frame.setTitle(TITLE);
        frame.setSize(300, 300);
        frame.setVisible(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }
}
