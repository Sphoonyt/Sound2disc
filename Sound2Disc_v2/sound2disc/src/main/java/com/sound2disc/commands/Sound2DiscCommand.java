package com.sound2disc.commands;

import com.sound2disc.Sound2Disc;
import com.sound2disc.listeners.JukeboxListener;
import com.sound2disc.managers.ResourcePackManager;
import com.sound2disc.utils.SoundConverter;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class Sound2DiscCommand implements CommandExecutor, TabCompleter {

    private final Sound2Disc plugin;
    private final SoundConverter converter;
    private final Set<UUID> processing = Collections.synchronizedSet(new HashSet<>());

    private static final String PREFIX = ChatColor.DARK_AQUA + "[" +
        ChatColor.AQUA + "Sound2Disc" + ChatColor.DARK_AQUA + "] " + ChatColor.RESET;

    public Sound2DiscCommand(Sound2Disc plugin) {
        this.plugin = plugin;
        this.converter = new SoundConverter(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("sound2disc.use")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length == 0) { sendHelp(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "give":   handleGive(sender, args);   break;
            case "list":   handleList(sender);          break;
            case "get":    handleGet(sender, args);     break;
            case "reload": handleReload(sender);        break;
            case "pack":   handlePack(sender);          break;
            default:       sendHelp(sender);
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(PREFIX + ChatColor.RED + "Must be run by a player."); return; }
        Player player = (Player) sender;
        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /sound2disc give <URL or filename>");
            player.sendMessage(ChatColor.GRAY + "  Supported: Dropbox, MediaFire, direct URL, local file");
            return;
        }
        if (processing.contains(player.getUniqueId())) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Already processing, please wait.");
            return;
        }

        String input = String.join("", Arrays.copyOfRange(args, 1, args.length));
        String soundKey = SoundConverter.toSoundKey(input);
        player.sendMessage(PREFIX + ChatColor.YELLOW + "Processing: " + ChatColor.WHITE + soundKey);
        player.sendMessage(PREFIX + ChatColor.GRAY + "This may take a moment...");
        processing.add(player.getUniqueId());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ResourcePackManager rpm = plugin.getResourcePackManager();
                boolean alreadyExists = rpm.hasSound(soundKey);
                if (!alreadyExists) {
                    converter.downloadAndConvert(input, soundKey);
                    rpm.addSound(soundKey);
                    // Re-send updated pack to all online players
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (!p.equals(player))
                                p.sendMessage(PREFIX + ChatColor.YELLOW + "New sound added by " + player.getName() + "! Updating resource pack...");
                            plugin.getPackListener().sendPack(p);
                        }
                    });
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    giveOrDrop(player, JukeboxListener.createDisc(plugin, soundKey, soundKey));
                    player.sendMessage(PREFIX + ChatColor.GREEN + "Disc created: " + ChatColor.WHITE + soundKey);
                    if (alreadyExists)
                        player.sendMessage(PREFIX + ChatColor.GRAY + "(Sound already existed - gave you the disc)");
                });
            } catch (Exception e) {
                String err = e.getMessage();
                Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(PREFIX + ChatColor.RED + "Failed: " + err));
                plugin.getLogger().warning("Conversion failed: " + err);
            } finally {
                processing.remove(player.getUniqueId());
            }
        });
    }

    private void handleGet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(PREFIX + ChatColor.RED + "Must be run by a player."); return; }
        Player player = (Player) sender;
        if (args.length < 2) { player.sendMessage(PREFIX + ChatColor.RED + "Usage: /sound2disc get <soundKey>"); return; }
        String soundKey = args[1].toLowerCase();
        if (!plugin.getResourcePackManager().hasSound(soundKey)) {
            player.sendMessage(PREFIX + ChatColor.RED + "No sound found: " + soundKey);
            player.sendMessage(PREFIX + ChatColor.GRAY + "Use /sound2disc list to see available sounds.");
            return;
        }
        giveOrDrop(player, JukeboxListener.createDisc(plugin, soundKey, soundKey));
        player.sendMessage(PREFIX + ChatColor.GREEN + "Given disc for: " + soundKey);
    }

    private void handleList(CommandSender sender) {
        Set<String> sounds = plugin.getResourcePackManager().getRegisteredSounds();
        if (sounds.isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.GRAY + "No sounds yet. Use /sound2disc give <URL>");
            return;
        }
        sender.sendMessage(PREFIX + ChatColor.AQUA + "Available sounds (" + sounds.size() + "):");
        for (String s : sounds)
            sender.sendMessage(ChatColor.DARK_GRAY + "  ▪ " + ChatColor.WHITE + s +
                ChatColor.GRAY + "  →  /sound2disc get " + s);
    }

    private void handlePack(CommandSender sender) {
        if (!(sender instanceof Player)) {
            ResourcePackManager rpm = plugin.getResourcePackManager();
            sender.sendMessage(PREFIX + "Pack URL: " + rpm.getPackUrl());
            sender.sendMessage(PREFIX + "Hash: " + rpm.getPackHash());
            return;
        }
        Player player = (Player) sender;
        // Send the native Minecraft resource pack prompt — no browser needed
        plugin.getPackListener().sendPack(player);
        player.sendMessage(PREFIX + ChatColor.GREEN + "Resource pack prompt sent! Accept it to hear custom sounds.");
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("sound2disc.admin")) { sender.sendMessage(PREFIX + ChatColor.RED + "No permission."); return; }
        plugin.reloadConfig();
        plugin.getResourcePackManager().clearUrlCache();
        try {
            plugin.getResourcePackManager().rebuildPack();
            sender.sendMessage(PREFIX + ChatColor.GREEN + "Reloaded and re-uploaded resource pack!");
        } catch (Exception e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Error: " + e.getMessage());
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values())
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + "Sound2Disc" + ChatColor.DARK_AQUA + " ===");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc give <URL>" + ChatColor.GRAY + " - Download and get a custom disc");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc get <name>" + ChatColor.GRAY + " - Get a disc for existing sound");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc list" + ChatColor.GRAY + " - List all sounds");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc pack" + ChatColor.GRAY + " - Send resource pack prompt to yourself");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc reload" + ChatColor.GRAY + " - Reload config");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filterStart(Arrays.asList("give", "get", "list", "pack", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("get"))
            return filterStart(new ArrayList<>(plugin.getResourcePackManager().getRegisteredSounds()), args[1]);
        return Collections.emptyList();
    }

    private List<String> filterStart(List<String> list, String prefix) {
        List<String> r = new ArrayList<>();
        for (String s : list) if (s.toLowerCase().startsWith(prefix.toLowerCase())) r.add(s);
        return r;
    }
}
