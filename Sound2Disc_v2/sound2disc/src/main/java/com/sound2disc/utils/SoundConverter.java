package com.sound2disc.utils;

import com.sound2disc.Sound2Disc;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.regex.*;

public class SoundConverter {

    private final Sound2Disc plugin;

    public SoundConverter(Sound2Disc plugin) {
        this.plugin = plugin;
    }

    public File downloadAndConvert(String rawUrl, String soundKey) throws Exception {
        File soundsDir = new File(plugin.getDataFolder(), "sounds");
        File outputOgg = new File(soundsDir, soundKey + ".ogg");

        if (outputOgg.exists()) {
            plugin.getLogger().info("Sound '" + soundKey + "' already exists, skipping download.");
            return outputOgg;
        }

        String directUrl = resolveDirectUrl(rawUrl);
        if (directUrl == null) throw new Exception("Could not resolve a direct download URL from: " + rawUrl);

        plugin.getLogger().info("Downloading audio from: " + directUrl);
        File tempFile = File.createTempFile("sound2disc_", "_download", soundsDir);
        tempFile.deleteOnExit();
        downloadFile(directUrl, tempFile);
        plugin.getLogger().info("Download complete (" + (tempFile.length() / 1024) + " KB). Converting...");

        convertToOgg(tempFile, outputOgg);
        tempFile.delete();

        if (!outputOgg.exists() || outputOgg.length() == 0)
            throw new Exception("Conversion produced no output file.");

        double duration = getAudioDuration(outputOgg);
        int maxDuration = plugin.getConfig().getInt("max-duration-seconds", 300);
        if (duration > maxDuration) {
            outputOgg.delete();
            throw new Exception(String.format(
                "Audio is %.1f seconds long. Maximum allowed is %d seconds.", duration, maxDuration));
        }

        plugin.getLogger().info("Conversion complete! Duration: " + String.format("%.1f", duration) + "s");
        return outputOgg;
    }

    // ── URL Resolution ─────────────────────────────────────────────────────────

    private String resolveDirectUrl(String raw) throws Exception {
        raw = raw.trim();
        if (raw.contains("dropbox.com") || raw.contains("dropboxusercontent.com")) return resolveDropbox(raw);
        if (raw.contains("mediafire.com")) return resolveMediaFire(raw);
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw;

        // Local file in sounds folder
        File localFile = new File(new File(plugin.getDataFolder(), "sounds"), raw);
        if (localFile.exists()) return localFile.toURI().toString();
        throw new Exception("Not a recognized URL and file not found locally: " + raw);
    }

    private String resolveDropbox(String url) {
        // Strip all query params and force dl=1
        String base = url.split("\\?")[0];
        base = base.replace("www.dropbox.com", "dl.dropboxusercontent.com");
        base = base.replace("dropbox.com", "dl.dropboxusercontent.com");
        return base + "?dl=1";
    }

    /**
     * Resolves a MediaFire share page URL to a direct download URL.
     * Tries multiple extraction patterns to handle MediaFire's HTML changes.
     */
    private String resolveMediaFire(String url) throws Exception {
        plugin.getLogger().info("Resolving MediaFire link: " + url);

        // Normalize: make sure it's the file page not a folder
        // e.g. https://www.mediafire.com/file/abc123/song.mp3/file
        //   or https://www.mediafire.com/file/abc123/song.mp3
        if (!url.contains("/file/")) {
            throw new Exception("Unsupported MediaFire URL format. Use a direct file share link " +
                "(e.g. mediafire.com/file/abc123/song.mp3/file)");
        }

        String html = fetchPage(url);

        // Pattern 1: Standard download button href
        // href="https://download1234.mediafire.com/..."
        String result = extractPattern(html,
            "href=\"(https://download\\d*\\.mediafire\\.com/[^\"]+)\"");
        if (result != null) { plugin.getLogger().info("MediaFire resolved (pattern 1)"); return result; }

        // Pattern 2: aria-label download button
        result = extractPattern(html,
            "aria-label=\"Download file\"[^>]*href=\"([^\"]+)\"");
        if (result != null) { plugin.getLogger().info("MediaFire resolved (pattern 2)"); return result; }

        // Pattern 3: data-scrambled or direct_url in script JSON
        result = extractPattern(html,
            "\"direct_download_url\"\\s*:\\s*\"([^\"]+)\"");
        if (result != null) {
            result = result.replace("\\/", "/");
            plugin.getLogger().info("MediaFire resolved (pattern 3)");
            return result;
        }

        // Pattern 4: window.location.href redirect in scripts
        result = extractPattern(html,
            "window\\.location\\.href\\s*=\\s*'(https://download[^']+)'");
        if (result != null) { plugin.getLogger().info("MediaFire resolved (pattern 4)"); return result; }

        // Pattern 5: Any download*.mediafire.com URL in the page source
        result = extractPattern(html,
            "(https://download[\\w.]*\\.mediafire\\.com/[^\"' ><]+)");
        if (result != null) { plugin.getLogger().info("MediaFire resolved (pattern 5)"); return result; }

        // If all patterns fail, log a snippet of HTML to help debug
        String snippet = html.length() > 500 ? html.substring(0, 500) : html;
        plugin.getLogger().warning("MediaFire HTML snippet:\n" + snippet);

        throw new Exception(
            "Could not extract download link from MediaFire page. " +
            "Make sure:\n" +
            "  1. The file is set to PUBLIC sharing\n" +
            "  2. The link is a direct file link (not a folder)\n" +
            "  3. The file still exists on MediaFire\n" +
            "Tip: Try copying the link from the Download button on the MediaFire page."
        );
    }

