package com.pdfeditor.process;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipResumeProcessor {

    public static final int MAX_ZIP_ENTRIES = 500;
    public static final long MAX_UNCOMPRESSED_ENTRY_SIZE = 50 * 1024 * 1024L; // 50MB
    public static final long MAX_TOTAL_UNCOMPRESSED_SIZE = 250 * 1024 * 1024L; // 250MB

    public record ZipEntryPdf(String name, byte[] data) {}

    public static boolean isZip(byte[] bytes, String filename) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        // ZIP magic bytes: PK\x03\x04 or PK\x05\x06 (empty) or PK\x07\x08
        boolean hasZipHeader = (bytes[0] == 0x50 && bytes[1] == 0x4B &&
                ((bytes[2] == 0x03 && bytes[3] == 0x04) ||
                 (bytes[2] == 0x05 && bytes[3] == 0x06) ||
                 (bytes[2] == 0x07 && bytes[3] == 0x08)));
        if (hasZipHeader) {
            return true;
        }
        return filename != null && filename.toLowerCase().endsWith(".zip");
    }

    public static List<ZipEntryPdf> extractPdfEntries(byte[] zipBytes) throws IOException {
        List<ZipEntryPdf> result = new ArrayList<>();
        long totalSize = 0;
        int entriesCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entriesCount++;
                if (entriesCount > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("ZIP archive contains too many entries (max " + MAX_ZIP_ENTRIES + ")");
                }

                String rawName = entry.getName();
                if (rawName == null || rawName.contains("..") || entry.isDirectory()) {
                    continue; // Skip directories and zip-slip path traversal attempts
                }

                // Strip directory path inside ZIP to get clean base name
                String baseName = new File(rawName).getName();
                if (baseName.startsWith(".") || baseName.startsWith("__MACOSX")) {
                    continue; // Skip macOS metadata or hidden files
                }

                // Only collect PDF files
                if (!baseName.toLowerCase().endsWith(".pdf")) {
                    continue;
                }

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int read;
                long entrySize = 0;
                while ((read = zis.read(buf)) != -1) {
                    entrySize += read;
                    totalSize += read;
                    if (entrySize > MAX_UNCOMPRESSED_ENTRY_SIZE) {
                        throw new IllegalArgumentException("File " + baseName + " in ZIP exceeds 50MB limit");
                    }
                    if (totalSize > MAX_TOTAL_UNCOMPRESSED_SIZE) {
                        throw new IllegalArgumentException("Total uncompressed size of ZIP exceeds 250MB limit");
                    }
                    bos.write(buf, 0, read);
                }
                zis.closeEntry();

                result.add(new ZipEntryPdf(baseName, bos.toByteArray()));
            }
        }
        return result;
    }

    public static byte[] createZip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                if (file.getValue() == null) continue;
                ZipEntry entry = new ZipEntry(file.getKey());
                zos.putNextEntry(entry);
                zos.write(file.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
