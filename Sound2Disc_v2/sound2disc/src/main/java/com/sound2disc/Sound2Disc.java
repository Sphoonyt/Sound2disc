package com.sound2disc;

import com.sound2disc.commands.Sound2DiscCommand;
import com.sound2disc.listeners.JukeboxListener;
import com.sound2disc.managers.ResourcePackManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

public class Sound2Disc extends JavaPlugin {

    private static Sound2Disc instance;
    private ResourcePackManager resourcePackManager;

    // Static binary URL — musl build works on virtually all Linux distros / Docker containers
    private static final String FFMPEG_URL =
        "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz";

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        new File(getDataFolder(), "sounds").mkdirs();
        new File(getDataFolder(), "resourcepack").mkdirs();

        // Try to get FFmpeg — download automatically if missing
        if (!checkFFmpeg()) {
            getLogger().warning("FFmpeg not found — attempting automatic download...");
            getLogger().warning("This may take a minute on first startup.");
            try {
                downloadFFmpeg();
                getLogger().info("FFmpeg downloaded successfully!");
            } catch (Exception e) {
                getLogger().severe("Auto-download failed: " + e.getMessage());
                getLogger().severe("Place an 'ffmpeg' binary manually in: " + getDataFolder());
                getLogger().severe("Plugin will load but conversions will fail.");
            }
        } else {
            getLogger().info("FFmpeg detected ✓");
        }

        resourcePackManager = new ResourcePackManager(this);
        resourcePackManager.initialize();

        getCommand("sound2disc").setExecutor(new Sound2DiscCommand(this));
        getCommand("sound2disc").setTabCompleter(new Sound2DiscCommand(this));
        getServer().getPluginManager().registerEvents(new JukeboxListener(this), this);

        getLogger().info("Sound2Disc enabled! Use /sound2disc give <URL>");
        getLogger().info("Resource pack URL: " + resourcePackManager.getPackUrl());
    }

    @Override
    public void onDisable() {
        if (resourcePackManager != null) resourcePackManager.shutdown();
    }

    // ── FFmpeg Detection ───────────────────────────────────────────────────────

    private boolean checkFFmpeg() {
        // 1. Local binary in plugin folder
        if (getLocalFfmpeg().exists()) return true;

        // 2. System PATH
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String getFfmpegPath() {
        File local = getLocalFfmpeg();
        if (local.exists()) return local.getAbsolutePath();
        return "ffmpeg";
    }

    private File getLocalFfmpeg() {
        return new File(getDataFolder(), "ffmpeg");
    }

    // ── Auto-Downloader ────────────────────────────────────────────────────────

    private void downloadFFmpeg() throws Exception {
        File dataFolder = getDataFolder();
        File tarFile = new File(dataFolder, "ffmpeg.tar.xz");
        File ffmpegBin = new File(dataFolder, "ffmpeg");

        // Step 1: Download the tar.xz
        getLogger().info("Downloading FFmpeg static build (~75 MB)...");
        downloadFile(FFMPEG_URL, tarFile);
        getLogger().info("Download complete. Extracting...");

        // Step 2: Extract with tar (available on all Linux systems including Docker)
        // We extract only the 'ffmpeg' binary from the archive
        ProcessBuilder pb = new ProcessBuilder(
            "tar",
            "--strip-components=1",   // remove the top-level directory
            "-xf", tarFile.getAbsolutePath(),
            "--wildcards", "*/ffmpeg", // only extract the ffmpeg binary
            "-C", dataFolder.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor();

        if (!ffmpegBin.exists()) {
            // Fallback: find extracted binary by searching
            Optional<Path> found = Files.walk(dataFolder.toPath())
                .filter(p -> p.getFileName().toString().equals("ffmpeg") && !p.equals(ffmpegBin.toPath()))
                .findFirst();
            if (found.isPresent()) {
                Files.move(found.get(), ffmpegBin.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new Exception("Could not find 'ffmpeg' binary after extraction.\ntar output: " + output);
            }
        }

        // Step 3: Make it executable
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(ffmpegBin.toPath());
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(ffmpegBin.toPath(), perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows — chmod not needed
            ffmpegBin.setExecutable(true);
        }

        // Step 4: Clean up tar file
        tarFile.delete();

        // Step 5: Verify it actually works
        if (!checkFFmpeg()) {
            throw new Exception("FFmpeg binary was downloaded but failed to execute. " +
                "This may be an architecture mismatch (plugin downloads amd64).");
        }
    }

    private void downloadFile(String urlStr, File dest) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(300_000); // 5 min — file is ~75 MB
        conn.setRequestProperty("User-Agent", "Sound2Disc-Plugin/1.0");
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
        // Follow redirects manually if needed
        int redirects = 0;
        while ((status == 301 || status == 302 || status == 307 || status == 308) && redirects++ < 5) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(loc).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(300_000);
            conn.setRequestProperty("User-Agent", "Sound2Disc-Plugin/1.0");
            status = conn.getResponseCode();
        }

        if (status != 200) throw new Exception("HTTP " + status + " from " + urlStr);

        long total = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            long downloaded = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    int pct = (int) (downloaded * 100 / total);
                    if (pct / 10 != lastPct / 10) {
                        lastPct = pct;
                        getLogger().info("Downloading FFmpeg: " + pct + "% (" +
                            (downloaded / 1024 / 1024) + " MB / " + (total / 1024 / 1024) + " MB)");
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    // ── Static Accessor ────────────────────────────────────────────────────────

    public static Sound2Disc getInstance() { return instance; }
    public ResourcePackManager getResourcePackManager() { return resourcePackManager; }
}
