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

    /**
     * Downloads a file from a Dropbox or MediaFire URL, converts it to OGG,
     * and saves it to the plugin's sounds directory.
     *
     * @param rawUrl   The URL or filename provided by the user
     * @param soundKey The sanitized key name (no extension)
     * @return Path to the resulting .ogg file, or null on failure
     */
    public File downloadAndConvert(String rawUrl, String soundKey) throws Exception {
        File soundsDir = new File(plugin.getDataFolder(), "sounds");
        File outputOgg = new File(soundsDir, soundKey + ".ogg");

        // If already exists as OGG, skip
        if (outputOgg.exists()) {
            plugin.getLogger().info("Sound '" + soundKey + "' already exists, skipping download.");
            return outputOgg;
        }

        // Resolve direct download URL
        String directUrl = resolveDirectUrl(rawUrl);
        if (directUrl == null) {
            throw new Exception("Could not resolve a direct download URL from: " + rawUrl);
        }

        plugin.getLogger().info("Downloading from: " + directUrl);

        // Download to temp file
        File tempFile = File.createTempFile("sound2disc_", "_download", soundsDir);
        tempFile.deleteOnExit();

        downloadFile(directUrl, tempFile);

        plugin.getLogger().info("Download complete (" + (tempFile.length() / 1024) + " KB). Converting...");

        // Convert to OGG mono using FFmpeg
        convertToOgg(tempFile, outputOgg);

        // Clean up temp
        tempFile.delete();

        if (!outputOgg.exists() || outputOgg.length() == 0) {
            throw new Exception("Conversion produced no output file.");
        }

        // Check duration
        double duration = getAudioDuration(outputOgg);
        int maxDuration = plugin.getConfig().getInt("max-duration-seconds", 300);
        if (duration > maxDuration) {
            outputOgg.delete();
            throw new Exception(String.format(
                "Audio is %.1f seconds long. Maximum allowed is %d seconds (%.1f minutes).",
                duration, maxDuration, maxDuration / 60.0
            ));
        }

        plugin.getLogger().info("Conversion complete! Duration: " + String.format("%.1f", duration) + "s");
        return outputOgg;
    }

    // ── URL Resolution ─────────────────────────────────────────────────────────

    private String resolveDirectUrl(String raw) throws Exception {
        raw = raw.trim();

        if (isDropbox(raw)) {
            return resolveDropbox(raw);
        } else if (isMediaFire(raw)) {
            return resolveMediaFire(raw);
        } else if (raw.startsWith("http://") || raw.startsWith("https://")) {
            // Generic direct URL — try as-is
            return raw;
        } else {
            // Assume it's a filename in the sounds directory
            File localFile = new File(new File(plugin.getDataFolder(), "sounds"), raw);
            if (localFile.exists()) {
                return localFile.toURI().toString();
            }
            throw new Exception("Not a recognized URL and file not found locally: " + raw);
        }
    }

    private boolean isDropbox(String url) {
        return url.contains("dropbox.com") || url.contains("dropboxusercontent.com");
    }

    private String resolveDropbox(String url) {
        // Convert sharing link to direct download
        // Remove dl=0 / dl=1 fragments, then add dl=1
        url = url.replaceAll("[?&]dl=\\d", "");
        // Remove rlkey and other params after removing dl
        // Keep it simple: strip all params and add dl=1
        String base = url.split("\\?")[0];
        // Also handle www.dropbox.com → dl.dropboxusercontent.com
        base = base.replace("www.dropbox.com", "dl.dropboxusercontent.com");
        base = base.replace("dropbox.com", "dl.dropboxusercontent.com");
        return base + "?dl=1";
    }

    private boolean isMediaFire(String url) {
        return url.contains("mediafire.com");
    }

    private String resolveMediaFire(String url) throws Exception {
        // MediaFire pages have the direct link embedded in HTML
        plugin.getLogger().info("Resolving MediaFire link...");
        HttpURLConnection conn = openConnection(url);
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        String html = readStream(conn.getInputStream());
        conn.disconnect();

        // Extract direct download URL from HTML
        // Pattern: href="https://download..../file.mp3?..."  inside Download button
        Pattern p = Pattern.compile("href=\"(https://download\\d+\\.mediafire\\.com/[^\"]+)\"");
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.group(1);
        }

        // Fallback pattern
        Pattern p2 = Pattern.compile("\"(https?://[^\"]*\\.mediafire\\.com/file/[^\"]+)\"");
        Matcher m2 = p2.matcher(html);
        if (m2.find()) {
            return m2.group(1);
        }

        throw new Exception("Could not extract direct download link from MediaFire page. " +
            "Make sure the file is publicly shared.");
    }

    // ── File Download ──────────────────────────────────────────────────────────

    private void downloadFile(String urlStr, File dest) throws Exception {
        // Handle local file URIs
        if (urlStr.startsWith("file:")) {
            Files.copy(new File(new URI(urlStr)).toPath(), dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        HttpURLConnection conn = openConnection(urlStr);
        // Follow redirects manually (handles Dropbox CDN redirects)
        int status = conn.getResponseCode();
        int redirects = 0;
        while ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
               && redirects < 10) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = openConnection(location);
            status = conn.getResponseCode();
            redirects++;
        }

        if (status != 200) {
            throw new Exception("HTTP " + status + " when downloading from: " + urlStr);
        }

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            plugin.getLogger().info("Downloaded " + (total / 1024) + " KB");
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    // ── FFmpeg Conversion ──────────────────────────────────────────────────────

    private void convertToOgg(File input, File output) throws Exception {
        int quality = plugin.getConfig().getInt("ogg-quality", 6);
        String ffmpeg = plugin.getFfmpegPath();

        // FFmpeg command:
        // -i <input>          - input file
        // -ac 1               - convert to MONO (required by Minecraft)
        // -ar 44100           - 44100 Hz sample rate
        // -c:a libvorbis      - Vorbis codec (.ogg)
        // -q:a <quality>      - quality 0-10
        // -map_metadata -1    - strip metadata
        // -y                  - overwrite
        ProcessBuilder pb = new ProcessBuilder(
            ffmpeg,
            "-i", input.getAbsolutePath(),
            "-vn",                     // no video
            "-ac", "1",               // mono
            "-ar", "44100",           // 44.1 kHz
            "-c:a", "libvorbis",      // OGG Vorbis
            "-q:a", String.valueOf(quality),
            "-map_metadata", "-1",    // strip tags
            "-y",
            output.getAbsolutePath()
        );

        pb.redirectErrorStream(true);
        Process proc = pb.start();

        // Read output for logging
        StringBuilder log = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.append(line).append("\n");
            }
        }

        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            plugin.getLogger().warning("FFmpeg output:\n" + log);
            throw new Exception("FFmpeg conversion failed (exit code " + exitCode + "). " +
                "Is the input file a valid audio file?");
        }
    }

    private double getAudioDuration(File oggFile) {
        try {
            String ffmpeg = plugin.getFfmpegPath();
            ProcessBuilder pb = new ProcessBuilder(
                ffmpeg, "-i", oggFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            String output = readStream(proc.getInputStream());
            proc.waitFor();

            // Parse "Duration: HH:MM:SS.ss"
            Matcher m = Pattern.compile("Duration: (\\d+):(\\d+):([\\d.]+)").matcher(output);
            if (m.find()) {
                int hours = Integer.parseInt(m.group(1));
                int minutes = Integer.parseInt(m.group(2));
                double seconds = Double.parseDouble(m.group(3));
                return hours * 3600 + minutes * 60 + seconds;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Sanitizes a URL/filename into a valid sound key:
     * lowercase alphanumeric + underscores only
     */
    public static String toSoundKey(String input) {
        // Extract filename if it's a URL
        String name = input;
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        // Remove query params
        if (name.contains("?")) {
            name = name.substring(0, name.indexOf('?'));
        }
        // Remove extension
        if (name.contains(".")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        // Sanitize: keep alphanumeric and underscores, lowercase
        name = name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        // Collapse consecutive underscores
        name = name.replaceAll("_+", "_").replaceAll("^_|_$", "");
        // Limit length
        if (name.length() > 40) name = name.substring(0, 40);
        if (name.isEmpty()) name = "disc_" + System.currentTimeMillis();
        return name;
    }
}
