package com.squeezeit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Persists user output-path preference to {@code ~/.squeezeit_settings.json}.
 * Hand-rolled JSON — no extra dependency required.
 * All methods are thread-safe.
 */
public final class AppSettings {

    public enum OutputMode {
        SQUEEZIT_FOLDER,   // ~/SqueezeIt/  (default)
        ALWAYS_ASK,        // DirectoryChooser before every batch
        FIXED_PATH         // user-supplied absolute path
    }

    private static final Path SETTINGS_FILE =
            Path.of(System.getProperty("user.home"), ".squeezeit_settings.json");

    private OutputMode outputMode = OutputMode.SQUEEZIT_FOLDER;
    private String fixedPath = "";

    private static volatile AppSettings instance;

    private AppSettings() { load(); }

    public static AppSettings get() {
        if (instance == null) {
            synchronized (AppSettings.class) {
                if (instance == null) instance = new AppSettings();
            }
        }
        return instance;
    }

    // ── Getters / setters (each setter auto-persists) ────────────────────

    public OutputMode getOutputMode() { return outputMode; }
    public void setOutputMode(OutputMode m) { outputMode = m; save(); }

    public String getFixedPath() { return fixedPath == null ? "" : fixedPath; }
    public void setFixedPath(String p) { fixedPath = p == null ? "" : p; save(); }

    /**
     * Returns the resolved output directory based on current settings.
     * Returns {@code null} when mode is {@link OutputMode#ALWAYS_ASK}
     * — the caller must show a {@code DirectoryChooser} in that case.
     *
     * @param sourceFile one of the input files (used to fall back to its parent)
     */
    public File resolveOutputDir(File sourceFile) {
        return switch (outputMode) {
            case SQUEEZIT_FOLDER -> {
                File d = new File(System.getProperty("user.home"), "SqueezeIt");
                d.mkdirs();
                yield d;
            }
            case FIXED_PATH -> {
                if (fixedPath != null && !fixedPath.isBlank()) {
                    File d = new File(fixedPath);
                    d.mkdirs();
                    yield d;
                }
                // Blank fixed path → fall back to source directory
                yield sourceFile != null ? sourceFile.getParentFile()
                        : new File(System.getProperty("user.home"));
            }
            case ALWAYS_ASK -> null;
        };
    }

    /** Human-readable label for each mode (used to populate the UI ComboBox). */
    public static String modeLabel(OutputMode m) {
        return switch (m) {
            case SQUEEZIT_FOLDER -> "SqueezeIt Folder  (~/SqueezeIt)";
            case ALWAYS_ASK      -> "Always Ask";
            case FIXED_PATH      -> "Fixed Path…";
        };
    }

    /** Reverse-lookup: label → OutputMode. */
    public static OutputMode fromLabel(String label) {
        if (label == null) return OutputMode.SQUEEZIT_FOLDER;
        if (label.startsWith("Always"))   return OutputMode.ALWAYS_ASK;
        if (label.startsWith("Fixed"))    return OutputMode.FIXED_PATH;
        return OutputMode.SQUEEZIT_FOLDER;
    }

    // ── Persistence ──────────────────────────────────────────────────────

    private synchronized void load() {
        try {
            if (!Files.exists(SETTINGS_FILE)) return;
            String json = Files.readString(SETTINGS_FILE, StandardCharsets.UTF_8);

            if      (json.contains("\"ALWAYS_ASK\"")) outputMode = OutputMode.ALWAYS_ASK;
            else if (json.contains("\"FIXED_PATH\""))  outputMode = OutputMode.FIXED_PATH;
            else                                       outputMode = OutputMode.SQUEEZIT_FOLDER;

            int s = json.indexOf("\"fixedPath\": \"");
            if (s >= 0) {
                s += "\"fixedPath\": \"".length();
                int e = json.indexOf('"', s);
                if (e > s) fixedPath = json.substring(s, e).replace("\\\\", "\\");
            }
        } catch (Exception ignored) {}
    }

    private synchronized void save() {
        try {
            String escaped = fixedPath.replace("\\", "\\\\").replace("\"", "\\\"");
            String json = "{\n" +
                    "  \"outputMode\": \"" + outputMode.name() + "\",\n" +
                    "  \"fixedPath\": \"" + escaped + "\"\n}\n";
            Files.writeString(SETTINGS_FILE, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {}
    }
}
