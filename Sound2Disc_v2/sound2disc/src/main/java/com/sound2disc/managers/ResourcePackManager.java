package com.sound2disc.managers;

import com.sound2disc.Sound2Disc;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Builds the resource pack ZIP and hosts it via catbox.moe (free, permanent, no account needed).
 * Falls back to a built-in HTTP server if catbox upload fails.
 */
public class ResourcePackManager {

    private final Sound2Disc plugin;
    private File packFile;
    private String packHash;
    private String packUrl;
    private final Set<String> registeredSounds = new LinkedHashSet<>();

    private static final String PACK_FORMAT   = "15"; // 1.20.3-1.20.4
    private static final String PACK_FILENAME = "sound2disc_pack.zip";
    private static final String CATBOX_API    = "https://catbox.moe/user/api.php";

    // Simple HTTP server as fallback
    private com.sun.net.httpserver.HttpServer httpServer;

    public ResourcePackManager(Sound2Disc plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        packFile = new File(plugin.getDataFolder(), "resourcepack" + File.separator + PACK_FILENAME);

        // Load already-converted sounds
        File soundsDir = new File(plugin.getDataFolder(), "sounds");
        if (soundsDir.exists()) {
            File[] files = soundsDir.listFiles((d, n) -> n.endsWith(".ogg"));
            if (files != null) for (File f : files) registeredSounds.add(f.getName().replace(".ogg", ""));
        }

        // Load cached pack URL if we have one
        File urlCache = new File(plugin.getDataFolder(), "resourcepack" + File.separator + "pack_url.txt");
        if (urlCache.exists() && packFile.exists()) {
            try {
                String cached = new String(Files.readAllBytes(urlCache.toPath())).trim();
                if (!cached.isEmpty()) {
                    packUrl = cached;
                    packHash = sha1(packFile);
                    plugin.getLogger().info("Loaded cached resource pack URL: " + packUrl);
                    return; // Skip rebuild on startup if nothing changed
                }
            } catch (Exception ignored) {}
        }

        try {
            rebuildPack();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to build resource pack: " + e.getMessage());
        }
    }

    public void addSound(String soundKey) throws Exception {
        registeredSounds.add(soundKey);
        rebuildPack();
    }

    public void rebuildPack() throws Exception {
        buildZip();

        // Try catbox.moe first — works on all hosts including Pterodactyl
        try {
            plugin.getLogger().info("Uploading resource pack to catbox.moe...");
            String url = uploadToCatbox(packFile);
            packUrl = url;
            plugin.getLogger().info("Resource pack hosted at: " + packUrl);

            // Cache URL to disk so we don't re-upload on every restart
            File urlCache = new File(plugin.getDataFolder(), "resourcepack" + File.separator + "pack_url.txt");
            Files.write(urlCache.toPath(), url.getBytes());

        } catch (Exception e) {
            plugin.getLogger().warning("catbox.moe upload failed: " + e.getMessage());
            plugin.getLogger().warning("Falling back to built-in HTTP server...");
            startHttpServerFallback();
        }
    }

    // ── ZIP Builder ────────────────────────────────────────────────────────────

