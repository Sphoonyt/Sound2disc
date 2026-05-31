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

        plugin.getLogger().info("Downloading from: " + directUrl);
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

    private String resolveDirectUrl(String raw) throws Exception {
        raw = raw.trim();
        if (raw.contains("dropbox.com") || raw.contains("dropboxusercontent.com")) return resolveDropbox(raw);
        if (raw.contains("mediafire.com")) return resolveMediaFire(raw);
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw;
        File localFile = new File(new File(plugin.getDataFolder(), "sounds"), raw);
        if (localFile.exists()) return localFile.toURI().toString();
        throw new Exception("Not a recognized URL and file not found locally: " + raw);
    }

    private String resolveDropbox(String url) {
        String base = url.split("\\?")[0];
        base = base.replace("www.dropbox.com", "dl.dropboxusercontent.com");
        base = base.replace("dropbox.com", "dl.dropboxusercontent.com");
        return base + "?dl=1";
    }

    private String resolveMediaFire(String url) throws Exception {
        plugin.getLogger().info("Resolving MediaFire link...");
        HttpURLConnection conn = openConnection(url);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        String html = readStream(conn.getInputStream());
        conn.disconnect();
        Pattern p = Pattern.compile("href=\"(https://download\\d+\\.mediafire\\.com/[^\"]+)\"");
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1);
        throw new Exception("Could not extract direct download link from MediaFire page. Make sure the file is publicly shared.");
    }

    private void downloadFile(String urlStr, File dest) throws Exception {
        if (urlStr.startsWith("file:")) {
            Files.copy(new File(new URI(urlStr)).toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        HttpURLConnection conn = openConnection(urlStr);
        int status = conn.getResponseCode();
        int redirects = 0;
        while ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && redirects < 10) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = openConnection(location);
            status = conn.getResponseCode();
            redirects++;
        }
        if (status != 200) throw new Exception("HTTP " + status + " when downloading from: " + urlStr);
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { conn.disconnect(); }
    }

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private void convertToOgg(File input, File output) throws Exception {
        int quality = plugin.getConfig().getInt("ogg-quality", 6);
        ProcessBuilder pb = new ProcessBuilder(
            plugin.getFfmpegPath(),
            "-i", input.getAbsolutePath(),
            "-vn", "-ac", "1", "-ar", "44100",
            "-c:a", "libvorbis", "-q:a", String.valueOf(quality),
            "-map_metadata", "-1", "-y",
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
            throw new Exception("FFmpeg conversion failed (exit code " + exitCode + "). Is the input a valid audio file?");
        }
    }

    private double getAudioDuration(File oggFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(plugin.getFfmpegPath(), "-i", oggFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = readStream(proc.getInputStream());
            proc.waitFor();
            Matcher m = Pattern.compile("Duration: (\\d+):(\\d+):([\\d.]+)").matcher(output);
            if (m.find()) return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3));
        } catch (Exception ignored) {}
        return 0;
    }

    private String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

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
