package com.sound2disc;

import com.sound2disc.commands.Sound2DiscCommand;
import com.sound2disc.listeners.JukeboxListener;
import com.sound2disc.managers.ResourcePackManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

public class Sound2Disc extends JavaPlugin {

    private static Sound2Disc instance;
    private ResourcePackManager resourcePackManager;
    private static final Logger LOG = Logger.getLogger("Sound2Disc");

    @Override
    public void onEnable() {
        instance = this;

        // Save default configs
        saveDefaultConfig();

        // Create data directories
        File soundsDir = new File(getDataFolder(), "sounds");
        if (!soundsDir.exists()) soundsDir.mkdirs();

        File packDir = new File(getDataFolder(), "resourcepack");
        if (!packDir.exists()) packDir.mkdirs();

        // Check FFmpeg
        if (!checkFFmpeg()) {
            getLogger().severe("════════════════════════════════════════════");
            getLogger().severe("  FFmpeg not found! Sound2Disc requires FFmpeg");
            getLogger().severe("  Install it: sudo apt install ffmpeg");
            getLogger().severe("  (or place ffmpeg binary in plugin folder)");
            getLogger().severe("════════════════════════════════════════════");
            getLogger().severe("  Plugin will load but conversions will FAIL");
            getLogger().severe("════════════════════════════════════════════");
        } else {
            getLogger().info("FFmpeg detected ✓");
        }

        // Start resource pack manager (builds pack + starts HTTP server)
        resourcePackManager = new ResourcePackManager(this);
        resourcePackManager.initialize();

        // Register commands
        getCommand("sound2disc").setExecutor(new Sound2DiscCommand(this));
        getCommand("sound2disc").setTabCompleter(new Sound2DiscCommand(this));

        // Register listeners
        getServer().getPluginManager().registerEvents(new JukeboxListener(this), this);

        getLogger().info("Sound2Disc enabled! Use /sound2disc give <URL> to create discs.");
        getLogger().info("Resource pack URL: " + resourcePackManager.getPackUrl());
    }

    @Override
    public void onDisable() {
        if (resourcePackManager != null) {
            resourcePackManager.shutdown();
        }
        getLogger().info("Sound2Disc disabled.");
    }

    private boolean checkFFmpeg() {
        // Check if ffmpeg binary is in plugin folder first
        File localFfmpeg = new File(getDataFolder(), "ffmpeg");
        if (localFfmpeg.exists()) return true;

        // Check system PATH
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String getFfmpegPath() {
        File localFfmpeg = new File(getDataFolder(), "ffmpeg");
        if (localFfmpeg.exists()) return localFfmpeg.getAbsolutePath();
        return "ffmpeg";
    }

    public static Sound2Disc getInstance() {
        return instance;
    }

    public ResourcePackManager getResourcePackManager() {
        return resourcePackManager;
    }
}