    private void buildZip() throws Exception {
        File soundsDir = new File(plugin.getDataFolder(), "sounds");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(packFile))) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            putZipEntry(zip, "pack.mcmeta",
                "{\n  \"pack\": {\n    \"pack_format\": " + PACK_FORMAT +
                ",\n    \"description\": \"Sound2Disc Custom Discs\"\n  }\n}");
            putZipEntry(zip, "assets/sound2disc/sounds.json", buildSoundsJson());
            for (String key : registeredSounds) {
                File ogg = new File(soundsDir, key + ".ogg");
                if (ogg.exists()) putZipFile(zip, "assets/sound2disc/sounds/" + key + ".ogg", ogg);
            }
        }
        packHash = sha1(packFile);
        plugin.getLogger().info("Resource pack built — " + registeredSounds.size() + " sound(s), hash: " + packHash);
    }

    private String buildSoundsJson() {
        if (registeredSounds.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{\n");
        Iterator<String> it = registeredSounds.iterator();
        while (it.hasNext()) {
            String key = it.next();
            sb.append("  \"").append(key).append("\": {\n")
              .append("    \"sounds\": [{\n")
              .append("      \"name\": \"sound2disc/").append(key).append("\",\n")
              .append("      \"stream\": true\n")
              .append("    }]\n")
              .append("  }");
            if (it.hasNext()) sb.append(",");
            sb.append("\n");
        }
        return sb.append("}").toString();
    }

    // ── Catbox Upload ──────────────────────────────────────────────────────────

    /**
     * Uploads the pack ZIP to catbox.moe using multipart/form-data.
     * Returns the public URL (e.g. https://files.catbox.moe/abc123.zip).
     * Files on catbox are permanent and free — no account needed.
     */
    private String uploadToCatbox(File file) throws Exception {
        String boundary = "----Sound2DiscBoundary" + System.currentTimeMillis();
        URL url = new URL(CATBOX_API);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("User-Agent", "Sound2Disc-Plugin/1.0");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);

        try (OutputStream out = conn.getOutputStream()) {
            // Field: reqtype=fileupload
            writeFormField(out, boundary, "reqtype", "fileupload");

            // File: fileToUpload=<zip>
            out.write(("--" + boundary + "\r\n").getBytes());
            out.write(("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + PACK_FILENAME + "\"\r\n").getBytes());
            out.write("Content-Type: application/zip\r\n\r\n".getBytes());
            Files.copy(file.toPath(), out);
            out.write("\r\n".getBytes());

            // End boundary
            out.write(("--" + boundary + "--\r\n").getBytes());
        }

        int status = conn.getResponseCode();
        String response;
        try (InputStream in = status == 200 ? conn.getInputStream() : conn.getErrorStream()) {
            response = in == null ? "" : new String(in.readAllBytes()).trim();
        }
        conn.disconnect();

        if (status != 200 || response.isEmpty()) {
            throw new Exception("catbox returned HTTP " + status + ": " + response);
        }
        if (!response.startsWith("https://")) {
            throw new Exception("Unexpected catbox response: " + response);
        }

        return response;
    }

    private void writeFormField(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        out.write((value + "\r\n").getBytes());
    }

    // ── HTTP Server Fallback ───────────────────────────────────────────────────

    private void startHttpServerFallback() {
        if (httpServer != null) return; // already running
        int port = plugin.getConfig().getInt("resource-pack-port", 8765);
        try {
            httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/" + PACK_FILENAME, exchange -> {
                if (!packFile.exists()) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                byte[] data = Files.readAllBytes(packFile.toPath());
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
                exchange.close();
            });
            httpServer.setExecutor(null);
            httpServer.start();

            String configIp = plugin.getConfig().getString("server-ip", "").trim();
            String ip = configIp.isEmpty() ? plugin.getServer().getIp() : configIp;
            if (ip == null || ip.isEmpty()) {
                try { ip = InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { ip = "127.0.0.1"; }
            }
            packUrl = "http://" + ip + ":" + port + "/" + PACK_FILENAME;
            plugin.getLogger().info("Fallback HTTP server started: " + packUrl);
            plugin.getLogger().warning("NOTE: This URL may not be reachable on Pterodactyl.");
            plugin.getLogger().warning("Set server-ip in config.yml to your panel's public IP.");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start fallback HTTP server: " + e.getMessage());
            packUrl = null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

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
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    public void shutdown() { if (httpServer != null) httpServer.stop(0); }

    public Set<String> getRegisteredSounds() { return Collections.unmodifiableSet(registeredSounds); }
    public boolean hasSound(String key) { return registeredSounds.contains(key); }
    public String getPackUrl()  { return packUrl  != null ? packUrl  : "(not available)"; }
    public String getPackHash() { return packHash != null ? packHash : ""; }
    public File getPackFile()   { return packFile; }

    /** Clears the cached URL so the next rebuild re-uploads */
    public void clearUrlCache() {
        File urlCache = new File(plugin.getDataFolder(), "resourcepack" + File.separator + "pack_url.txt");
        urlCache.delete();
        packUrl = null;
    }
}
