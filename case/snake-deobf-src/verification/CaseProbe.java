import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import sun.misc.Unsafe;

public class CaseProbe {
    private static final Unsafe UNSAFE;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, arguments); method.setAccessible(true); return method;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ArrayList<String> trim(URLClassLoader loader, boolean original, int size, int score) throws Exception {
        Class<?> gameClass = loader.loadClass(original ? "e" : "snake.GameThread");
        Class<?> pointClass = loader.loadClass(original ? "d" : "snake.GridPoint");
        Class<?> cellClass = loader.loadClass(original ? "b" : "snake.Cell");
        Object game = UNSAFE.allocateInstance(gameClass);
        if (original) {
            field(gameClass, "h").setBoolean(null, false);
            field(pointClass, "e").setBoolean(null, false);
        }
        ArrayList<ArrayList<Object>> grid = new ArrayList<>();
        for (int y = 0; y < 20; y++) {
            ArrayList<Object> row = new ArrayList<>();
            for (int x = 0; x < 20; x++) row.add(cellClass.getConstructor(int.class).newInstance(0));
            grid.add(row);
        }
        ArrayList<Object> body = new ArrayList<>();
        for (int i = 0; i < size; i++) body.add(pointClass.getConstructor(int.class, int.class).newInstance(i, 5));
        field(gameClass, original ? "a" : "grid").set(game, grid);
        field(gameClass, original ? "c" : "score").setInt(game, score);
        field(gameClass, original ? "f" : "body").set(game, body);
        if (original) method(gameClass, "e", long.class).invoke(game, 41896598891982L);
        else method(gameClass, "trimBodySegments").invoke(game);
        ArrayList<String> coordinates = new ArrayList<>();
        for (Object point : body) coordinates.add(field(pointClass, original ? "a" : "x").getInt(point) + "," +
            field(pointClass, original ? "b" : "y").getInt(point));
        return coordinates;
    }

    private static int direction(URLClassLoader loader, boolean original, int current, int key) throws Exception {
        Class<?> gameClass = loader.loadClass(original ? "e" : "snake.GameThread");
        Class<?> listenerClass = loader.loadClass(original ? "a" : "snake.SnakeKeyListener");
        Field direction = field(gameClass, original ? "e" : "direction");
        if (original) field(gameClass, "h").setBoolean(null, false);
        direction.setInt(null, current);
        Object listener = UNSAFE.allocateInstance(listenerClass);
        method(listenerClass, "keyPressed", KeyEvent.class).invoke(listener,
            new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0L, 0, key, KeyEvent.CHAR_UNDEFINED));
        return direction.getInt(null);
    }

    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 2, "Expected original and rebuilt JAR paths.");
        try (URLClassLoader original = new URLClassLoader(new java.net.URL[]{Path.of(arguments[0]).toUri().toURL()},
                 ClassLoader.getPlatformClassLoader());
             URLClassLoader rebuilt = new URLClassLoader(new java.net.URL[]{Path.of(arguments[1]).toUri().toURL()},
                 ClassLoader.getPlatformClassLoader())) {
            int trimCases = 0;
            for (int score : new int[]{0, 1, 3, 5}) {
                for (int size = 0; size <= 8; size++) {
                    ArrayList<String> expected = trim(original, true, size, score);
                    ArrayList<String> actual = trim(rebuilt, false, size, score);
                    require(expected.equals(actual), "Trim divergence for size=" + size + ", score=" + score +
                        ": original=" + expected + ", rebuilt=" + actual);
                    require(expected.size() == Math.min(size, score), "Unexpected original trim invariant.");
                    trimCases++;
                }
            }
            for (int current = 1; current <= 4; current++) {
                for (int key : new int[]{37, 38, 39, 40}) require(direction(original, true, current, key) ==
                    direction(rebuilt, false, current, key), "Direction divergence.");
            }
            require(trim(original, true, 4, 3).size() == 3, "Move/trim length must return to score three.");
            System.out.println("{\"trimCases\":" + trimCases + ",\"directionCases\":16,\"passed\":true}");
        }
    }
}