    private String extractPattern(String html, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            String result = m.group(1).trim();
            // Decode HTML entities
            result = result.replace("&amp;", "&").replace("&#038;", "&");
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    private String fetchPage(String urlStr) throws Exception {
        HttpURLConnection conn = openConnection(urlStr);
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
        conn.setRequestProperty("Accept-Encoding", "identity"); // no gzip so we can read plainly
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
        // Follow redirects manually for safety
        int redirects = 0;
        while ((status == 301 || status == 302 || status == 307 || status == 308) && redirects++ < 5) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            conn = openConnection(loc);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            status = conn.getResponseCode();
        }

        if (status != 200) throw new Exception("HTTP " + status + " fetching MediaFire page");

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        } finally { conn.disconnect(); }

        return sb.toString();
    }

    // ── File Download ──────────────────────────────────────────────────────────

    private void downloadFile(String urlStr, File dest) throws Exception {
        if (urlStr.startsWith("file:")) {
            Files.copy(new File(new URI(urlStr)).toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        HttpURLConnection conn = openConnection(urlStr);
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        int status = conn.getResponseCode();
        int redirects = 0;
        while ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && redirects++ < 10) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = openConnection(location);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            status = conn.getResponseCode();
        }
        if (status != 200) throw new Exception("HTTP " + status + " when downloading audio file");

        long total = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n; long downloaded = 0; int lastPct = -1;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    int pct = (int)(downloaded * 100 / total);
                    if (pct / 20 != lastPct / 20) { // log every 20%
                        lastPct = pct;
                        plugin.getLogger().info("Downloading audio: " + pct + "% (" + (downloaded/1024) + " KB)");
                    }
                }
            }
        } finally { conn.disconnect(); }
    }

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    // ── FFmpeg Conversion ──────────────────────────────────────────────────────

    private void convertToOgg(File input, File output) throws Exception {
        int quality = plugin.getConfig().getInt("ogg-quality", 6);
        ProcessBuilder pb = new ProcessBuilder(
            plugin.getFfmpegPath(),
            "-i", input.getAbsolutePath(),
            "-vn",                          // no video
            "-ac", "1",                     // mono
            "-ar", "44100",                 // 44.1 kHz
            "-c:a", "libvorbis",            // OGG Vorbis codec
            "-q:a", String.valueOf(quality),
            "-map_metadata", "-1",          // strip metadata
            "-y",                           // overwrite
            output.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder log = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) log.append(line).append("\n");
        }
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            plugin.getLogger().warning("FFmpeg output:\n" + log);
            throw new Exception("FFmpeg conversion failed (exit " + exitCode + "). Is the file a valid audio file?");
        }
    }

    private double getAudioDuration(File file) {
        try {
            ProcessBuilder pb = new ProcessBuilder(plugin.getFfmpegPath(), "-i", file.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            Matcher m = Pattern.compile("Duration: (\\d+):(\\d+):([\\d.]+)").matcher(output);
            if (m.find()) return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3));
        } catch (Exception ignored) {}
        return 0;
    }

    // ── Key Sanitizer ──────────────────────────────────────────────────────────

    public static String toSoundKey(String input) {
        String name = input;
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name.contains("?")) name = name.substring(0, name.indexOf('?'));
        if (name.contains(".")) name = name.substring(0, name.lastIndexOf('.'));
        name = name.toLowerCase().replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (name.length() > 40) name = name.substring(0, 40);
        if (name.isEmpty()) name = "disc_" + System.currentTimeMillis();
        return name;
    }
}
