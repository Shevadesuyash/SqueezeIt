package com.squeezeit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Core compression engine for SqueezeIt.
 *
 * <p>Two primary operations:
 * <ol>
 *   <li>{@link #compressImageToTargetSize} – Binary-search JPEG/WebP quality feedback loop.</li>
 *   <li>{@link #convertImagesToPdf} – Multi-image → compressed PDF via PDFBox 3.x API.</li>
 * </ol>
 *
 * All methods are stateless and thread-safe; they may be called from
 * background {@code javafx.concurrent.Task} threads without synchronisation.
 */
public final class CompressionEngine {

    /* ─── Constants ───────────────────────────────────────────────────── */

    /** Maximum binary-search iterations before accepting the best result. */
    private static final int MAX_ITERATIONS = 14;

    /** Lowest acceptable JPEG quality floor (avoids unreadable artefacts). */
    private static final float MIN_QUALITY = 0.05f;

    /** Standard A4 page size in PDFBox user-units (1/72 inch). */
    private static final PDRectangle A4 = PDRectangle.A4;

    /** A4 margins in points. */
    private static final float MARGIN = 28f;  // ~10 mm

    // Utility class – no instantiation
    private CompressionEngine() {}

    /* ═══════════════════════════════════════════════════════════════════
       1. IMAGE COMPRESSION
       ═══════════════════════════════════════════════════════════════════ */

    /**
     * Compresses a single image file toward a target byte budget using a
     * binary-search quality feedback loop.
     *
     * <p>Supports {@code JPEG}, {@code PNG}, and {@code WEBP} output formats.
     * For formats that do not natively support lossy quality parameters (e.g.
     * PNG), the image is first down-sampled in resolution to meet the target.
     *
     * @param inputFile       source image file (any format readable by ImageIO)
     * @param outputFile      destination file – will be overwritten if it exists
     * @param targetSizeBytes maximum allowed file size in bytes
     * @param format          output format string: {@code "JPEG"}, {@code "PNG"}, or {@code "WEBP"}
     * @param progressCallback optional callback receiving progress [0.0, 1.0]; may be {@code null}
     * @throws IOException if reading/writing fails
     */
    public static void compressImageToTargetSize(
            File inputFile,
            File outputFile,
            long targetSizeBytes,
            String format,
            Consumer<Double> progressCallback) throws IOException {

        // ── 1. Decode source ─────────────────────────────────────────────
        BufferedImage sourceImage = ImageIO.read(inputFile);
        if (sourceImage == null) {
            throw new IOException("Unsupported or corrupt image: " + inputFile.getName());
        }

        // Convert to RGB for JPEG/WebP (removes alpha channel issues)
        if ("JPEG".equalsIgnoreCase(format) || "WEBP".equalsIgnoreCase(format)) {
            sourceImage = toRGB(sourceImage);
        }

        notifyProgress(progressCallback, 0.05);

        // ── 2. Choose compression strategy ──────────────────────────────
        if ("PNG".equalsIgnoreCase(format)) {
            // PNG is lossless; use resolution down-sampling to hit target
            writePngWithResolutionReduction(sourceImage, outputFile, targetSizeBytes, progressCallback);
        } else {
            // JPEG / WebP – binary-search quality
            writeWithQualityBinarySearch(sourceImage, outputFile, targetSizeBytes, format, progressCallback);
        }
    }

    /**
     * Overload without a progress callback (convenience).
     */
    public static void compressImageToTargetSize(
            File inputFile, File outputFile, long targetSizeBytes, String format)
            throws IOException {
        compressImageToTargetSize(inputFile, outputFile, targetSizeBytes, format, null);
    }

    // ── Private helpers ─────────────────────────────────────────────────

    /**
     * Binary-search JPEG/WebP quality until the output is ≤ {@code targetBytes}.
     */
    private static void writeWithQualityBinarySearch(
            BufferedImage image,
            File outputFile,
            long targetBytes,
            String format,
            Consumer<Double> progressCallback) throws IOException {

        float lo = MIN_QUALITY;
        float hi = 1.0f;
        float bestQuality = MIN_QUALITY;
        byte[] bestBytes = null;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            float mid = (lo + hi) / 2f;
            byte[] candidate = encodeToBytes(image, format, mid);

            notifyProgress(progressCallback, 0.1 + 0.75 * ((double) i / MAX_ITERATIONS));

            if (candidate.length <= targetBytes) {
                // This quality fits → try higher
                bestQuality = mid;
                bestBytes = candidate;
                lo = mid;
            } else {
                // Too big → try lower
                hi = mid;
            }

            // Converge when the search window is negligible
            if (hi - lo < 0.005f) break;
        }

        // Edge case: even MIN_QUALITY exceeds target → try resolution down-scaling
        if (bestBytes == null) {
            bestBytes = compressByResolutionReduction(image, format, targetBytes, progressCallback);
        }

        // Write the best candidate to disk
        try (var fos = new java.io.FileOutputStream(outputFile)) {
            fos.write(bestBytes);
        }
        notifyProgress(progressCallback, 1.0);
    }

    /**
     * Encode a {@link BufferedImage} to an in-memory byte array at the given quality.
     */
    private static byte[] encodeToBytes(BufferedImage image, String format, float quality)
            throws IOException {

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new IOException("No ImageWriter found for format: " + format);
        }
        ImageWriter writer = writers.next();

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (var ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);

                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    // Some writers require a compression type to be set first
                    if (param.getCompressionTypes() != null &&
                            param.getCompressionTypes().length > 0 &&
                            param.getCompressionType() == null) {
                        param.setCompressionType(param.getCompressionTypes()[0]);
                    }
                    param.setCompressionQuality(quality);
                }

                writer.write(null, new IIOImage(image, null, null), param);
                ios.flush();
            }
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * Progressive resolution down-sampling for formats that can't reach the
     * target via quality tuning alone (e.g. very small targets for JPEG).
     */
    private static byte[] compressByResolutionReduction(
            BufferedImage image, String format, long targetBytes,
            Consumer<Double> progressCallback) throws IOException {

        double scaleFactor = 0.9;
        int width  = image.getWidth();
        int height = image.getHeight();

        byte[] result = encodeToBytes(image, format, MIN_QUALITY);

        for (int attempt = 0; attempt < 20 && result.length > targetBytes; attempt++) {
            width  = Math.max(1, (int)(width  * scaleFactor));
            height = Math.max(1, (int)(height * scaleFactor));
            BufferedImage scaled = scaleImage(image, width, height);
            result = encodeToBytes(scaled, format, MIN_QUALITY);
            notifyProgress(progressCallback, 0.85 + 0.1 * ((double) attempt / 20));
        }
        return result;
    }

    /**
     * PNG lossless: iteratively reduce resolution until within target.
     */
    private static void writePngWithResolutionReduction(
            BufferedImage image, File outputFile, long targetBytes,
            Consumer<Double> progressCallback) throws IOException {

        int width  = image.getWidth();
        int height = image.getHeight();

        // Try original size first
        byte[] candidate = encodeToBytes(image, "PNG", 1.0f);

        int step = 0;
        while (candidate.length > targetBytes && (width > 10 || height > 10)) {
            width  = Math.max(1, (int)(width  * 0.85));
            height = Math.max(1, (int)(height * 0.85));
            BufferedImage scaled = scaleImage(image, width, height);
            candidate = encodeToBytes(scaled, "PNG", 1.0f);
            notifyProgress(progressCallback, 0.1 + 0.80 * Math.min(1.0, ++step / 20.0));
        }

        try (var fos = new java.io.FileOutputStream(outputFile)) {
            fos.write(candidate);
        }
        notifyProgress(progressCallback, 1.0);
    }

    /* ═══════════════════════════════════════════════════════════════════
       2. IMAGES → PDF CONVERSION
       ═══════════════════════════════════════════════════════════════════ */

    /**
     * Converts a list of image files into a single PDF document.
     *
     * <p>Each image occupies its own A4 page, scaled to fit within the printable
     * area while preserving aspect ratio. If {@code targetSizeBytes > 0}, the
     * JPEG quality of embedded images is reduced via a binary-search feedback
     * loop until the PDF byte budget is satisfied.
     *
     * @param inputImages     ordered list of source image files
     * @param outputPdfFile   destination PDF file
     * @param targetSizeBytes desired maximum PDF size in bytes (0 = no limit)
     * @param progressCallback optional progress callback; may be {@code null}
     * @throws IOException if any image cannot be read or the PDF cannot be written
     */
    public static void convertImagesToPdf(
            List<File> inputImages,
            File outputPdfFile,
            long targetSizeBytes,
            Consumer<Double> progressCallback) throws IOException {

        if (inputImages == null || inputImages.isEmpty()) {
            throw new IllegalArgumentException("No input images provided.");
        }

        notifyProgress(progressCallback, 0.02);

        // Decode all images up-front and report progress
        List<BufferedImage> images = new ArrayList<>(inputImages.size());
        for (int i = 0; i < inputImages.size(); i++) {
            BufferedImage img = ImageIO.read(inputImages.get(i));
            if (img == null) {
                throw new IOException("Cannot read image: " + inputImages.get(i).getName());
            }
            images.add(toRGB(img));
            notifyProgress(progressCallback, 0.02 + 0.25 * ((double)(i + 1) / inputImages.size()));
        }

        // ── If no size constraint, write at high quality directly ────────
        if (targetSizeBytes <= 0) {
            writePdf(images, outputPdfFile, 0.85f, progressCallback, 0.27, 0.95);
            return;
        }

        // ── Binary-search JPEG quality for target PDF size ───────────────
        float lo = MIN_QUALITY;
        float hi = 0.92f;
        float bestQuality = MIN_QUALITY;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            float mid = (lo + hi) / 2f;
            // Write to a temp file to measure size
            File temp = File.createTempFile("squeezit_probe_", ".pdf");
            temp.deleteOnExit();
            try {
                writePdf(images, temp, mid, null, 0, 0);
                long size = temp.length();
                if (size <= targetSizeBytes) {
                    bestQuality = mid;
                    lo = mid;
                } else {
                    hi = mid;
                }
            } finally {
                temp.delete();
            }
            notifyProgress(progressCallback, 0.27 + 0.55 * ((double)(iter + 1) / MAX_ITERATIONS));
            if (hi - lo < 0.005f) break;
        }

        // Final write with best quality found
        writePdf(images, outputPdfFile, bestQuality, progressCallback, 0.82, 0.98);
        notifyProgress(progressCallback, 1.0);
    }

    /**
     * Overload without progress callback.
     */
    public static void convertImagesToPdf(
            List<File> inputImages, File outputPdfFile, long targetSizeBytes)
            throws IOException {
        convertImagesToPdf(inputImages, outputPdfFile, targetSizeBytes, null);
    }

    /**
     * Internal PDF writer using PDFBox 3.x API.
     */
    private static void writePdf(
            List<BufferedImage> images,
            File outputFile,
            float jpegQuality,
            Consumer<Double> progressCallback,
            double progressStart,
            double progressEnd) throws IOException {

        try (PDDocument doc = new PDDocument()) {
            float printableW = A4.getWidth()  - 2 * MARGIN;
            float printableH = A4.getHeight() - 2 * MARGIN;

            for (int i = 0; i < images.size(); i++) {
                BufferedImage img = images.get(i);
                PDPage page = new PDPage(A4);
                doc.addPage(page);

                // Embed the image as JPEG at the chosen quality
                PDImageXObject pdImage = JPEGFactory.createFromImage(doc, img, jpegQuality);

                // Compute scale to fit within printable area (maintain aspect)
                float scaleW = printableW / pdImage.getWidth();
                float scaleH = printableH / pdImage.getHeight();
                float scale  = Math.min(scaleW, scaleH);

                float drawW = pdImage.getWidth()  * scale;
                float drawH = pdImage.getHeight() * scale;

                // Centre the image on the page
                float x = MARGIN + (printableW - drawW) / 2f;
                float y = MARGIN + (printableH - drawH) / 2f;

                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.drawImage(pdImage, x, y, drawW, drawH);
                }

                if (progressCallback != null && images.size() > 1) {
                    double t = (double)(i + 1) / images.size();
                    notifyProgress(progressCallback, progressStart + t * (progressEnd - progressStart));
                }
            }

            doc.save(outputFile);
        }
    }

    /* ═══════════════════════════════════════════════════════════════════
       UTILITIES
       ═══════════════════════════════════════════════════════════════════ */

    /** Convert any image to RGB (removes alpha, required for JPEG output). */
    private static BufferedImage toRGB(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    /** High-quality Lanczos-style down-scaling using Java2D. */
    private static BufferedImage scaleImage(BufferedImage src, int targetW, int targetH) {
        BufferedImage result = new BufferedImage(targetW, targetH, src.getType());
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                           RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                           RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return result;
    }

    /** Safely fires a progress event, ignoring null callbacks. */
    private static void notifyProgress(Consumer<Double> callback, double value) {
        if (callback != null) callback.accept(Math.min(1.0, Math.max(0.0, value)));
    }

    /* ── Format helpers ─────────────────────────────────────────────── */

    /**
     * Returns {@code true} if the given file extension is a supported input image.
     */
    public static boolean isSupportedImage(String ext) {
        return switch (ext.toUpperCase()) {
            case "JPG", "JPEG", "PNG", "WEBP", "BMP", "GIF", "TIFF", "TIF" -> true;
            default -> false;
        };
    }

    /** Returns {@code true} if the file is a PDF. */
    public static boolean isPdf(String ext) {
        return "PDF".equalsIgnoreCase(ext);
    }

    /** Returns the file extension (lower-case, without dot), or {@code ""} on failure. */
    public static String getExtension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * Human-readable byte size string (e.g. "1.4 MB", "320 KB").
     */
    public static String humanSize(long bytes) {
        if (bytes >= 1_048_576) return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024)     return String.format("%.1f KB", bytes / 1_024.0);
        return bytes + " B";
    }
}
