package com.sound2disc.managers;

import com.sound2disc.Sound2Disc;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

public class ResourcePackManager {

    private final Sound2Disc plugin;
    private File packFile;
    private String packHash;
    private String packUrl;
    private final Set<String> registeredSounds = new LinkedHashSet<>();
    private com.sun.net.httpserver.HttpServer httpServer;

    private static final String PACK_FORMAT   = "15";
    private static final String PACK_FILENAME = "sound2disc_pack.zip";

    // Upload hosts tried in order
    private static final String[][] UPLOAD_HOSTS = {
        // 0x0.st — simple curl-style upload
        { "0x0.st",       "https://0x0.st",                          "file" },
        // litterbox.catbox.moe — temp files, 72h, no account needed
        { "litterbox",    "https://litterbox.catbox.moe/resources/internals/api.php", "litterbox" },
        // transfer.sh
        { "transfer.sh",  "https://transfer.sh/" + PACK_FILENAME,    "transfer.sh" },
    };

    public ResourcePackManager(Sound2Disc plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        packFile = new File(plugin.getDataFolder(), "resourcepack" + File.separator + PACK_FILENAME);

        File soundsDir = new File(plugin.getDataFolder(), "sounds");
        if (soundsDir.exists()) {
            File[] files = soundsDir.listFiles((d, n) -> n.endsWith(".ogg"));
            if (files != null) for (File f : files) registeredSounds.add(f.getName().replace(".ogg", ""));
        }

        // Load cached URL
        String cached = loadCachedUrl();
        if (cached != null && packFile.exists()) {
            packUrl = cached;
            try { packHash = sha1(packFile); } catch (Exception ignored) {}
            plugin.getLogger().info("Loaded cached resource pack URL: " + packUrl);
            return;
        }

        try { rebuildPack(); } catch (Exception e) {
            plugin.getLogger().severe("Failed to build resource pack: " + e.getMessage());
        }
    }

    public void addSound(String soundKey) throws Exception {
        registeredSounds.add(soundKey);
        rebuildPack();
    }

