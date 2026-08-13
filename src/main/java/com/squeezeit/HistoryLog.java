package com.squeezeit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Persists a JSON-array compression history to {@code ~/.squeezeit_history.json}.
 *
 * <p>Uses only {@code java.io} / {@code java.time} — no external dependency.
 * Each call to {@link #append} adds one entry to the array atomically.
 */
public final class HistoryLog {

    /** Path of the history file in the user's home directory. */
    private static final Path LOG_FILE = Path.of(
            System.getProperty("user.home"), ".squeezeit_history.json");

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // Utility class
    private HistoryLog() {}

    /**
     * Appends one compression entry to the history log.
     *
     * @param inputFile   original source file
     * @param outputFile  compressed output file
     * @param format      output format string (JPEG / PNG / WEBP / PDF)
     * @param targetBytes target size the user requested
     */
    public static void append(File inputFile, File outputFile, String format, long targetBytes) {
        try {
            long inSize  = inputFile.length();
            long outSize = outputFile.exists() ? outputFile.length() : 0L;
            double reduction = inSize > 0 ? (1.0 - (double) outSize / inSize) * 100.0 : 0.0;

            String entry = buildEntry(
                    inputFile.getName(),
                    outputFile.getName(),
                    format,
                    targetBytes,
                    inSize,
                    outSize,
                    reduction);

            writeEntry(entry);
        } catch (Exception ignored) {
            // History logging is best-effort — never crash the main workflow
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static String buildEntry(
            String inputName, String outputName,
            String format, long targetBytes,
            long inSize, long outSize, double reduction) {

        return "  {\n" +
               "    \"timestamp\": \""    + LocalDateTime.now().format(FMT) + "\",\n" +
               "    \"inputFile\": \""   + escape(inputName)  + "\",\n" +
               "    \"outputFile\": \""  + escape(outputName) + "\",\n" +
               "    \"format\": \""      + escape(format)     + "\",\n" +
               "    \"targetSize\": "   + targetBytes          + ",\n" +
               "    \"inputSize\": "    + inSize               + ",\n" +
               "    \"outputSize\": "   + outSize              + ",\n" +
               "    \"reduction\": \""  + String.format("%.1f%%", reduction) + "\"\n" +
               "  }";
    }

    /**
     * Inserts the new entry into the JSON array in the log file,
     * creating the file if it doesn't exist.
     */
    private static synchronized void writeEntry(String entry) throws IOException {
        if (!Files.exists(LOG_FILE)) {
            // Create fresh array
            Files.writeString(LOG_FILE,
                    "[\n" + entry + "\n]\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            return;
        }

        // Read existing content, insert before closing ']'
        String existing = Files.readString(LOG_FILE, StandardCharsets.UTF_8).trim();

        String updated;
        if (existing.equals("[]") || existing.equals("[\n]")) {
            updated = "[\n" + entry + "\n]\n";
        } else if (existing.endsWith("]")) {
            // Strip trailing ']', append comma + new entry + ']'
            updated = existing.substring(0, existing.length() - 1).stripTrailing()
                    + ",\n" + entry + "\n]\n";
        } else {
            // Malformed file — overwrite with single-element array
            updated = "[\n" + entry + "\n]\n";
        }

        Files.writeString(LOG_FILE, updated,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /** Escapes backslashes and double-quotes for JSON string values. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Returns the absolute path of the history log file.
     * Useful for displaying in the UI.
     */
    public static String getLogPath() {
        return LOG_FILE.toAbsolutePath().toString();
    }
}
