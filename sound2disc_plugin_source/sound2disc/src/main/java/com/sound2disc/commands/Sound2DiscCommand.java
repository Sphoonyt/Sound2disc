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

    // Tracks ongoing downloads per player to prevent spam
    private final Set<UUID> processing = Collections.synchronizedSet(new HashSet<>());

    private static final String PREFIX = ChatColor.DARK_AQUA + "[" +
        ChatColor.AQUA + "Sound2Disc" +
        ChatColor.DARK_AQUA + "] " + ChatColor.RESET;

    public Sound2DiscCommand(Sound2Disc plugin) {
        this.plugin = plugin;
        this.converter = new SoundConverter(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("sound2disc.use")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give":
                handleGive(sender, args);
                break;
            case "list":
                handleList(sender);
                break;
            case "get":
                handleGet(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "pack":
                handlePack(sender);
                break;
            default:
                sendHelp(sender);
        }

        return true;
    }

    // ── /sound2disc give <url or filename> [customName] ───────────────────────

    private void handleGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "This command must be run by a player.");
            return;
        }
        Player player = (Player) sender;

        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /sound2disc give <dropbox/mediafire URL or filename>");
            player.sendMessage(PREFIX + ChatColor.GRAY + "Examples:");
            player.sendMessage(ChatColor.DARK_GRAY + "  /sound2disc give https://www.dropbox.com/s/abc123/mysong.mp3?dl=0");
            player.sendMessage(ChatColor.DARK_GRAY + "  /sound2disc give https://www.mediafire.com/file/xyz/mysong.mp3/file");
            player.sendMessage(ChatColor.DARK_GRAY + "  /sound2disc give mysong.mp3  (file in plugins/Sound2Disc/sounds/)");
            return;
        }

        if (processing.contains(player.getUniqueId())) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "You already have a conversion in progress. Please wait.");
            return;
        }

        // Join remaining args in case URL has spaces (shouldn't, but just in case)
        String input = String.join("", Arrays.copyOfRange(args, 1, args.length));
        String soundKey = SoundConverter.toSoundKey(input);

        player.sendMessage(PREFIX + ChatColor.YELLOW + "⏳ Processing sound: " + ChatColor.WHITE + soundKey + " ...");
        player.sendMessage(PREFIX + ChatColor.GRAY + "(Downloading and converting to OGG — this may take a moment)");

        processing.add(player.getUniqueId());

        // Run async (download + ffmpeg are blocking)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ResourcePackManager rpm = plugin.getResourcePackManager();

                boolean alreadyExists = rpm.hasSound(soundKey);

                if (!alreadyExists) {
                    // Download + convert
                    converter.downloadAndConvert(input, soundKey);

                    // Add to resource pack
                    rpm.addSound(soundKey);

                    // Notify all online players to re-download pack
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        broadcastPackUpdate(player);
                    });
                }

                // Give disc to player (back on main thread)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ItemStack disc = JukeboxListener.createDisc(plugin, soundKey, soundKey);
                    giveOrDrop(player, disc);
                    player.sendMessage(PREFIX + ChatColor.GREEN + "✔ Disc created: " +
                        ChatColor.WHITE + soundKey);
                    if (alreadyExists) {
                        player.sendMessage(PREFIX + ChatColor.GRAY +
                            "(Sound was already converted, gave you the existing disc)");
                    }
                    player.sendMessage(PREFIX + ChatColor.YELLOW +
                        "⚠ Make sure you have the resource pack loaded to hear the sound!");
                });

            } catch (Exception e) {
                String err = e.getMessage();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(PREFIX + ChatColor.RED + "✘ Failed: " + err);
                });
                plugin.getLogger().warning("Sound2Disc conversion failed: " + err);
            } finally {
                processing.remove(player.getUniqueId());
            }
        });
    }

    // ── /sound2disc get <soundKey> ─────────────────────────────────────────────

    private void handleGet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Must be run by a player.");
            return;
        }
        Player player = (Player) sender;

        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /sound2disc get <soundKey>");
            player.sendMessage(PREFIX + ChatColor.GRAY + "Use /sound2disc list to see available sounds.");
            return;
        }

        String soundKey = args[1].toLowerCase();
        if (!plugin.getResourcePackManager().hasSound(soundKey)) {
            player.sendMessage(PREFIX + ChatColor.RED + "No sound found with key: " + soundKey);
            player.sendMessage(PREFIX + ChatColor.GRAY + "Use /sound2disc list to see available sounds.");
            return;
        }

        ItemStack disc = JukeboxListener.createDisc(plugin, soundKey, soundKey);
        giveOrDrop(player, disc);
        player.sendMessage(PREFIX + ChatColor.GREEN + "✔ Given disc for: " + ChatColor.WHITE + soundKey);
    }

    // ── /sound2disc list ───────────────────────────────────────────────────────

    private void handleList(CommandSender sender) {
        Set<String> sounds = plugin.getResourcePackManager().getRegisteredSounds();
        if (sounds.isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.GRAY + "No sounds converted yet.");
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Use /sound2disc give <URL> to convert your first sound.");
            return;
        }
        sender.sendMessage(PREFIX + ChatColor.AQUA + "Available sounds (" + sounds.size() + "):");
        for (String s : sounds) {
            sender.sendMessage(ChatColor.DARK_GRAY + "  ▪ " + ChatColor.WHITE + s +
                ChatColor.GRAY + "  →  /sound2disc get " + s);
        }
    }

    // ── /sound2disc pack ───────────────────────────────────────────────────────

    private void handlePack(CommandSender sender) {
        ResourcePackManager rpm = plugin.getResourcePackManager();
        sender.sendMessage(PREFIX + ChatColor.AQUA + "Resource Pack Info:");
        sender.sendMessage(ChatColor.GRAY + "  URL: " + ChatColor.WHITE + rpm.getPackUrl());
        sender.sendMessage(ChatColor.GRAY + "  Hash: " + ChatColor.WHITE + rpm.getPackHash());
        sender.sendMessage(ChatColor.GRAY + "  Sounds: " + ChatColor.WHITE +
            rpm.getRegisteredSounds().size());
        sender.sendMessage(PREFIX + ChatColor.YELLOW +
            "Players must download this resource pack to hear custom sounds.");
        if (sender instanceof Player) {
            // Send the pack to this player
            Player player = (Player) sender;
            player.setResourcePack(rpm.getPackUrl(), rpm.getPackHash(), true);
            player.sendMessage(PREFIX + ChatColor.GREEN + "Resource pack sent to you!");
        }
    }

    // ── /sound2disc reload ─────────────────────────────────────────────────────

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("sound2disc.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No permission.");
            return;
        }
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "Reloading...");
        plugin.reloadConfig();
        try {
            plugin.getResourcePackManager().rebuildPack();
            sender.sendMessage(PREFIX + ChatColor.GREEN + "✔ Config reloaded and resource pack rebuilt.");
        } catch (Exception e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Error rebuilding pack: " + e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void broadcastPackUpdate(Player initiator) {
        ResourcePackManager rpm = plugin.getResourcePackManager();
        String url = rpm.getPackUrl();
        String hash = rpm.getPackHash();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setResourcePack(url, hash, true);
            if (!p.equals(initiator)) {
                p.sendMessage(PREFIX + ChatColor.YELLOW +
                    "New sound added by " + initiator.getName() +
                    "! Downloading updated resource pack...");
            }
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_AQUA + "═══ " + ChatColor.AQUA + "Sound2Disc Help" +
            ChatColor.DARK_AQUA + " ═══");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc give <URL>" +
            ChatColor.GRAY + " — Download URL and get a custom disc");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc get <name>" +
            ChatColor.GRAY + " — Get a disc for an already-converted sound");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc list" +
            ChatColor.GRAY + " — List all available sounds");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc pack" +
            ChatColor.GRAY + " — Show resource pack URL & send it to yourself");
        sender.sendMessage(ChatColor.AQUA + "/sound2disc reload" +
            ChatColor.GRAY + " — Reload config");
        sender.sendMessage(ChatColor.DARK_GRAY + "Supported URL sources: Dropbox, MediaFire, direct links");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStart(Arrays.asList("give", "get", "list", "pack", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("get")) {
            return filterStart(new ArrayList<>(plugin.getResourcePackManager().getRegisteredSounds()), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterStart(List<String> list, String prefix) {
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) result.add(s);
        }
        return result;
    }
}
