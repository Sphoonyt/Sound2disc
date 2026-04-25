package com.sound2disc.listeners;

import com.sound2disc.Sound2Disc;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Handles all jukebox interactions for custom Sound2Disc music discs.
 *
 * Flow:
 *  1. Player right-clicks a JUKEBOX block while holding a custom disc
 *  2. We cancel the event (prevent vanilla behavior)
 *  3. We store disc data in the jukebox, play custom OGG sound
 *  4. Player right-clicks again (no item or same disc) → eject + stop sound
 */
public class JukeboxListener implements Listener {

    private final Sound2Disc plugin;
    private final NamespacedKey SOUND_KEY;

    // Tracks active jukebox locations → sound data
    private static class ActiveDisc {
        final String soundKey;       // e.g. "my_song"
        final ItemStack discItem;    // the actual item (to return on eject)
        BukkitTask loopTask;         // null (we don't loop, Minecraft handles repeat)

        ActiveDisc(String soundKey, ItemStack discItem) {
            this.soundKey = soundKey;
            this.discItem = discItem;
        }
    }

    private final Map<Location, ActiveDisc> activeJukeboxes = new HashMap<>();

    public JukeboxListener(Sound2Disc plugin) {
        this.plugin = plugin;
        SOUND_KEY = new NamespacedKey(plugin, "sound_key");
    }

    // ── Create Custom Disc Item ────────────────────────────────────────────────

    /**
     * Creates a custom music disc ItemStack tagged with the given sound key.
     */
    public static ItemStack createDisc(Sound2Disc plugin, String soundKey, String displayName) {
        NamespacedKey key = new NamespacedKey(plugin, "sound_key");

        ItemStack disc = new ItemStack(Material.MUSIC_DISC_11);
        ItemMeta meta = disc.getItemMeta();

        // Display name
        String nameFormat = plugin.getConfig().getString("disc-name-format",
            "&b✦ Custom Disc &7[&f%name%&7]");
        String formattedName = ChatColor.translateAlternateColorCodes('&',
            nameFormat.replace("%name%", displayName));
        meta.setDisplayName(formattedName);

        // Lore
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Sound: " + ChatColor.WHITE + displayName);
        lore.add(ChatColor.DARK_GRAY + "Place in a jukebox to play");
        lore.add(ChatColor.DARK_PURPLE + "" + ChatColor.ITALIC + "♪ Custom Sound2Disc ♪");
        meta.setLore(lore);

        // Store sound key in PDC
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, soundKey);

