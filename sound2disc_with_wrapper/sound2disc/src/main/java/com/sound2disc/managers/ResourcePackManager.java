package com.sound2disc.managers;

import com.sound2disc.Sound2Disc;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.*;

/**
 * Manages the dynamic resource pack:
 * - Tracks which OGG sounds are registered
 * - Generates the ZIP with sounds.json + OGG files
 * - Serves it via a lightweight built-in HTTP server
 * - Provides the SHA-1 hash for Minecraft's resource pack verification
 */
public class ResourcePackManager {

    private final Sound2Disc plugin;
    private HttpServer httpServer;
    private File packFile;
    private String packHash;
    private String packUrl;
    private final Set<String> registeredSounds = new LinkedHashSet<>();

    private static final String PACK_FORMAT = "15"; // 1.20.3–1.20.4
    private static final String PACK_FILENAME = "sound2disc_pack.zip";

    public ResourcePackManager(Sound2Disc plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        packFile = new File(plugin.getDataFolder(), "resourcepack" + File.separator + PACK_FILENAME);

        // Load already-converted sounds from disk
        File soundsDir = new File(plugin.getDataFolder(), "sounds");
        if (soundsDir.exists()) {
            File[] files = soundsDir.listFiles((d, n) -> n.endsWith(".ogg"));
            if (files != null) {
                for (File f : files) {
                    registeredSounds.add(f.getName().replace(".ogg", ""));
                }
            }
        }

        // Build the pack
        try {
            rebuildPack();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to build initial resource pack: " + e.getMessage());
        }

        // Start HTTP server
        startHttpServer();
    }

    /**
     * Adds a new sound to the resource pack and rebuilds it.
     */
    public void addSound(String soundKey) throws Exception {
        registeredSounds.add(soundKey);
        rebuildPack();
    }

    /**
     * Returns all registered sound keys.
     */
    public Set<String> getRegisteredSounds() {
        return Collections.unmodifiableSet(registeredSounds);
    }

    public boolean hasSound(String key) {
        return registeredSounds.contains(key);
    }

    // ── Pack Generation ────────────────────────────────────────────────────────

    public void rebuildPack() throws Exception {
        File soundsDir = new File(plugin.getDataFolder(), "sounds");

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(packFile))) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);

            // pack.mcmeta
            putZipEntry(zip, "pack.mcmeta",
                "{\n" +
                "  \"pack\": {\n" +
                "    \"pack_format\": " + PACK_FORMAT + ",\n" +
                "    \"description\": \"Sound2Disc — Custom Music Discs\"\n" +
                "  }\n" +
                "}"
            );

            // sounds.json  (namespace: sound2disc)
            putZipEntry(zip, "assets/sound2disc/sounds.json", buildSoundsJson());

            // Add each OGG file
            for (String key : registeredSounds) {
                File ogg = new File(soundsDir, key + ".ogg");
                if (ogg.exists()) {
                    putZipFile(zip, "assets/sound2disc/sounds/" + key + ".ogg", ogg);
                }
            }
        }

        // Compute SHA-1
        packHash = sha1(packFile);
        plugin.getLogger().info("Resource pack rebuilt. " + registeredSounds.size() +
            " sound(s), hash: " + packHash);
    }

    private String buildSoundsJson() {
        if (registeredSounds.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{\n");
        Iterator<String> it = registeredSounds.iterator();
        while (it.hasNext()) {
            String key = it.next();
            // "key": { "sounds": [{ "name": "sound2disc:key", "stream": true }] }
            sb.append("  \"").append(key).append("\": {\n");
            sb.append("    \"sounds\": [{\n");
            sb.append("      \"name\": \"sound2disc/").append(key).append("\",\n");
            sb.append("      \"stream\": true\n");
            sb.append("    }]\n");
            sb.append("  }");
            if (it.hasNext()) sb.append(",");
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private void putZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private void putZipFile(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file.toPath(), zip);
        zip.closeEntry();
    }

    private String sha1(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // ── HTTP Server ────────────────────────────────────────────────────────────

    private void startHttpServer() {
        int port = plugin.getConfig().getInt("resource-pack-port", 8765);
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/" + PACK_FILENAME, exchange -> {
                if (!packFile.exists()) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] data = Files.readAllBytes(packFile.toPath());
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + PACK_FILENAME + "\"");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
                exchange.close();
            });
            httpServer.setExecutor(null);
            httpServer.start();

            packUrl = buildUrl(port);
            plugin.getLogger().info("Resource pack server started on port " + port);
            plugin.getLogger().info("Pack URL: " + packUrl);

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start resource pack HTTP server: " + e.getMessage());
            plugin.getLogger().severe("Change resource-pack-port in config.yml and reload.");
        }
    }

    private String buildUrl(int port) {
        String configIp = plugin.getConfig().getString("server-ip", "").trim();
        if (!configIp.isEmpty()) {
            return "http://" + configIp + ":" + port + "/" + PACK_FILENAME;
        }
        // Auto-detect: try server IP from Bukkit
        String serverIp = plugin.getServer().getIp();
        if (serverIp == null || serverIp.isEmpty()) {
            try {
                serverIp = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                serverIp = "127.0.0.1";
            }
        }
        return "http://" + serverIp + ":" + port + "/" + PACK_FILENAME;
    }

    public void shutdown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public String getPackUrl() {
        return packUrl != null ? packUrl : "(not started)";
    }

    public String getPackHash() {
        return packHash != null ? packHash : "";
    }

    public File getPackFile() {
        return packFile;
    }
}
