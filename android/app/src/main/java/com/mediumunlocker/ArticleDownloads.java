package com.inulute.mediumunlocker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Saving downloaded articles (PDF / markdown) to shared storage. */
final class ArticleDownloads {

    /** Folder under Downloads that saved articles go into. */
    static final String FOLDER = "Freedium";

    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 120;
    private static final Pattern DISPOSITION_EXT =
            Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPOSITION =
            Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    /** A file fetched over the network, ready to be saved. */
    static final class Download {
        final byte[] bytes;
        final String fileName;
        final String mimeType;

        Download(byte[] bytes, String fileName, String mimeType) {
            this.bytes = bytes;
            this.fileName = fileName;
            this.mimeType = mimeType;
        }
    }

    private ArticleDownloads() { }

    /**
     * Writes the file to Downloads/Freedium via MediaStore. Android 10+ only, needs no
     * permission. MediaStore picks a unique name if one is already taken.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    static Uri saveToDownloads(Context context, byte[] bytes, String fileName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, storageMimeType(fileName));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Couldn't create " + fileName);
        try {
            writeToUri(context, uri, bytes);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    static void writeToUri(Context context, Uri uri, byte[] bytes) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Couldn't open " + uri);
            out.write(bytes);
        }
    }

    static void copyToUri(Context context, File source, Uri uri) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Couldn't open " + uri);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    /**
     * MIME type to store the file under. MediaStore appends an extension when the type
     * doesn't match the name ("article.md" saved as text/markdown can become
     * "article.md.txt" on devices that don't know .md), so use the type the platform
     * associates with the extension, or octet-stream, which keeps the name as is.
     */
    static String storageMimeType(String fileName) {
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(fileName));
        return type != null ? type : "application/octet-stream";
    }

    /** MIME type to open the saved file with. */
    static String viewMimeType(String fileName, String mimeType) {
        String ext = extension(fileName);
        if ("md".equals(ext) || "markdown".equals(ext)) return "text/markdown";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (type != null) return type;
        return mimeType != null && !mimeType.isEmpty() ? mimeType : "*/*";
    }

    /** Makes a page-supplied name safe to use as a file name and gives it an extension. */
    static String sanitizeFileName(String name, String mimeType) {
        String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (clean.startsWith(".")) clean = clean.substring(1);
        if (clean.isEmpty()) clean = "article";
        if (extension(clean).isEmpty()) {
            String ext = extensionForMime(mimeType);
            if (!ext.isEmpty()) clean = clean + "." + ext;
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            String ext = extension(clean);
            int keep = MAX_NAME_LENGTH - (ext.isEmpty() ? 0 : ext.length() + 1);
            clean = clean.substring(0, keep) + (ext.isEmpty() ? "" : "." + ext);
        }
        return clean;
    }

    /** File name from a Content-Disposition header, or null. */
    static String fileNameFromDisposition(String disposition) {
        if (disposition == null) return null;
        Matcher m = DISPOSITION_EXT.matcher(disposition);
        if (m.find()) {
            try {
                return URLDecoder.decode(m.group(1).trim().replaceAll("^\"|\"$", ""), "UTF-8");
            } catch (Exception ignored) { }
        }
        m = DISPOSITION.matcher(disposition);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Downloads a URL the page couldn't hand over itself, sending the WebView's cookies
     * and user agent so the mirror sees the same session. Blocking; call off the UI thread.
     */
    static Download fetch(String url, String userAgent, String cookies, String referer,
                          String contentDisposition, String mimeType) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(true);
            if (userAgent != null && !userAgent.isEmpty()) conn.setRequestProperty("User-Agent", userAgent);
            if (cookies != null && !cookies.isEmpty()) conn.setRequestProperty("Cookie", cookies);
            if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);

            String disposition = conn.getHeaderField("Content-Disposition");
            if (disposition == null) disposition = contentDisposition;
            String type = conn.getContentType();
            if (type == null || type.isEmpty()) type = mimeType;
            if (type != null) type = type.split(";")[0].trim();

            String name = fileNameFromDisposition(disposition);
            if (name == null || name.isEmpty()) name = URLUtil.guessFileName(url, disposition, type);

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_BYTES) throw new IOException("File too large");
                }
                return new Download(out.toByteArray(), sanitizeFileName(name, type), type);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String extensionForMime(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return "";
        String type = mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if ("text/markdown".equals(type) || "text/x-markdown".equals(type)) return "md";
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
        return ext != null ? ext : "";
    }
}
