package com.squeezeit;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JavaFX controller for {@code main-view.fxml}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Drag-and-drop file acceptance from the OS shell.</li>
 *   <li>Compression/conversion task dispatch onto a background thread.</li>
 *   <li>Real-time UI updates: progress bar, status label, file list.</li>
 *   <li>Live size preview card (before → target estimate).</li>
 *   <li>PDF → PDF re-compression via {@link CompressionEngine#recompressPdf}.</li>
 *   <li>Batch rename with user-defined prefix/suffix.</li>
 *   <li>History logging via {@link HistoryLog}.</li>
 * </ul>
 */
public class MainController implements Initializable {

    /* ── FXML-injected UI nodes ─────────────────────────────────────── */

    @FXML private VBox          dropZone;
    @FXML private Label         dropZoneLabel;
    @FXML private ListView<String> fileListView;
    @FXML private ComboBox<String> formatCombo;
    @FXML private Slider        sizeSlider;
    @FXML private Label         sizeLabel;
    @FXML private Button        processButton;
    @FXML private Button        clearButton;
    @FXML private ProgressBar   progressBar;
    @FXML private Label         statusLabel;
    @FXML private Label         resultLabel;

    // Rename fields
    @FXML private TextField     prefixField;
    @FXML private TextField     suffixField;

    // Size preview card
    @FXML private VBox          previewCard;
    @FXML private Label         previewBefore;
    @FXML private Label         previewAfter;
    @FXML private Label         previewReduction;

    /* ── State ─────────────────────────────────────────────────────── */

    /** All files currently queued by the user. */
    private final List<File> queuedFiles = new ArrayList<>();

    /** Currently running compression task (for cancellation). */
    private Task<?> activeTask;

    /* ═══════════════════════════════════════════════════════════════════
       INITIALISATION
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // ── Format combo ─────────────────────────────────────────────
        formatCombo.getItems().setAll("JPEG", "PNG", "WEBP", "PDF");
        formatCombo.setValue("JPEG");

        // ── Size slider: 50 KB → 10 MB, default 1 MB ─────────────────
        sizeSlider.setMin(50_000);
        sizeSlider.setMax(10_000_000);
        sizeSlider.setValue(1_000_000);
        updateSizeLabel(1_000_000);

        sizeSlider.valueProperty().addListener((obs, oldV, newV) -> {
            updateSizeLabel(newV.longValue());
            updatePreviewCard();
        });

        // Update preview when format changes
        formatCombo.valueProperty().addListener((obs, o, n) -> updatePreviewCard());

        // ── Progress bar ─────────────────────────────────────────────
        progressBar.setProgress(0);

        // ── Drag styling reset ────────────────────────────────────────
        dropZone.setOnMouseEntered(e -> dropZone.getStyleClass().add("drop-zone-hover"));
        dropZone.setOnMouseExited(e  -> dropZone.getStyleClass().remove("drop-zone-hover"));

        // ── Browse button doubles as clicking on drop zone ───────────
        dropZone.setOnMouseClicked(e -> onBrowseClicked());
    }

    private void updateSizeLabel(long bytes) {
        sizeLabel.setText(CompressionEngine.humanSize(bytes));
    }

    /* ═══════════════════════════════════════════════════════════════════
       SIZE PREVIEW CARD
       ═══════════════════════════════════════════════════════════════════ */

    /**
     * Refreshes the before/after preview card based on queued files + slider.
     * Called whenever files are added, slider moves, or format changes.
     */
    private void updatePreviewCard() {
        if (queuedFiles.isEmpty()) {
            previewCard.setManaged(false);
            previewCard.setVisible(false);
            return;
        }

        long totalBefore = queuedFiles.stream().mapToLong(File::length).sum();
        long target      = (long) sizeSlider.getValue();

        // For multi-file queues the target is per-file (or total for PDF merge)
        String format = formatCombo.getValue();
        long estimatedAfter;
        if ("PDF".equalsIgnoreCase(format)) {
            // PDF output = single file at target
            estimatedAfter = Math.min(target, totalBefore);
        } else {
            // Each file independently targeted
            estimatedAfter = Math.min(target * queuedFiles.size(), totalBefore);
        }

        double saving = totalBefore > 0
                ? (1.0 - (double) estimatedAfter / totalBefore) * 100.0
                : 0.0;

        previewBefore.setText(CompressionEngine.humanSize(totalBefore));
        previewAfter.setText("~" + CompressionEngine.humanSize(estimatedAfter));
        previewReduction.setText(String.format("%.0f%%", Math.max(0, saving)));

        previewCard.setManaged(true);
        previewCard.setVisible(true);
    }

    /* ═══════════════════════════════════════════════════════════════════
       DRAG-AND-DROP
       ═══════════════════════════════════════════════════════════════════ */

    @FXML
    private void onDragOver(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            boolean acceptable = db.getFiles().stream()
                    .anyMatch(f -> {
                        String ext = CompressionEngine.getExtension(f);
                        return CompressionEngine.isSupportedImage(ext)
                            || CompressionEngine.isPdf(ext);
                    });
            if (acceptable) {
                event.acceptTransferModes(TransferMode.COPY);
                dropZone.getStyleClass().add("drop-zone-active");
            }
        }
        event.consume();
    }

    @FXML
    private void onDragExited(DragEvent event) {
        dropZone.getStyleClass().remove("drop-zone-active");
        event.consume();
    }

    @FXML
    private void onDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;

        if (db.hasFiles()) {
            List<File> accepted = db.getFiles().stream()
                    .filter(f -> {
                        String ext = CompressionEngine.getExtension(f);
                        return CompressionEngine.isSupportedImage(ext)
                            || CompressionEngine.isPdf(ext);
                    })
                    .collect(Collectors.toList());

            addFiles(accepted);
            success = true;
        }

        dropZone.getStyleClass().remove("drop-zone-active");
        event.setDropCompleted(success);
        event.consume();
    }

    /* ═══════════════════════════════════════════════════════════════════
       BUTTON HANDLERS
       ═══════════════════════════════════════════════════════════════════ */

    @FXML
    private void onBrowseClicked() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Files to Compress");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images & PDFs",
                        "*.jpg", "*.jpeg", "*.png", "*.webp", "*.bmp", "*.gif", "*.tiff", "*.pdf"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));

        List<File> selected = chooser.showOpenMultipleDialog(dropZone.getScene().getWindow());
        if (selected != null) addFiles(selected);
    }

    @FXML
    private void onClearClicked() {
        queuedFiles.clear();
        fileListView.getItems().clear();
        progressBar.setProgress(0);
        setStatus("Queue cleared.", false);
        resultLabel.setText("");
        processButton.setDisable(false);
        dropZoneLabel.setText("Drop files here  or  click to browse");
        previewCard.setManaged(false);
        previewCard.setVisible(false);
    }

    @FXML
    private void onProcessClicked() {
        if (queuedFiles.isEmpty()) {
            setStatus("⚠  No files queued. Drop or browse files first.", true);
            return;
        }

        String format      = formatCombo.getValue();
        long   targetBytes = (long) sizeSlider.getValue();
        String prefix      = prefixField  != null ? prefixField.getText().trim()  : "";
        String suffix      = suffixField  != null ? suffixField.getText().trim()  : "_squeezed";
        if (suffix.isEmpty()) suffix = "_squeezed";

        // Choose output directory (same dir as first file)
        File outputDir = queuedFiles.get(0).getParentFile();

        processButton.setDisable(true);
        clearButton.setDisable(true);
        progressBar.setProgress(0);
        resultLabel.setText("");
        setStatus("Preparing…", false);

        activeTask = buildCompressionTask(queuedFiles, format, targetBytes, outputDir, prefix, suffix);

        progressBar.progressProperty().bind(activeTask.progressProperty());

        activeTask.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            progressBar.setProgress(1.0);
            setStatus("✅  All files processed successfully!", false);
            resultLabel.setText((String) activeTask.getValue());
            processButton.setDisable(false);
            clearButton.setDisable(false);
        });

        activeTask.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            progressBar.setProgress(0);
            Throwable ex = activeTask.getException();
            setStatus("❌  Error: " + (ex != null ? ex.getMessage() : "Unknown"), true);
            processButton.setDisable(false);
            clearButton.setDisable(false);
        });

        Thread worker = new Thread(activeTask, "SqueezeIt-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    /* ═══════════════════════════════════════════════════════════════════
       BACKGROUND TASK
       ═══════════════════════════════════════════════════════════════════ */

    /**
     * Builds a {@link Task} that compresses/converts all queued files on a
     * background thread and returns a human-readable result summary.
     *
     * @param files       snapshot of queued files
     * @param format      output format (JPEG / PNG / WEBP / PDF)
     * @param targetBytes target size in bytes per output file
     * @param outputDir   directory where output files are saved
     * @param prefix      filename prefix (may be empty)
     * @param suffix      filename suffix before extension (e.g. "_squeezed")
     */
    private Task<String> buildCompressionTask(
            List<File> files, String format, long targetBytes,
            File outputDir, String prefix, String suffix) {

        List<File> snapshot      = new ArrayList<>(files);
        String     suffixFinal   = suffix;
        String     prefixFinal   = prefix;

        return new Task<>() {
            @Override
            protected String call() throws Exception {
                updateMessage("Starting…");
                StringBuilder results = new StringBuilder();
                int total = snapshot.size();

                // ── Decide operation mode ─────────────────────────────
                boolean makePdf = "PDF".equalsIgnoreCase(format);

                // Separate images from PDFs in the queue
                List<File> images = snapshot.stream()
                        .filter(f -> CompressionEngine.isSupportedImage(
                                CompressionEngine.getExtension(f)))
                        .collect(Collectors.toList());

                List<File> pdfs = snapshot.stream()
                        .filter(f -> CompressionEngine.isPdf(
                                CompressionEngine.getExtension(f)))
                        .collect(Collectors.toList());

                // ── Case 1: Merge images → single PDF ────────────────
                if (makePdf && !images.isEmpty()) {
                    updateMessage("Building PDF from " + images.size() + " image(s)…");
                    String baseName = prefixFinal + stripExtension(images.get(0).getName()) + suffixFinal;
                    File out = resolveOutput(outputDir, baseName, "pdf");

                    CompressionEngine.convertImagesToPdf(
                            images, out, targetBytes,
                            p -> updateProgress(p * 0.9, 1.0));

                    long inTotal = images.stream().mapToLong(File::length).sum();
                    long outSize = out.length();
                    results.append(String.format(
                            "📄 %s  (%s → %s)%n",
                            out.getName(),
                            CompressionEngine.humanSize(inTotal),
                            CompressionEngine.humanSize(outSize)));

                    // Log each source image as contributing to this PDF
                    for (File img : images) {
                        HistoryLog.append(img, out, format, targetBytes);
                    }
                }

                // ── Case 2: Compress each file individually ───────────
                int processed = 0;
                List<File> toProcess = makePdf ? pdfs : snapshot;

                for (File file : toProcess) {
                    String ext    = CompressionEngine.getExtension(file);
                    String outExt = makePdf ? "pdf" : format.toLowerCase().replace("jpeg", "jpg");
                    String baseName = prefixFinal + stripExtension(file.getName()) + suffixFinal;
                    File out = resolveOutput(outputDir, baseName, outExt);

                    updateMessage("Processing: " + file.getName());

                    long inSize = file.length();

                    if (CompressionEngine.isPdf(ext)) {
                        // ── PDF → PDF re-compression ──────────────────
                        final int snap = processed;
                        CompressionEngine.recompressPdf(
                                file, out, targetBytes,
                                p -> {
                                    double base = (double) snap / total;
                                    double step = 1.0 / total;
                                    updateProgress(base + p * step, 1.0);
                                });

                        long outSize = out.length();
                        double ratio = inSize > 0 ? (1.0 - (double) outSize / inSize) * 100 : 0;
                        results.append(String.format(
                                "📑  %s  (%s → %s, %.0f%% smaller)%n",
                                out.getName(),
                                CompressionEngine.humanSize(inSize),
                                CompressionEngine.humanSize(outSize),
                                ratio));
                        HistoryLog.append(file, out, "PDF", targetBytes);

                    } else {
                        // ── Image compression ─────────────────────────
                        String fmt = makePdf ? "JPEG" : format;
                        final int snap = processed;
                        CompressionEngine.compressImageToTargetSize(
                                file, out, targetBytes, fmt,
                                p -> {
                                    double base = (double) snap / total;
                                    double step = 1.0 / total;
                                    updateProgress(base + p * step, 1.0);
                                });

                        long outSize = out.length();
                        double ratio = inSize > 0 ? (1.0 - (double) outSize / inSize) * 100 : 0;
                        results.append(String.format(
                                "🗜  %s  (%s → %s, %.0f%% smaller)%n",
                                out.getName(),
                                CompressionEngine.humanSize(inSize),
                                CompressionEngine.humanSize(outSize),
                                ratio));
                        HistoryLog.append(file, out, format, targetBytes);
                    }

                    processed++;
                    updateProgress(processed, total);
                }

                return results.toString().trim();
            }
        };
    }

    /* ═══════════════════════════════════════════════════════════════════
       HELPERS
       ═══════════════════════════════════════════════════════════════════ */

    private void addFiles(List<File> newFiles) {
        for (File f : newFiles) {
            if (!queuedFiles.contains(f)) {
                queuedFiles.add(f);
                fileListView.getItems().add(
                        f.getName() + "  [" + CompressionEngine.humanSize(f.length()) + "]");
            }
        }
        int count = queuedFiles.size();
        dropZoneLabel.setText(count + " file" + (count == 1 ? "" : "s") + " queued");
        setStatus("Ready to compress.", false);
        updatePreviewCard();
    }

    private void setStatus(String message, boolean isError) {
        Platform.runLater(() -> {
            statusLabel.setText(message);
            statusLabel.setStyle(isError ? "-fx-text-fill: #ff6b6b;" : "-fx-text-fill: #a0aec0;");
        });
    }

    /** Returns a non-colliding output file path in the given directory. */
    private static File resolveOutput(File dir, String baseName, String ext) {
        File candidate = new File(dir, baseName + "." + ext);
        int counter = 1;
        while (candidate.exists()) {
            candidate = new File(dir, baseName + "_" + counter++ + "." + ext);
        }
        return candidate;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
