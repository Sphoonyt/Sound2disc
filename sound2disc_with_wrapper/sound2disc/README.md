# 🎵 Sound2Disc — Minecraft Plugin

Turn any audio file (1 second to 5 minutes) into a playable custom music disc.
Supports Dropbox and MediaFire links, plus direct URLs and local files.

## Requirements

| Requirement | Version |
|-------------|---------|
| **Paper** (or Spigot) | 1.20.x |
| **Java** | 17+ |
| **FFmpeg** | Any recent version |

### Installing FFmpeg
```bash
# Ubuntu/Debian
sudo apt install ffmpeg

# CentOS/RHEL
sudo yum install ffmpeg

# Windows Server
# Download from https://ffmpeg.org/download.html
# Place ffmpeg.exe in plugins/Sound2Disc/ folder
```

---

## Installation

1. Drop `Sound2Disc.jar` into your `plugins/` folder
2. Start the server — config generates at `plugins/Sound2Disc/config.yml`
3. Open port **8765** (or change in config) — this is the resource pack HTTP server
4. Give yourself a disc with a URL:
   ```
   /sound2disc give https://www.dropbox.com/s/abc123/mysong.mp3?dl=0
   ```

---

## Commands

| Command | Description |
|---------|-------------|
| `/sound2disc give <URL>` | Download a sound from Dropbox/MediaFire and get a disc |
| `/sound2disc get <name>` | Get a disc for an already-converted sound |
| `/sound2disc list` | List all converted sounds |
| `/sound2disc pack` | Show resource pack URL and send pack to yourself |
| `/sound2disc reload` | Reload config and rebuild resource pack |

**Permission:** `sound2disc.use` (default: op)

---

## Supported URL Sources

### Dropbox
Paste any Dropbox sharing link — both `?dl=0` and `?dl=1` formats work:
```
/sound2disc give https://www.dropbox.com/s/abc123/mysong.mp3?dl=0
```

### MediaFire
Paste the MediaFire file page URL:
```
/sound2disc give https://www.mediafire.com/file/xyz789/mysong.mp3/file
```

### Direct URL
Any direct download link to an audio file:
```
/sound2disc give https://example.com/audio/song.mp3
```

### Local File
Place the file in `plugins/Sound2Disc/sounds/` and use just the filename:
```
/sound2disc give mysong.mp3
```

---

## How It Works

```
User runs /sound2disc give <URL>
         │
         ▼
  Download audio file  ──── Dropbox/MediaFire link resolution
         │
         ▼
  FFmpeg converts → mono OGG (44100 Hz)
         │
         ▼
  Added to resource pack (ZIP)
         │
         ▼
  Resource pack re-sent to all online players
         │
         ▼
  Player receives MUSIC_DISC_11 item
  with custom NBT (sound key stored in PersistentDataContainer)
         │
         ▼
  Player places disc in Jukebox
         │
         ▼
  Plugin cancels vanilla sound, plays custom OGG via resource pack
```

---

## Resource Pack

Sound2Disc hosts its own lightweight HTTP resource pack server on port **8765**.

- Pack URL: `http://<your-server-ip>:8765/sound2disc_pack.zip`
- The pack is automatically sent to players when they join (if configured)
- The pack is re-sent to all players when a new sound is added

### Manual Pack Setup
If you'd rather host the pack yourself (e.g. on a CDN):
1. Run `/sound2disc pack` to see the pack location
2. Copy `plugins/Sound2Disc/resourcepack/sound2disc_pack.zip` to your host
3. Set `server-ip` and update your server's `server.properties` resource-pack URL manually

---

## Configuration

```yaml
# plugins/Sound2Disc/config.yml

# Port for the built-in resource pack HTTP server
resource-pack-port: 8765

# Optional: Override the server IP in the resource pack URL
server-ip: ""

# Max audio duration in seconds (1-300)
max-duration-seconds: 300

# OGG conversion quality (0-10)
ogg-quality: 6

# Jukebox sound range in blocks
jukebox-sound-range: 65.0

# Disc item display name format
disc-name-format: "&b✦ Custom Disc &7[&f%name%&7]"
```

---

## File Structure

```
plugins/Sound2Disc/
├── config.yml
├── sounds/           ← Converted OGG files stored here
│   ├── mysong.ogg
│   └── another_track.ogg
├── resourcepack/
│   └── sound2disc_pack.zip   ← Auto-generated, served via HTTP
└── ffmpeg            ← Optional: local FFmpeg binary (Windows)
```

---

## Troubleshooting

**"FFmpeg not found"**
→ Run `sudo apt install ffmpeg` on Linux, or place `ffmpeg`/`ffmpeg.exe` in `plugins/Sound2Disc/`

**Sound plays but I hear nothing**
→ You need to download the resource pack. Run `/sound2disc pack` and accept the pack prompt.

**"Could not extract direct download link from MediaFire"**
→ Make sure the MediaFire file is set to **public** sharing.

**Port 8765 already in use**
→ Change `resource-pack-port` in `config.yml` and do `/sound2disc reload`

**Conversion fails with "invalid audio file"**
→ FFmpeg can't read the file format. Try converting to MP3 first, then share that.

---

## Building from Source

```bash
git clone <repo>
cd sound2disc
./gradlew build
# Output: build/libs/Sound2Disc-1.0.0.jar
```

---

## License

MIT License — free to use, modify, and distribute.
