package com.sound2disc.listeners;

import com.sound2disc.Sound2Disc;
import com.sound2disc.managers.ResourcePackManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

public class PackListener implements Listener {

    private final Sound2Disc plugin;

    private static final String PREFIX = ChatColor.DARK_AQUA + "[" +
        ChatColor.AQUA + "Sound2Disc" + ChatColor.DARK_AQUA + "] " + ChatColor.RESET;

    public PackListener(Sound2Disc plugin) {
        this.plugin = plugin;
    }

    /**
     * When a player joins, send them the resource pack automatically
     * so they get the Minecraft "Accept / Decline" prompt.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ResourcePackManager rpm = plugin.getResourcePackManager();

        String url = rpm.getPackUrl();
        String hash = rpm.getPackHash();

        if (url == null || url.equals("(not available)")) return;

        // Short delay so the player fully loads in before receiving the pack prompt
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendPack(player);
            }
        }, 40L); // 2 seconds
    }

    /**
     * Listen for resource pack status so we can inform the player if they decline.
     */
    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED:
                player.sendMessage(PREFIX + ChatColor.GREEN + "Resource pack loaded! Custom disc sounds are ready.");
                break;
            case DECLINED:
                player.sendMessage(PREFIX + ChatColor.RED + "You declined the resource pack.");
                player.sendMessage(PREFIX + ChatColor.YELLOW + "Custom disc sounds won't play without it.");
                player.sendMessage(PREFIX + ChatColor.GRAY + "Run " + ChatColor.AQUA + "/sound2disc pack" +
                    ChatColor.GRAY + " to get the prompt again.");
                break;
            case FAILED_DOWNLOAD:
                player.sendMessage(PREFIX + ChatColor.RED + "Resource pack download failed.");
                player.sendMessage(PREFIX + ChatColor.GRAY + "Run " + ChatColor.AQUA + "/sound2disc pack" +
                    ChatColor.GRAY + " to try again.");
                break;
            default:
                break;
        }
    }

    public void sendPack(Player player) {
        ResourcePackManager rpm = plugin.getResourcePackManager();
        String url = rpm.getPackUrl();
        String hash = rpm.getPackHash();

        if (url == null || url.equals("(not available)")) {
            player.sendMessage(PREFIX + ChatColor.RED + "Resource pack not available yet. Try again in a moment.");
            return;
        }

        // This sends the native Minecraft "Server Resource Pack" accept/decline prompt
        player.setResourcePack(url, hash, true,
            net.kyori.adventure.text.Component.text(
                ChatColor.stripColor(PREFIX) + "Sound2Disc needs this pack for custom disc sounds."
            )
        );
    }
}
