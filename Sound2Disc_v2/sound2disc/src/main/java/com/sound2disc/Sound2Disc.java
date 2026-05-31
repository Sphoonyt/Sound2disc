package com.sound2disc;

import com.sound2disc.commands.Sound2DiscCommand;
import com.sound2disc.listeners.JukeboxListener;
import com.sound2disc.listeners.PackListener;
import com.sound2disc.managers.ResourcePackManager;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

public class Sound2Disc extends JavaPlugin {

    private static Sound2Disc instance;
    private ResourcePackManager resourcePackManager;
    private PackListener packListener;

    private static final String FFMPEG_URL =
        "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz";

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        new File(getDataFolder(), "sounds").mkdirs();
        new File(getDataFolder(), "resourcepack").mkdirs();

        if (!checkFFmpeg()) {
            getLogger().warning("FFmpeg not found - attempting automatic download...");
            getLogger().warning("This may take a minute on first startup.");
            try {
                downloadFFmpeg();
                getLogger().info("FFmpeg downloaded and ready!");
            } catch (Exception e) {
                getLogger().severe("Auto-download failed: " + e.getMessage());
                getLogger().severe("Place an 'ffmpeg' binary manually in: " + getDataFolder());
            }
        } else {
            getLogger().info("FFmpeg detected!");
        }

        resourcePackManager = new ResourcePackManager(this);
        resourcePackManager.initialize();

        packListener = new PackListener(this);

        getCommand("sound2disc").setExecutor(new Sound2DiscCommand(this));
        getCommand("sound2disc").setTabCompleter(new Sound2DiscCommand(this));
        getServer().getPluginManager().registerEvents(new JukeboxListener(this), this);
        getServer().getPluginManager().registerEvents(packListener, this);

        getLogger().info("Sound2Disc enabled!");
    }

    @Override
    public void onDisable() {
        if (resourcePackManager != null) resourcePackManager.shutdown();
    }

    public PackListener getPackListener() { return packListener; }

    private boolean checkFFmpeg() {
        if (getLocalFfmpeg().exists()) return true;
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    public String getFfmpegPath() {
        File local = getLocalFfmpeg();
        return local.exists() ? local.getAbsolutePath() : "ffmpeg";
    }

    private File getLocalFfmpeg() {
        return new File(getDataFolder(), "ffmpeg");
    }

    private void downloadFFmpeg() throws Exception {
        File dataFolder = getDataFolder();
        File tarFile = new File(dataFolder, "ffmpeg.tar.xz");
        File ffmpegBin = new File(dataFolder, "ffmpeg");

        getLogger().info("Downloading FFmpeg static build (~75 MB)...");
        downloadFile(FFMPEG_URL, tarFile);
        getLogger().info("Download complete. Extracting using Java...");

        extractFfmpegFromTarXz(tarFile, ffmpegBin);
        tarFile.delete();

        if (!ffmpegBin.exists() || ffmpegBin.length() == 0)
            throw new Exception("Extraction completed but ffmpeg binary not found.");

        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(ffmpegBin.toPath()));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(ffmpegBin.toPath(), perms);
        } catch (UnsupportedOperationException e) {
            ffmpegBin.setExecutable(true);
        }

        if (!checkFFmpeg())
            throw new Exception("FFmpeg was extracted but failed to execute. Possible architecture mismatch.");

        getLogger().info("FFmpeg ready at: " + ffmpegBin.getAbsolutePath());
    }

    private void extractFfmpegFromTarXz(File tarXzFile, File outputFile) throws Exception {
        try (FileInputStream fis = new FileInputStream(tarXzFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             XZInputStream xzis = new XZInputStream(bis);
             TarArchiveInputStream tar = new TarArchiveInputStream(xzis)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/ffmpeg") && !name.endsWith("/ffprobe") && !name.endsWith("/ffplay")) {
                    getLogger().info("Found ffmpeg binary: " + name);
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = tar.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    getLogger().info("Extracted ffmpeg (" + (outputFile.length() / 1024 / 1024) + " MB)");
                    return;
                }
            }
        }
        throw new Exception("Could not find 'ffmpeg' binary inside the archive.");
    }

    private void downloadFile(String urlStr, File dest) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(300_000);
        conn.setRequestProperty("User-Agent", "Sound2Disc-Plugin/1.0");
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
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
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536]; long downloaded = 0; int n, lastPct = -1;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    int pct = (int)(downloaded * 100 / total);
                    if (pct / 10 != lastPct / 10) {
                        lastPct = pct;
                        getLogger().info("Downloading FFmpeg: " + pct + "% ("
                            + (downloaded/1024/1024) + " MB / " + (total/1024/1024) + " MB)");
                    }
                }
            }
        } finally { conn.disconnect(); }
    }

    public static Sound2Disc getInstance() { return instance; }
    public ResourcePackManager getResourcePackManager() { return resourcePackManager; }
}
