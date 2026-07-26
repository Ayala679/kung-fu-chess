package view;

import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import model.Piece;
import snapshot.PieceVisualState;

/**
 * Resolves and loads the current animation frame for a piece from
 * resources/pieces/<TYPE><COLOR>/states/<state>/sprites/<n>.png. Each state's
 * config.json supplies frames_per_sec/is_loop, so the frame shown depends on
 * how long the piece has been in that state. Loaded frames are cached (keyed
 * by path + target size) so a fresh disk read/resize doesn't happen for every
 * piece on every render tick.
 */
public class PieceSprites {
    private static final String ROOT = "resources/pieces";
    private static final Pattern FPS_PATTERN = Pattern.compile("\"frames_per_sec\"\\s*:\\s*(\\d+)");
    private static final Pattern LOOP_PATTERN = Pattern.compile("\"is_loop\"\\s*:\\s*(true|false)");

    private final Map<String, Img> frameCache = new HashMap<>();
    private final Map<String, Integer> frameCountCache = new HashMap<>();
    private final Map<String, int[]> configCache = new HashMap<>();

    public Img frame(Piece.Type type, Piece.Color color, PieceVisualState state,
                      long stateElapsedMillis, int cellWidth, int cellHeight) {
        String stateDir = ROOT + "/" + folderName(type, color) + "/states/" + state.name().toLowerCase();

        int[] config = readConfig(stateDir);
        int fps = config[0];
        boolean loop = config[1] == 1;
        int totalFrames = countFrames(stateDir);

        int index = (int) (stateElapsedMillis / 1000.0 * fps);
        index = loop ? Math.floorMod(index, totalFrames) : Math.min(index, totalFrames - 1);

        String path = stateDir + "/sprites/" + (index + 1) + ".png";
        String cacheKey = path + "@" + cellWidth + "x" + cellHeight;
        return frameCache.computeIfAbsent(cacheKey,
                k -> new Img().read(path, new Dimension(cellWidth, cellHeight), true, null));
    }

    private static String folderName(Piece.Type type, Piece.Color color) {
        return type.name() + (color == Piece.Color.WHITE ? "W" : "B");
    }

    private int countFrames(String stateDir) {
        return frameCountCache.computeIfAbsent(stateDir, dir -> {
            File[] files = new File(dir, "sprites").listFiles((d, name) -> name.endsWith(".png"));
            return (files == null || files.length == 0) ? 1 : files.length;
        });
    }

    private int[] readConfig(String stateDir) {
        return configCache.computeIfAbsent(stateDir, dir -> {
            try {
                String json = Files.readString(new File(dir, "config.json").toPath());
                Matcher fpsMatcher = FPS_PATTERN.matcher(json);
                Matcher loopMatcher = LOOP_PATTERN.matcher(json);
                int fps = fpsMatcher.find() ? Integer.parseInt(fpsMatcher.group(1)) : 6;
                boolean loop = !loopMatcher.find() || Boolean.parseBoolean(loopMatcher.group(1));
                return new int[]{fps, loop ? 1 : 0};
            } catch (IOException e) {
                return new int[]{6, 1};
            }
        });
    }
}