    public void rebuildPack() throws Exception {
        buildZip();

        // Try each upload host in order
        for (String[] host : UPLOAD_HOSTS) {
            try {
                plugin.getLogger().info("Uploading resource pack to " + host[0] + "...");
                String url = upload(host, packFile);
                if (url != null && url.startsWith("http")) {
                    packUrl = url;
                    saveCachedUrl(url);
                    plugin.getLogger().info("Resource pack hosted at: " + packUrl);
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().warning(host[0] + " upload failed: " + e.getMessage());
            }
        }

        // All hosts failed — fall back to built-in HTTP server
        plugin.getLogger().warning("All upload hosts failed. Falling back to built-in HTTP server.");
        plugin.getLogger().warning("Run /sound2disc reload once your server port is accessible.");
        startHttpServer();
    }

    private String upload(String[] host, File file) throws Exception {
        String type = host[2];

        if (type.equals("0x0.st")) {
            return uploadMultipart(host[1], "file", file, null, null);
        } else if (type.equals("litterbox")) {
            return uploadMultipart(host[1], "fileToUpload", file,
                new String[]{"reqtype", "time"},
                new String[]{"fileupload", "72h"});
        } else if (type.equals("transfer.sh")) {
            // transfer.sh uses PUT
            return uploadPut(host[1], file);
        }
        return null;
    }

    private String uploadMultipart(String urlStr, String fileField, File file,
                                    String[] extraFields, String[] extraValues) throws Exception {
        String boundary = "----S2DBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = openConn(urlStr, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = conn.getOutputStream()) {
            // Extra fields
            if (extraFields != null) {
                for (int i = 0; i < extraFields.length; i++) {
                    out.write(("--" + boundary + "\r\n").getBytes());
                    out.write(("Content-Disposition: form-data; name=\"" + extraFields[i] + "\"\r\n\r\n").getBytes());
                    out.write((extraValues[i] + "\r\n").getBytes());
                }
            }
            // File
            out.write(("--" + boundary + "\r\n").getBytes());
            out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + PACK_FILENAME + "\"\r\n").getBytes());
            out.write("Content-Type: application/zip\r\n\r\n".getBytes());
            Files.copy(file.toPath(), out);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes());
        }

        return readResponse(conn);
    }

    private String uploadPut(String urlStr, File file) throws Exception {
        HttpURLConnection conn = openConn(urlStr, "PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/zip");
        conn.setRequestProperty("Content-Length", String.valueOf(file.length()));
        try (OutputStream out = conn.getOutputStream()) {
            Files.copy(file.toPath(), out);
        }
        return readResponse(conn);
    }

    private HttpURLConnection openConn(String urlStr, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "Sound2Disc-Plugin/1.0");
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        int status = conn.getResponseCode();
        InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        String body = in == null ? "" : new String(in.readAllBytes()).trim();
        conn.disconnect();
        if (status >= 400) throw new Exception("HTTP " + status + ": " + body);
        return body;
    }

    // ── Built-in HTTP Server fallback ──────────────────────────────────────────

    private void startHttpServer() {
        if (httpServer != null) return;
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
            plugin.getLogger().info("Fallback HTTP server: " + packUrl);
        } catch (IOException e) {
            plugin.getLogger().severe("HTTP server failed: " + e.getMessage());
        }
    }

    // ── ZIP Builder ────────────────────────────────────────────────────────────

    private void buildZip() throws Exception {
        File soundsDir = new File(plugin.getDataFolder(), "sounds");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(packFile))) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            putEntry(zip, "pack.mcmeta",
                "{\n  \"pack\": {\n    \"pack_format\": " + PACK_FORMAT + ",\n    \"description\": \"Sound2Disc\"\n  }\n}");
            putEntry(zip, "assets/sound2disc/sounds.json", buildSoundsJson());
            for (String key : registeredSounds) {
                File ogg = new File(soundsDir, key + ".ogg");
                if (ogg.exists()) {
                    zip.putNextEntry(new ZipEntry("assets/sound2disc/sounds/" + key + ".ogg"));
                    Files.copy(ogg.toPath(), zip);
                    zip.closeEntry();
                }
            }
        }
        packHash = sha1(packFile);
        plugin.getLogger().info("Pack built — " + registeredSounds.size() + " sound(s)");
    }

    private String buildSoundsJson() {
        if (registeredSounds.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{\n");
        Iterator<String> it = registeredSounds.iterator();
        while (it.hasNext()) {
            String key = it.next();
            sb.append("  \"").append(key).append("\": {\"sounds\": [{\"name\": \"sound2disc/")
              .append(key).append("\", \"stream\": true}]}");
            if (it.hasNext()) sb.append(",");
            sb.append("\n");
        }
        return sb.append("}").toString();
    }

    private void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes("UTF-8"));
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

    // ── URL Cache ──────────────────────────────────────────────────────────────

    private void saveCachedUrl(String url) {
        try {
            File f = new File(plugin.getDataFolder(), "resourcepack/pack_url.txt");
            Files.write(f.toPath(), url.getBytes());
        } catch (Exception ignored) {}
    }

    private String loadCachedUrl() {
        try {
            File f = new File(plugin.getDataFolder(), "resourcepack/pack_url.txt");
            if (f.exists()) return new String(Files.readAllBytes(f.toPath())).trim();
        } catch (Exception ignored) {}
        return null;
    }

    public void clearUrlCache() {
        new File(plugin.getDataFolder(), "resourcepack/pack_url.txt").delete();
        packUrl = null;
    }

    public void shutdown() { if (httpServer != null) httpServer.stop(0); }
    public Set<String> getRegisteredSounds() { return Collections.unmodifiableSet(registeredSounds); }
    public boolean hasSound(String key) { return registeredSounds.contains(key); }
    public String getPackUrl()  { return packUrl  != null ? packUrl  : "(not available)"; }
    public String getPackHash() { return packHash != null ? packHash : ""; }
    public File getPackFile()   { return packFile; }
}
