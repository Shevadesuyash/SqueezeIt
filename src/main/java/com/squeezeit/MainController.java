package com.squeezeit;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JavaFX controller for {@code main-view.fxml} — v1.2.
 *
 * <p>New in v1.2:
 * <ul>
 *   <li>Segmented ToggleGroup: ⚡ Compress | 🔄 Convert | 📄 Extract Pages</li>
 *   <li>Context-aware property panels per mode</li>
 *   <li>Auto-clear queue after successful processing (Bug #1 fix)</li>
 *   <li>Per-file remove (Bug #2 fix)</li>
 *   <li>Locale-safe size strings (Bug #4 fix — comma decimal separator)</li>
 *   <li>Results panel with clickable 📂 Open per output file (Bug #4 fix)</li>
 *   <li>Output path settings: SqueezeIt folder / Always Ask / Fixed Path</li>
 *   <li>PDF → Images extraction via {@link CompressionEngine#convertPdfToImages}</li>
 *   <li>Image format conversion at full quality via {@link CompressionEngine#convertImage}</li>
 * </ul>
 */
public class MainController implements Initializable {

    /* ── FXML — Left panel ──────────────────────────────────────────── */
    @FXML private VBox              dropZone;
    @FXML private Label             dropZoneLabel;
    @FXML private ListView<String>  fileListView;
    @FXML private Button            removeSelectedBtn;
    @FXML private VBox              previewCard;
    @FXML private Label             previewBefore;
    @FXML private Label             previewAfter;
    @FXML private Label             previewReduction;
    @FXML private Label             resultsHeader;
    @FXML private ScrollPane        resultsScroll;
    @FXML private VBox              resultsPane;
    @FXML private Button            clearButton;

    /* ── FXML — Mode toggles ────────────────────────────────────────── */
    @FXML private ToggleButton      compressToggle;
    @FXML private ToggleButton      convertToggle;
    @FXML private ToggleButton      extractToggle;

    /* ── FXML — Compress panel ──────────────────────────────────────── */
    @FXML private VBox              compressPanel;
    @FXML private ComboBox<String>  compressFormatCombo;
    @FXML private Slider            sizeSlider;
    @FXML private Label             sizeLabel;

    /* ── FXML — Convert panel ───────────────────────────────────────── */
    @FXML private VBox              convertPanel;
    @FXML private ComboBox<String>  convertFormatCombo;
    @FXML private ComboBox<String>  convertDpiCombo;
    @FXML private ComboBox<String>  convertScaleCombo;

    /* ── FXML — Extract panel ───────────────────────────────────────── */
    @FXML private VBox              extractPanel;
    @FXML private ComboBox<String>  extractFormatCombo;
    @FXML private ComboBox<String>  extractDpiCombo;
    @FXML private Label             extractPreviewLabel;

    /* ── FXML — Shared Output naming ────────────────────────────────── */
    @FXML private VBox              outputNamingBox;
    @FXML private TextField         prefixField;
    @FXML private TextField         suffixField;

    /* ── FXML — Output folder card ──────────────────────────────────── */
    @FXML private ComboBox<String>  outputModeCombo;
    @FXML private HBox              fixedPathBox;
    @FXML private TextField         fixedPathField;

    /* ── FXML — Bottom ──────────────────────────────────────────────── */
    @FXML private Button            processButton;
    @FXML private ProgressBar       progressBar;
    @FXML private Label             statusLabel;

    /* ── State ──────────────────────────────────────────────────────── */

    private enum OperationMode { COMPRESS, CONVERT, EXTRACT }

    private final List<File> queuedFiles = new ArrayList<>();
    private Task<?>           activeTask;

    /* ═══════════════════════════════════════════════════════════════════
       INITIALISATION
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public void initialize(URL location, ResourceBundle resources) {

        // ── Mode ToggleGroup ─────────────────────────────────────────
        ToggleGroup modeGroup = new ToggleGroup();
        compressToggle.setToggleGroup(modeGroup);
        convertToggle.setToggleGroup(modeGroup);
        extractToggle.setToggleGroup(modeGroup);

        // Prevent deselection (always keep one active)
        modeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) oldT.setSelected(true);
            else applyModeSwitch();
        });

        // ── Compress format combo ────────────────────────────────────
        compressFormatCombo.getItems().setAll("JPEG", "PNG", "WEBP", "PDF");
        compressFormatCombo.setValue("JPEG");
        compressFormatCombo.valueProperty().addListener((obs, o, n) -> updatePreviewCard());

        // ── Convert combos ───────────────────────────────────────────
        convertFormatCombo.getItems().setAll("JPEG", "PNG", "WEBP");
        convertFormatCombo.setValue("JPEG");

        convertDpiCombo.getItems().setAll("72 dpi", "96 dpi", "150 dpi", "300 dpi");
        convertDpiCombo.setValue("96 dpi");

        convertScaleCombo.getItems().setAll("100%", "75%", "50%", "25%");
        convertScaleCombo.setValue("100%");

        // ── Extract combos ───────────────────────────────────────────
        extractFormatCombo.getItems().setAll("JPEG", "PNG");
        extractFormatCombo.setValue("JPEG");
        extractFormatCombo.valueProperty().addListener((obs, o, n) -> updateExtractPreview());

        extractDpiCombo.getItems().setAll("72 dpi", "96 dpi", "150 dpi", "300 dpi");
        extractDpiCombo.setValue("150 dpi");
        extractDpiCombo.valueProperty().addListener((obs, o, n) -> updateExtractPreview());

        // ── Size slider ──────────────────────────────────────────────
        sizeSlider.setMin(50_000);
        sizeSlider.setMax(10_000_000);
        sizeSlider.setValue(1_000_000);
        updateSizeLabel(1_000_000);
        sizeSlider.valueProperty().addListener((obs, o, n) -> {
            updateSizeLabel(n.longValue());
            updatePreviewCard();
        });

        // ── Output path settings ─────────────────────────────────────
        outputModeCombo.getItems().setAll(
                AppSettings.modeLabel(AppSettings.OutputMode.SQUEEZIT_FOLDER),
                AppSettings.modeLabel(AppSettings.OutputMode.ALWAYS_ASK),
                AppSettings.modeLabel(AppSettings.OutputMode.FIXED_PATH));

        AppSettings settings = AppSettings.get();
        outputModeCombo.setValue(AppSettings.modeLabel(settings.getOutputMode()));

        boolean isFixed = settings.getOutputMode() == AppSettings.OutputMode.FIXED_PATH;
        show(fixedPathBox, isFixed);
        if (!settings.getFixedPath().isEmpty()) fixedPathField.setText(settings.getFixedPath());

        outputModeCombo.valueProperty().addListener((obs, o, n) -> {
            AppSettings.OutputMode mode = AppSettings.fromLabel(n);
            AppSettings.get().setOutputMode(mode);
            show(fixedPathBox, mode == AppSettings.OutputMode.FIXED_PATH);
        });
        fixedPathField.textProperty().addListener((obs, o, n) -> AppSettings.get().setFixedPath(n));

        // ── Per-file remove button ───────────────────────────────────
        removeSelectedBtn.disableProperty().bind(
                fileListView.getSelectionModel().selectedIndexProperty().lessThan(0));

        // ── Drag styling ─────────────────────────────────────────────
        dropZone.setOnMouseEntered(e -> dropZone.getStyleClass().add("drop-zone-hover"));
        dropZone.setOnMouseExited(e  -> dropZone.getStyleClass().remove("drop-zone-hover"));
        dropZone.setOnMouseClicked(e -> onBrowseClicked());

        // ── Initial mode apply ───────────────────────────────────────
        applyModeSwitch();

        // ── Progress ─────────────────────────────────────────────────
        progressBar.setProgress(0);
    }

    /* ═══════════════════════════════════════════════════════════════════
       MODE SWITCHING
       ═══════════════════════════════════════════════════════════════════ */

    private void applyModeSwitch() {
        boolean isCompress = compressToggle.isSelected();
        boolean isConvert  = convertToggle.isSelected();
        boolean isExtract  = extractToggle.isSelected();

        show(compressPanel,    isCompress);
        show(convertPanel,     isConvert);
        show(extractPanel,     isExtract);
        show(outputNamingBox,  !isExtract);   // rename fields not needed for extract
        show(previewCard,      isCompress && !queuedFiles.isEmpty());

        if (isCompress) processButton.setText("⚡  Compress Files");
        else if (isConvert) processButton.setText("🔄  Convert Files");
        else processButton.setText("📄  Extract Pages");

        if (isCompress) updatePreviewCard();
    }

    /* ═══════════════════════════════════════════════════════════════════
       DRAG-AND-DROP
       ═══════════════════════════════════════════════════════════════════ */

    @FXML
    private void onDragOver(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            boolean ok = db.getFiles().stream().anyMatch(f -> {
                String ext = CompressionEngine.getExtension(f);
                return CompressionEngine.isSupportedImage(ext) || CompressionEngine.isPdf(ext);
            });
            if (ok) {
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
                        return CompressionEngine.isSupportedImage(ext) || CompressionEngine.isPdf(ext);
                    }).collect(Collectors.toList());
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
        chooser.setTitle("Select Files");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images & PDFs",
                        "*.jpg", "*.jpeg", "*.png", "*.webp", "*.bmp", "*.gif", "*.tiff", "*.pdf"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        List<File> selected = chooser.showOpenMultipleDialog(dropZone.getScene().getWindow());
        if (selected != null) addFiles(selected);
    }

    /** Removes whichever row is currently selected in the file list. */
    @FXML
    private void onRemoveSelectedClicked() {
        int idx = fileListView.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;
        queuedFiles.remove(idx);
        fileListView.getItems().remove(idx);
        updateQueueState();
        updatePreviewCard();
    }

    @FXML
    private void onClearClicked() {
        queuedFiles.clear();
        fileListView.getItems().clear();
        progressBar.setProgress(0);
        setStatus("Queue cleared.", false);
        show(previewCard, false);
        show(resultsHeader, false);
        show(resultsScroll, false);
        resultsPane.getChildren().clear();
        processButton.setDisable(false);
        clearButton.setDisable(false);
        dropZoneLabel.setText("Drop files here  or  click to browse");
        updateQueueState();
    }

    @FXML
    private void onProcessClicked() {
        if (queuedFiles.isEmpty()) {
            setStatus("⚠  No files queued.", true);
            return;
        }

        OperationMode mode = currentMode();

        // ── Resolve output directory ──────────────────────────────────
        AppSettings settings = AppSettings.get();
        File outputDir = settings.resolveOutputDir(queuedFiles.get(0));

        if (outputDir == null) {
            // ALWAYS_ASK — show DirectoryChooser on FX thread before spawning task
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose Output Folder");
            outputDir = dc.showDialog(dropZone.getScene().getWindow());
            if (outputDir == null) {
                setStatus("⚠  No output folder selected.", true);
                return;
            }
        }

        // ── Build task parameters ────────────────────────────────────
        String format = switch (mode) {
            case COMPRESS -> compressFormatCombo.getValue();
            case CONVERT  -> convertFormatCombo.getValue();
            case EXTRACT  -> extractFormatCombo.getValue();
        };
        long   targetBytes = (long) sizeSlider.getValue();
        double scaleFactor = parseScale(convertScaleCombo.getValue());
        int    dpi         = parseDpi(
                mode == OperationMode.EXTRACT ? extractDpiCombo.getValue() : convertDpiCombo.getValue());
        String prefix = prefixField != null ? prefixField.getText().trim() : "";
        String suffix = suffixField != null && !suffixField.getText().trim().isEmpty()
                ? suffixField.getText().trim() : "_squeezed";

        final File finalOutputDir = outputDir;

        // ── Prepare UI ───────────────────────────────────────────────
        processButton.setDisable(true);
        clearButton.setDisable(true);
        progressBar.setProgress(0);
        show(resultsHeader, false);
        show(resultsScroll, false);
        resultsPane.getChildren().clear();
        setStatus("Starting…", false);

        // ── Launch task ──────────────────────────────────────────────
        List<File> snapshot = new ArrayList<>(queuedFiles);
        activeTask = buildTask(snapshot, mode, format, targetBytes, scaleFactor, dpi,
                finalOutputDir, prefix, suffix);

        progressBar.progressProperty().bind(activeTask.progressProperty());

        activeTask.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            progressBar.setProgress(1.0);
            setStatus("✅  Done! " + queuedFiles.size() + " file(s) processed.", false);
            processButton.setDisable(false);
            clearButton.setDisable(false);
            // Bug #1 fix: clear queue after success
            queuedFiles.clear();
            fileListView.getItems().clear();
            show(previewCard, false);
            dropZoneLabel.setText("Drop files here  or  click to browse");
            updateQueueState();
            // Show results
            if (!resultsPane.getChildren().isEmpty()) {
                show(resultsHeader, true);
                show(resultsScroll, true);
            }
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

    /** Opens a DirectoryChooser for the fixed-path output folder setting. */
    @FXML
    private void onBrowseOutputPath() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose Output Folder");
        if (!fixedPathField.getText().isBlank()) {
            File current = new File(fixedPathField.getText());
            if (current.exists()) dc.setInitialDirectory(current);
        }
        File chosen = dc.showDialog(dropZone.getScene().getWindow());
        if (chosen != null) fixedPathField.setText(chosen.getAbsolutePath());
    }

    /* ═══════════════════════════════════════════════════════════════════
       BACKGROUND TASK
       ═══════════════════════════════════════════════════════════════════ */

    private Task<String> buildTask(
            List<File> files, OperationMode mode,
            String format, long targetBytes, double scaleFactor, int dpi,
            File outputDir, String prefix, String suffix) {

        return new Task<>() {
            @Override
            protected String call() throws Exception {
                updateMessage("Starting…");
                int total = files.size();
                int done  = 0;

                switch (mode) {
                    /* ─── COMPRESS ─────────────────────────────────────── */
                    case COMPRESS -> {
                        boolean makePdf = "PDF".equalsIgnoreCase(format);

                        List<File> images = files.stream()
                                .filter(f -> CompressionEngine.isSupportedImage(
                                        CompressionEngine.getExtension(f)))
                                .collect(Collectors.toList());
                        List<File> pdfs = files.stream()
                                .filter(f -> CompressionEngine.isPdf(
                                        CompressionEngine.getExtension(f)))
                                .collect(Collectors.toList());

                        // Merge images → single PDF
                        if (makePdf && !images.isEmpty()) {
                            updateMessage("Building PDF from " + images.size() + " image(s)…");
                            String baseName = prefix + stripExt(images.get(0).getName()) + suffix;
                            File out = resolveOutput(outputDir, baseName, "pdf");

                            CompressionEngine.convertImagesToPdf(images, out, targetBytes,
                                    p -> updateProgress(p * 0.9, 1.0));

                            long inTotal = images.stream().mapToLong(File::length).sum();
                            addResultRow("📄", images.get(0).getName(), out.getName(),
                                    inTotal, out.length(), out);
                            for (File img : images) HistoryLog.append(img, out, format, targetBytes);
                        }

                        // Compress each file independently
                        List<File> toProcess = makePdf ? pdfs : files;
                        for (File file : toProcess) {
                            String ext = CompressionEngine.getExtension(file);
                            String outExt = makePdf ? "pdf" : format.toLowerCase().replace("jpeg", "jpg");
                            String baseName = prefix + stripExt(file.getName()) + suffix;
                            File out = resolveOutput(outputDir, baseName, outExt);
                            long inSize = file.length();
                            final int snap = done;

                            updateMessage("Compressing: " + file.getName());

                            if (CompressionEngine.isPdf(ext)) {
                                CompressionEngine.recompressPdf(file, out, targetBytes,
                                        p -> updateProgress((double)snap/total + p/total, 1.0));
                                addResultRow("📑", file.getName(), out.getName(),
                                        inSize, out.length(), out);
                                HistoryLog.append(file, out, "PDF", targetBytes);
                            } else {
                                String fmt = makePdf ? "JPEG" : format;
                                CompressionEngine.compressImageToTargetSize(file, out, targetBytes, fmt,
                                        p -> updateProgress((double)snap/total + p/total, 1.0));
                                addResultRow("🗜", file.getName(), out.getName(),
                                        inSize, out.length(), out);
                                HistoryLog.append(file, out, format, targetBytes);
                            }
                            done++;
                            updateProgress(done, total);
                        }
                    }

                    /* ─── CONVERT ──────────────────────────────────────── */
                    case CONVERT -> {
                        for (File file : files) {
                            if (!CompressionEngine.isSupportedImage(CompressionEngine.getExtension(file))) {
                                done++; continue;
                            }
                            String outExt  = format.toLowerCase().replace("jpeg", "jpg");
                            String baseName = prefix + stripExt(file.getName()) + suffix;
                            File out = resolveOutput(outputDir, baseName, outExt);
                            long inSize = file.length();
                            final int snap = done;

                            updateMessage("Converting: " + file.getName());
                            CompressionEngine.convertImage(file, out, format, scaleFactor,
                                    p -> updateProgress((double)snap/total + p/total, 1.0));

                            addResultRow("🔄", file.getName(), out.getName(),
                                    inSize, out.length(), out);
                            HistoryLog.append(file, out, format, 0);
                            done++;
                            updateProgress(done, total);
                        }
                    }

                    /* ─── EXTRACT ──────────────────────────────────────── */
                    case EXTRACT -> {
                        for (File file : files) {
                            if (!CompressionEngine.isPdf(CompressionEngine.getExtension(file))) {
                                done++; continue;
                            }
                            updateMessage("Extracting: " + file.getName());
                            final int snap = done;

                            List<File> pages = CompressionEngine.convertPdfToImages(
                                    file, outputDir, format, dpi,
                                    p -> updateProgress((double)snap/total + p/total, 1.0));

                            for (File page : pages) {
                                addResultRow("📄", file.getName(), page.getName(),
                                        -1, page.length(), page);
                            }
                            done++;
                            updateProgress(done, total);
                        }
                    }
                }

                return "Done";
            }
        };
    }

    /* ═══════════════════════════════════════════════════════════════════
       RESULTS PANEL
       ═══════════════════════════════════════════════════════════════════ */

    /**
     * Adds one result row to {@code resultsPane} — called from the background thread.
     * Dispatches to FX thread via {@link Platform#runLater}.
     *
     * @param icon       emoji icon
     * @param inputName  source filename
     * @param outputName output filename
     * @param inSize     input size (-1 to skip size display)
     * @param outSize    output size
     * @param outputFile the actual output file (used by Open button)
     */
    private void addResultRow(String icon, String inputName, String outputName,
                               long inSize, long outSize, File outputFile) {
        Platform.runLater(() -> {
            // Row container
            HBox row = new HBox(8);
            row.getStyleClass().add("result-row");
            row.setAlignment(Pos.CENTER_LEFT);

            // Icon
            Label iconLbl = new Label(icon);
            iconLbl.getStyleClass().add("result-icon");

            // Text block
            VBox textBox = new VBox(2);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            Label nameLbl = new Label(outputName);
            nameLbl.getStyleClass().add("result-name");

            String sizeInfo;
            if (inSize > 0 && outSize > 0) {
                double pct = (1.0 - (double) outSize / inSize) * 100;
                sizeInfo = CompressionEngine.humanSize(inSize) + " → "
                         + CompressionEngine.humanSize(outSize)
                         + (pct > 0
                            ? String.format(Locale.US, " (%.0f%% smaller)", pct)
                            : "");
            } else {
                sizeInfo = outSize > 0 ? CompressionEngine.humanSize(outSize) : "";
            }
            Label sizeLbl = new Label(sizeInfo);
            sizeLbl.getStyleClass().add("result-size");

            textBox.getChildren().addAll(nameLbl, sizeLbl);

            // Open button
            Button openBtn = new Button("📂 Open");
            openBtn.getStyleClass().add("btn-open");
            openBtn.setOnAction(e -> openInExplorer(outputFile));

            row.getChildren().addAll(iconLbl, textBox, openBtn);
            resultsPane.getChildren().add(row);
        });
    }

    /**
     * Reveals the output file in the OS file explorer.
     * On Windows uses {@code explorer /select,"path"} to highlight the exact file.
     */
    private void openInExplorer(File file) {
        try {
            File target = file.exists() ? file : file.getParentFile();
            if (target == null || !target.exists()) {
                setStatus("⚠  Output path not found.", true);
                return;
            }
            // Windows: highlight the specific file in Explorer
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("explorer.exe", "/select,\"" + target.getAbsolutePath() + "\"")
                        .start();
            } else if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(target.isDirectory() ? target : target.getParentFile());
            }
        } catch (IOException ex) {
            setStatus("⚠  Cannot open folder: " + ex.getMessage(), true);
        }
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
        updateQueueState();
        updatePreviewCard();
        updateExtractPreview();
    }

    /** Updates drop-zone label, toggle availability, and status text. */
    private void updateQueueState() {
        int count = queuedFiles.size();
        dropZoneLabel.setText(count == 0
                ? "Drop files here  or  click to browse"
                : count + " file" + (count == 1 ? "" : "s") + " queued");

        boolean hasPdf   = queuedFiles.stream()
                .anyMatch(f -> CompressionEngine.isPdf(CompressionEngine.getExtension(f)));
        boolean hasImage = queuedFiles.stream()
                .anyMatch(f -> CompressionEngine.isSupportedImage(CompressionEngine.getExtension(f)));

        extractToggle.setDisable(!hasPdf);
        convertToggle.setDisable(!hasImage);

        // If current selection is now disabled, fall back to Compress
        if (extractToggle.isSelected() && !hasPdf) compressToggle.setSelected(true);
        if (convertToggle.isSelected() && !hasImage) compressToggle.setSelected(true);

        if (count > 0) setStatus("Ready — " + count + " file" + (count == 1 ? "" : "s") + " queued.", false);
    }

    /** Updates the size-estimate preview card (Compress mode only). */
    private void updatePreviewCard() {
        if (queuedFiles.isEmpty() || !compressToggle.isSelected()) {
            show(previewCard, false);
            return;
        }
        long totalBefore = queuedFiles.stream().mapToLong(File::length).sum();
        long target      = (long) sizeSlider.getValue();

        boolean makePdf = "PDF".equalsIgnoreCase(compressFormatCombo.getValue());
        long estimatedAfter = makePdf
                ? Math.min(target, totalBefore)
                : Math.min(target * queuedFiles.size(), totalBefore);

        double saving = totalBefore > 0
                ? (1.0 - (double) estimatedAfter / totalBefore) * 100.0 : 0.0;

        previewBefore.setText(CompressionEngine.humanSize(totalBefore));
        previewAfter.setText("~" + CompressionEngine.humanSize(estimatedAfter));
        previewReduction.setText(String.format(Locale.US, "%.0f%%", Math.max(0, saving)));
        show(previewCard, true);
    }

    /** Updates the extract naming preview label. */
    private void updateExtractPreview() {
        if (extractPreviewLabel == null) return;
        String fmt = extractFormatCombo != null ? extractFormatCombo.getValue() : "JPEG";
        String ext = "PNG".equalsIgnoreCase(fmt) ? "png" : "jpg";
        String sample = queuedFiles.isEmpty() ? "document" :
                stripExt(queuedFiles.stream()
                        .filter(f -> CompressionEngine.isPdf(CompressionEngine.getExtension(f)))
                        .findFirst()
                        .map(File::getName)
                        .orElse("document.pdf"));
        extractPreviewLabel.setText(sample + "_001." + ext);
    }

    private void updateSizeLabel(long bytes) {
        sizeLabel.setText(CompressionEngine.humanSize(bytes));
    }

    private void setStatus(String msg, boolean isError) {
        Platform.runLater(() -> {
            statusLabel.setText(msg);
            statusLabel.setStyle(isError ? "-fx-text-fill: #ff6b6b;" : "-fx-text-fill: #a0aec0;");
        });
    }

    /** Toggle visibility AND managed state together (so layout reflows correctly). */
    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private OperationMode currentMode() {
        if (convertToggle.isSelected()) return OperationMode.CONVERT;
        if (extractToggle.isSelected()) return OperationMode.EXTRACT;
        return OperationMode.COMPRESS;
    }

    /** Parses "150 dpi" → 150; returns 150 on parse failure. */
    private static int parseDpi(String value) {
        if (value == null) return 150;
        try { return Integer.parseInt(value.replace(" dpi", "").trim()); }
        catch (NumberFormatException e) { return 150; }
    }

    /** Parses "75%" → 0.75; returns 1.0 on parse failure. */
    private static double parseScale(String value) {
        if (value == null) return 1.0;
        try { return Integer.parseInt(value.replace("%", "").trim()) / 100.0; }
        catch (NumberFormatException e) { return 1.0; }
    }

    /** Returns a non-colliding output file path inside {@code dir}. */
    private static File resolveOutput(File dir, String baseName, String ext) {
        File candidate = new File(dir, baseName + "." + ext);
        int counter = 1;
        while (candidate.exists()) {
            candidate = new File(dir, baseName + "_" + counter++ + "." + ext);
        }
        return candidate;
    }

    /** Strips the file extension (everything after the last dot). */
    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
