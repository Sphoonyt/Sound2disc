package com.sound2disc.listeners;

import com.sound2disc.Sound2Disc;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JukeboxListener implements Listener {

    private final Sound2Disc plugin;
    private final NamespacedKey SOUND_KEY;

    private static class ActiveDisc {
        final String soundKey;
        final ItemStack discItem;
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
        return meta != null && meta.getPersistentDataContainer().has(SOUND_KEY, PersistentDataType.STRING);
    }

    public String getSoundKey(ItemStack item) {
        if (!isCustomDisc(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(SOUND_KEY, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;

        Player player = event.getPlayer();
        ItemStack hand = event.getItem();
        Location loc = block.getLocation();

        boolean holdingCustomDisc = isCustomDisc(hand);
        boolean jukeboxHasCustom  = activeJukeboxes.containsKey(loc);

        if (holdingCustomDisc) {
            event.setCancelled(true);
            String soundKey = getSoundKey(hand);
            if (soundKey == null) return;

            if (jukeboxHasCustom) {
                ejectCustomDisc(block, null);
            } else {
                forceStopJukebox(block);
            }

            ItemStack discCopy = hand.clone();
            discCopy.setAmount(1);
            if (player.getGameMode() != GameMode.CREATIVE) {
                hand.setAmount(hand.getAmount() - 1);
            }

            activeJukeboxes.put(loc, new ActiveDisc(soundKey, discCopy));
            playCustomSound(loc, soundKey);

            loc.getWorld().spawnParticle(Particle.NOTE, loc.clone().add(0.5, 1.2, 0.5), 6, 0.5, 0.3, 0.5, 0);
            player.sendMessage(ChatColor.GREEN + "♪ Now playing: " + ChatColor.WHITE + soundKey);

        } else if (jukeboxHasCustom && (hand == null || hand.getType() == Material.AIR)) {
            event.setCancelled(true);
            ejectCustomDisc(block, player);
        }
    }

    private void ejectCustomDisc(Block block, Player player) {
        Location loc = block.getLocation();
        ActiveDisc active = activeJukeboxes.remove(loc);
        if (active == null) return;
        stopCustomSound(loc, active.soundKey);
        loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 1.0, 0.5), active.discItem);
        if (player != null) player.sendMessage(ChatColor.YELLOW + "⏏ Disc ejected.");
    }

    private void playCustomSound(Location loc, String soundKey) {
        float range = (float) plugin.getConfig().getDouble("jukebox-sound-range", 65.0);
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distance(loc) <= range) {
                p.stopSound(SoundCategory.RECORDS);
                p.playSound(loc, "sound2disc:" + soundKey, SoundCategory.RECORDS, 4.0f, 1.0f);
            }
        }
    }

    private void stopCustomSound(Location loc, String soundKey) {
        float range = (float) plugin.getConfig().getDouble("jukebox-sound-range", 65.0);
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distance(loc) <= range) {
                p.stopSound("sound2disc:" + soundKey, SoundCategory.RECORDS);
            }
        }
    }

    private void forceStopJukebox(Block block) {
        Location loc = block.getLocation();
        float range = (float) plugin.getConfig().getDouble("jukebox-sound-range", 65.0);
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distance(loc) <= range) {
                p.stopSound(SoundCategory.RECORDS);
            }
        }
        if (block.getState() instanceof org.bukkit.block.Jukebox) {
            org.bukkit.block.Jukebox jb = (org.bukkit.block.Jukebox) block.getState();
            jb.stopPlaying();
            jb.setRecord(new ItemStack(Material.AIR));
            jb.update(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.JUKEBOX) return;
        if (activeJukeboxes.containsKey(block.getLocation())) {
            ejectCustomDisc(block, null);
        }
    }

    public void stopAll() {
        for (Location loc : new HashSet<>(activeJukeboxes.keySet())) {
            Block block = loc.getBlock();
            if (block.getType() == Material.JUKEBOX) ejectCustomDisc(block, null);
            else activeJukeboxes.remove(loc);
        }
    }
}
