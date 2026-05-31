package com.sound2disc.listeners;

import com.sound2disc.Sound2Disc;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class JukeboxListener implements Listener {

    private final Sound2Disc plugin;
    private final NamespacedKey SOUND_KEY;

    private static class ActiveDisc {
        final String soundKey;
        final ItemStack discItem;
        ActiveDisc(String soundKey, ItemStack discItem) { this.soundKey = soundKey; this.discItem = discItem; }
    }

    private final Map<Location, ActiveDisc> activeJukeboxes = new HashMap<>();

    public JukeboxListener(Sound2Disc plugin) {
        this.plugin = plugin;
        SOUND_KEY = new NamespacedKey(plugin, "sound_key");
    }

    public static ItemStack createDisc(Sound2Disc plugin, String soundKey, String displayName) {
        NamespacedKey key = new NamespacedKey(plugin, "sound_key");
        ItemStack disc = new ItemStack(Material.MUSIC_DISC_11);
        ItemMeta meta = disc.getItemMeta();
        String nameFormat = plugin.getConfig().getString("disc-name-format", "&b✦ Custom Disc &7[&f%name%&7]");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', nameFormat.replace("%name%", displayName)));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Sound: " + ChatColor.WHITE + displayName);
        lore.add(ChatColor.DARK_GRAY + "Place in a jukebox to play");
        lore.add(ChatColor.DARK_PURPLE + "" + ChatColor.ITALIC + "♪ Custom Sound2Disc ♪");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, soundKey);
        disc.setItemMeta(meta);
        return disc;
    }

    public boolean isCustomDisc(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(SOUND_KEY, PersistentDataType.STRING);
    }

    public String getSoundKey(ItemStack item) {
        if (!isCustomDisc(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(SOUND_KEY, PersistentDataType.STRING);
    }

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

        if (holdingCustomDisc) {
            event.setCancelled(true);
            String soundKey = getSoundKey(hand);
            if (soundKey == null) return;

            // Eject whatever is playing first
            if (jukeboxHasCustom) {
                ejectCustomDisc(block, null);
            } else {
                Jukebox jb = (Jukebox) block.getState();
                if (jb.isPlaying()) { jb.stopPlaying(); jb.update(); }
            }

            // Take disc from player
            ItemStack discCopy = hand.clone();
            discCopy.setAmount(1);
            if (player.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);

            // Set jukebox record visually (this triggers vanilla sound — we stop it immediately)
            Jukebox jb = (Jukebox) block.getState();
            jb.setRecord(new ItemStack(Material.MUSIC_DISC_11));
            jb.update();

            // Stop the vanilla disc-11 sound that just got triggered for all nearby players
            stopVanillaJukeboxSound(loc);

            // Register and play our custom sound
            activeJukeboxes.put(loc, new ActiveDisc(soundKey, discCopy));
            playCustomSoundAtJukebox(loc, soundKey);

            // Check if player has resource pack — warn if not
            block.getWorld().spawnParticle(Particle.NOTE, loc.clone().add(0.5, 1.2, 0.5), 5, 0.5, 0.5, 0.5, 1.0);
            player.sendMessage(ChatColor.GREEN + "♪ Now playing: " + ChatColor.WHITE + soundKey + ChatColor.GREEN + " ♪");

            // If they may not have the resource pack, remind them
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(ChatColor.GRAY + "Not hearing sound? Run " +
                        ChatColor.AQUA + "/sound2disc pack" +
                        ChatColor.GRAY + " to load the resource pack.");
                }
            }, 40L); // 2 seconds later

        } else if (jukeboxHasCustom && (hand == null || hand.getType() == Material.AIR)) {
            event.setCancelled(true);
            ejectCustomDisc(block, player);
        }
    }

    private void ejectCustomDisc(Block block, Player player) {
        Location loc = block.getLocation();
        ActiveDisc active = activeJukeboxes.remove(loc);
        if (active == null) return;

        stopCustomSoundAtJukebox(loc, active.soundKey);

        Jukebox jb = (Jukebox) block.getState();
        jb.stopPlaying();
        jb.setRecord(new ItemStack(Material.AIR));
        jb.update();

        block.getWorld().dropItemNaturally(loc.clone().add(0.5, 1.0, 0.5), active.discItem);
        if (player != null) player.sendMessage(ChatColor.YELLOW + "Disc ejected.");
    }

    // Stops the vanilla Music Disc 11 sound that plays when we call jb.setRecord()
    private void stopVanillaJukeboxSound(Location loc) {
        float range = (float) plugin.getConfig().getDouble("jukebox-sound-range", 65.0);
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distance(loc) <= range) {
                // Stop the vanilla disc 11 sound
                p.stopSound(Sound.MUSIC_DISC_11, SoundCategory.RECORDS);
                // Also stop all record-category sounds just in case
                p.stopSound(SoundCategory.RECORDS);
            }
        }
    }

    private void playCustomSoundAtJukebox(Location loc, String soundKey) {
        float range = (float) plugin.getConfig().getDouble("jukebox-sound-range", 65.0);
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distance(loc) <= range) {
                p.playSound(loc, "sound2disc:" + soundKey, SoundCategory.RECORDS, 4.0f, 1.0f);
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

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.JUKEBOX) return;
        if (activeJukeboxes.containsKey(block.getLocation())) ejectCustomDisc(block, null);
    }

    public void stopAll() {
        for (Location loc : new HashSet<>(activeJukeboxes.keySet())) {
            Block block = loc.getBlock();
            if (block.getType() == Material.JUKEBOX) ejectCustomDisc(block, null);
            else activeJukeboxes.remove(loc);
        }
    }
}
