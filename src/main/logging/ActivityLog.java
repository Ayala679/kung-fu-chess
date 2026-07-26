package logging;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tiny append-only, timestamped text logger - used both server-side
 * (one shared log for the whole process) and client-side (one log per
 * NetworkGuiMain instance). Deliberately not a real logging framework: one
 * file, one line per event, human-readable, matching the CTD 26 brief's
 * "store logs on both server and client side, for all of the client/server
 * activity."
 */
public class ActivityLog {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final File file;

    public ActivityLog(String filePath) {
        this.file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
    }

    /** Appends one timestamped line. Failures are swallowed - logging must never break the app. */
    public synchronized void log(String event) {
        try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
            out.println("[" + LocalDateTime.now().format(TIMESTAMP) + "] " + event);
        } catch (IOException e) {
            System.err.println("ActivityLog: could not write to " + file + ": " + e.getMessage());
        }
    }
}