        disc.setItemMeta(meta);
        return disc;
    }

    // ── Check if Item is a Custom Disc ─────────────────────────────────────────

    public boolean isCustomDisc(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(SOUND_KEY, PersistentDataType.STRING);
    }

    public String getSoundKey(ItemStack item) {
        if (!isCustomDisc(item)) return null;
        return item.getItemMeta().getPersistentDataContainer()
            .get(SOUND_KEY, PersistentDataType.STRING);
    }

    // ── Jukebox Interaction ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;

        Player player = event.getPlayer();
        ItemStack hand = event.getItem();
        Location loc = block.getLocation();

        boolean holdingCustomDisc = isCustomDisc(hand);
        boolean jukeboxHasCustom = activeJukeboxes.containsKey(loc);

        // Case 1: Holding a custom disc + jukebox is empty (or has custom disc)
        if (holdingCustomDisc) {
            event.setCancelled(true);

            String soundKey = getSoundKey(hand);
            if (soundKey == null) return;

            // If jukebox already playing something, eject first
            if (jukeboxHasCustom) {
                ejectCustomDisc(block, player);
            } else {
                // Check if vanilla disc is in jukebox
                Jukebox jb = (Jukebox) block.getState();
                if (jb.isPlaying()) {
                    jb.stopPlaying();
                    jb.update();
                }
            }

            // Insert the disc
            ItemStack discCopy = hand.clone();
            discCopy.setAmount(1);
            if (player.getGameMode() != GameMode.CREATIVE) {
                hand.setAmount(hand.getAmount() - 1);
            }

            // Store disc in jukebox visually (use block state)
            Jukebox jb = (Jukebox) block.getState();
            jb.setRecord(new ItemStack(Material.MUSIC_DISC_11)); // vanilla visual
            jb.update();

            // Register as active
            activeJukeboxes.put(loc, new ActiveDisc(soundKey, discCopy));

            // Play the custom sound for all nearby players
            playCustomSoundAtJukebox(block.getLocation(), soundKey);

            // Particles + vanilla note effect
            spawnJukeboxParticles(block);

            player.sendMessage(ChatColor.GREEN + "♪ " + ChatColor.WHITE +
                discCopy.getItemMeta().getDisplayName() + ChatColor.GREEN + " ♪");

        // Case 2: Not holding a custom disc but jukebox has one → eject
        } else if (jukeboxHasCustom && (hand == null || hand.getType() == Material.AIR)) {
            event.setCancelled(true);
            ejectCustomDisc(block, player);
        }
        // Case 3: Holding a custom disc but jukebox already has it (same sound) → eject
        else if (holdingCustomDisc && jukeboxHasCustom) {
            event.setCancelled(true);
            ejectCustomDisc(block, player);
        }
    }

    private void ejectCustomDisc(Block block, Player player) {
        Location loc = block.getLocation();
        ActiveDisc active = activeJukeboxes.remove(loc);
        if (active == null) return;

        // Stop sound for all nearby players
        stopCustomSoundAtJukebox(loc, active.soundKey);

        // Clear jukebox state
        Jukebox jb = (Jukebox) block.getState();
        jb.stopPlaying();
        jb.setRecord(new ItemStack(Material.AIR));
        jb.update();

        // Drop the disc
        block.getWorld().dropItemNaturally(
            loc.clone().add(0.5, 1.0, 0.5),
            active.discItem
        );

        if (player != null) {
            player.sendMessage(ChatColor.YELLOW + "⏏ Disc ejected.");
        }
    }

    // ── Sound Playback ─────────────────────────────────────────────────────────

    private void playCustomSoundAtJukebox(Location loc, String soundKey) {
        // Sound ID in our resource pack namespace: "sound2disc:<soundKey>"
        // Minecraft sends this to clients who have our resource pack loaded
        float range = (float) plugin.getConfig().getDouble("jukebox-sound-range", 65.0);

        // Play to all players in range
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distance(loc) <= range) {
                p.playSound(loc, "sound2disc:" + soundKey,
                    SoundCategory.RECORDS, 4.0f, 1.0f);
            }
        }
    }

    private void stopCustomSoundAtJukebox(Location loc, String soundKey) {
        float range = (float) plugin.getConfig().getDouble("jukebox-sound-range", 65.0);

        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distance(loc) <= range) {
                p.stopSound("sound2disc:" + soundKey, SoundCategory.RECORDS);
            }
        }
    }

    private void spawnJukeboxParticles(Block block) {
        Location center = block.getLocation().add(0.5, 1.2, 0.5);
        block.getWorld().spawnParticle(Particle.NOTE, center, 5,
            0.5, 0.5, 0.5, 1.0);
    }

    // ── Player Enter/Leave Range (sync sound) ──────────────────────────────────

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only check when player crosses a block boundary (performance)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        float range = (float) plugin.getConfig().getDouble("jukebox-sound-range", 65.0);

        for (Map.Entry<Location, ActiveDisc> entry : activeJukeboxes.entrySet()) {
            Location jukeLoc = entry.getKey();
            if (!jukeLoc.getWorld().equals(player.getWorld())) continue;

            double dist = player.getLocation().distance(jukeLoc);
            if (dist <= range) {
                // Player is in range — check if already playing (avoid double-play)
                // We can't easily check this in vanilla, so just re-send
                // (Minecraft deduplicates sounds client-side for the same sound ID)
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Nothing needed — sound stops client-side on disconnect
    }

    // ── Block Break (stop jukebox) ─────────────────────────────────────────────

    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.JUKEBOX) return;

        Location loc = block.getLocation();
        if (activeJukeboxes.containsKey(loc)) {
            ejectCustomDisc(block, null);
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Called by the command to stop all active jukeboxes (e.g. on reload) */
    public void stopAll() {
        for (Location loc : new HashSet<>(activeJukeboxes.keySet())) {
            Block block = loc.getBlock();
            if (block.getType() == Material.JUKEBOX) {
                ejectCustomDisc(block, null);
            } else {
                activeJukeboxes.remove(loc);
            }
        }
    }
}
