package me.theTWIXhunter.discordauth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

import net.kyori.adventure.text.format.NamedTextColor;
import java.util.ArrayList;

import java.util.List;

/**
 * Handles /discordauth commands (reload, etc.)
 */
public class DiscordAuthCommand implements CommandExecutor, TabCompleter {
    private final DiscordAuthPlugin plugin;
    private final PlayerDataManager dataManager;
    private final LanguageManager lang;

    public DiscordAuthCommand(DiscordAuthPlugin plugin, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof org.bukkit.entity.Player player) {
                // Open the account management dashboard
                plugin.getDialogManager().showMainDashboardDialog(player);
                return true;
            }
            // Console or command block: print text help
            sender.sendMessage(Component.text("DiscordAuth v" + plugin.getDescription().getVersion(), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Commands:", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  /discordauth list - List linked Discord accounts", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  /discordauth unlink [player] - Unlink Discord account", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  /discordauth logout [player] - Logout (require re-verification)", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  /discordauth reload - Reload configuration (admin)", NamedTextColor.GRAY));
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "version":
            case "about":
                sender.sendMessage(Component.text("\u2550".repeat(35), NamedTextColor.GOLD));
                sender.sendMessage(Component.text("DiscordAuth v" + plugin.getDescription().getVersion(), NamedTextColor.GOLD)
                    .append(Component.text("  by theTWIXhunter", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text("Website: ", NamedTextColor.GRAY)
                    .append(Component.text("https://thetwixhunter.nekoweb.org/discordauth/", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl("https://thetwixhunter.nekoweb.org/discordauth/"))));
                sender.sendMessage(Component.text("\u2550".repeat(35), NamedTextColor.GOLD));
                return true;

            case "reload":
                if (!sender.hasPermission("discordauth.admin")) {
                    sender.sendMessage(lang.getComponent("no-permission"));
                    return true;
                }

                // Reload config.yml
                plugin.reloadConfig();
                
                // Reload language files
                lang.loadLanguage();
                
                // Reload playerdata.yml
                dataManager.load();
                
                sender.sendMessage(lang.getComponent("reload-success"));
                plugin.getLogger().info(sender.getName() + " reloaded DiscordAuth configuration");
                return true;

            case "unlink":
                // Allow admin to unlink other players
                if (args.length >= 2 && sender.hasPermission("discordauth.admin")) {
                    String targetName = args[1];
                    org.bukkit.OfflinePlayer targetPlayer = plugin.getServer().getOfflinePlayer(targetName);
                    
                    if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
                        sender.sendMessage(lang.getComponent("player-not-found"));
                        return true;
                    }
                    
                    PlayerDataManager.PlayerRecord targetRecord = dataManager.getRecord(targetPlayer.getUniqueId());
                    if (targetRecord == null || targetRecord.discordId == null) {
                        sender.sendMessage(Component.text(targetPlayer.getName() + " doesn't have a linked Discord account.", NamedTextColor.RED));
                        return true;
                    }
                    
                    // Remove player data
                    dataManager.removeRecord(targetPlayer.getUniqueId());
                    
                    // Send feedback with placeholder replacement
                    java.util.Map<String, String> replacements = new java.util.HashMap<>();
                    replacements.put("player", targetPlayer.getName());
                    sender.sendMessage(lang.getComponent("unlink-target-success", replacements));
                    plugin.getLogger().info(sender.getName() + " unlinked " + targetPlayer.getName() + "'s Discord account");
                    
                    // Notify online player
                    if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
                        targetPlayer.getPlayer().sendMessage(Component.text("An administrator has unlinked your Discord account.", NamedTextColor.YELLOW));
                    }
                    
                    return true;
                }
                
                // Self unlink
                if (!(sender instanceof org.bukkit.entity.Player)) {
                    sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
                    return true;
                }

                org.bukkit.entity.Player player = (org.bukkit.entity.Player) sender;
                PlayerDataManager.PlayerRecord record = dataManager.getRecord(player.getUniqueId());
                
                if (record == null || record.discordId == null) {
                    sender.sendMessage(Component.text("You don't have a linked Discord account.", NamedTextColor.RED));
                    return true;
                }

                // Remove player data
                dataManager.removeRecord(player.getUniqueId());
                sender.sendMessage(lang.getComponent("unlink-success"));
                plugin.getLogger().info(player.getName() + " unlinked their Discord account");
                return true;

            case "logout":
                // Allow admin to logout other players
                if (args.length >= 2 && sender.hasPermission("discordauth.admin")) {
                    String targetName = args[1];
                    org.bukkit.OfflinePlayer targetPlayer = plugin.getServer().getOfflinePlayer(targetName);
                    
                    if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
                        sender.sendMessage(lang.getComponent("player-not-found"));
                        return true;
                    }
                    
                    PlayerDataManager.PlayerRecord targetRecord = dataManager.getRecord(targetPlayer.getUniqueId());
                    if (targetRecord == null || targetRecord.discordId == null) {
                        sender.sendMessage(Component.text(targetPlayer.getName() + " is not verified yet.", NamedTextColor.RED));
                        return true;
                    }
                    
                    // Invalidate IP to force re-verification
                    dataManager.updateIp(targetPlayer.getUniqueId(), "LOGGED_OUT");

                    // Send feedback with placeholder replacement
                    java.util.Map<String, String> replacements = new java.util.HashMap<>();
                    replacements.put("player", targetPlayer.getName());
                    sender.sendMessage(lang.getComponent("logout-target-success", replacements));
                    plugin.getLogger().info(sender.getName() + " logged out " + targetPlayer.getName());

                    // Kick online player so they must re-verify on rejoin
                    if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
                        targetPlayer.getPlayer().kick(Component.text(
                            "You have been logged out by an administrator.\nPlease rejoin to re-verify.",
                            NamedTextColor.YELLOW));
                    }

                    return true;
                }
                
                // Self logout
                if (!(sender instanceof org.bukkit.entity.Player)) {
                    sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
                    return true;
                }

                org.bukkit.entity.Player logoutPlayer = (org.bukkit.entity.Player) sender;
                PlayerDataManager.PlayerRecord logoutRecord = dataManager.getRecord(logoutPlayer.getUniqueId());

                if (logoutRecord == null) {
                    sender.sendMessage(Component.text("You are not verified yet.", NamedTextColor.RED));
                    return true;
                }

                // Invalidate IP to force re-verification, then kick
                dataManager.updateIp(logoutPlayer.getUniqueId(), "LOGGED_OUT");
                plugin.getLogger().info(logoutPlayer.getName() + " logged out");
                logoutPlayer.kick(lang.getComponent("logout-success"));
                return true;

            case "link":
                if (!sender.hasPermission("discordauth.admin")) {
                    sender.sendMessage(lang.getComponent("no-permission"));
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage(Component.text("Usage: /discordauth link <player> <discordID>", NamedTextColor.RED));
                    return true;
                }

                String targetName = args[1];
                String discordId = args[2];

                // Validate Discord ID format
                if (!discordId.matches("\\d{17,20}")) {
                    sender.sendMessage(Component.text("Invalid Discord ID format. Must be 17-20 digits.", NamedTextColor.RED));
                    return true;
                }

                // Get target player (online or offline)
                org.bukkit.OfflinePlayer targetPlayer = plugin.getServer().getOfflinePlayer(targetName);
                
                if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
                    sender.sendMessage(lang.getComponent("player-not-found"));
                    return true;
                }

                // Get current IP if online, otherwise use "ADMIN_LINKED"
                String currentIp = "ADMIN_LINKED";
                if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
                    java.net.InetSocketAddress addr = targetPlayer.getPlayer().getAddress();
                    currentIp = addr != null ? addr.getAddress().getHostAddress() : "ADMIN_LINKED";
                }

                // Create/update the record
                PlayerDataManager.PlayerRecord newRecord = new PlayerDataManager.PlayerRecord(
                    targetPlayer.getUniqueId(),
                    discordId,
                    currentIp
                );
                dataManager.setRecord(newRecord);

                sender.sendMessage(Component.text("Successfully linked " + targetPlayer.getName() + " to Discord ID " + discordId, NamedTextColor.GREEN));
                plugin.getLogger().info(sender.getName() + " linked " + targetPlayer.getName() + " to Discord ID " + discordId);
                
                // Notify online player
                if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
                    targetPlayer.getPlayer().sendMessage(Component.text("An administrator has linked your account to Discord.", NamedTextColor.GREEN));
                }
                
                return true;

            case "list":
                if (!(sender instanceof org.bukkit.entity.Player)) {
                    sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
                    return true;
                }

                org.bukkit.entity.Player listPlayer = (org.bukkit.entity.Player) sender;
                PlayerDataManager.PlayerRecord listRecord = dataManager.getRecord(listPlayer.getUniqueId());
                
                if (listRecord == null || listRecord.discordId == null) {
                    sender.sendMessage(Component.text("You don't have a linked Discord account.", NamedTextColor.RED));
                    return true;
                }

                // Show player's Discord ID
                sender.sendMessage(Component.text("═══════════════════════════", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Discord Account Information", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("═══════════════════════════", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Discord ID: ", NamedTextColor.GRAY).append(Component.text(listRecord.discordId, NamedTextColor.WHITE)));
                
                // Show all Minecraft accounts linked to this Discord ID
                java.util.List<String> linkedAccounts = dataManager.getPlayerNamesByDiscordId(listRecord.discordId);
                sender.sendMessage(Component.text("Linked Minecraft Accounts: ", NamedTextColor.GRAY).append(Component.text(linkedAccounts.size() + "", NamedTextColor.WHITE)));
                for (int i = 0; i < linkedAccounts.size(); i++) {
                    String accountName = linkedAccounts.get(i);
                    NamedTextColor color = accountName.equals(listPlayer.getName()) ? NamedTextColor.GREEN : NamedTextColor.GRAY;
                    sender.sendMessage(Component.text("  " + (i + 1) + ". ", NamedTextColor.DARK_GRAY).append(Component.text(accountName, color)));
                }
                sender.sendMessage(Component.text("═══════════════════════════", NamedTextColor.GOLD));
                
                return true;

            case "verify":
                if (!sender.hasPermission("discordauth.admin")) {
                    sender.sendMessage(lang.getComponent("no-permission"));
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage(Component.text("Usage: /discordauth verify <player> <discordID>", NamedTextColor.RED));
                    return true;
                }

                String verifyTargetName = args[1];
                String verifyDiscordId = args[2];

                // Validate Discord ID format
                if (!verifyDiscordId.matches("\\d{17,20}")) {
                    sender.sendMessage(Component.text("Invalid Discord ID format. Must be 17-20 digits.", NamedTextColor.RED));
                    return true;
                }

                // Get target player (online or offline)
                org.bukkit.OfflinePlayer verifyTargetPlayer = plugin.getServer().getOfflinePlayer(verifyTargetName);
                
                if (!verifyTargetPlayer.hasPlayedBefore() && !verifyTargetPlayer.isOnline()) {
                    sender.sendMessage(lang.getComponent("player-not-found"));
                    return true;
                }

                // Get current IP if online, otherwise use "ADMIN_VERIFIED"
                String verifyCurrentIp = "ADMIN_VERIFIED";
                if (verifyTargetPlayer.isOnline() && verifyTargetPlayer.getPlayer() != null) {
                    java.net.InetSocketAddress addr = verifyTargetPlayer.getPlayer().getAddress();
                    verifyCurrentIp = addr != null ? addr.getAddress().getHostAddress() : "ADMIN_VERIFIED";
                }

                // Create/update the record
                PlayerDataManager.PlayerRecord verifyNewRecord = new PlayerDataManager.PlayerRecord(
                    verifyTargetPlayer.getUniqueId(),
                    verifyDiscordId,
                    verifyCurrentIp
                );
                dataManager.setRecord(verifyNewRecord);

                sender.sendMessage(Component.text("Successfully verified " + verifyTargetPlayer.getName() + " with Discord ID " + verifyDiscordId, NamedTextColor.GREEN));
                plugin.getLogger().info(sender.getName() + " force-verified " + verifyTargetPlayer.getName() + " with Discord ID " + verifyDiscordId);
                
                // Notify online player
                if (verifyTargetPlayer.isOnline() && verifyTargetPlayer.getPlayer() != null) {
                    verifyTargetPlayer.getPlayer().sendMessage(Component.text("An administrator has verified your account.", NamedTextColor.GREEN));
                }
                
                return true;

            case "unlinkall":
                if (!sender.hasPermission("discordauth.admin")) {
                    sender.sendMessage(lang.getComponent("no-permission"));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /discordauth unlinkall <discordID>", NamedTextColor.RED));
                    return true;
                }

                String unlinkAllDiscordId = args[1];

                // Validate Discord ID format
                if (!unlinkAllDiscordId.matches("\\d{17,20}")) {
                    sender.sendMessage(Component.text("Invalid Discord ID format. Must be 17-20 digits.", NamedTextColor.RED));
                    return true;
                }

                // Get all accounts linked to this Discord ID
                java.util.List<String> accountsToUnlink = dataManager.getPlayerNamesByDiscordId(unlinkAllDiscordId);
                
                if (accountsToUnlink.isEmpty()) {
                    sender.sendMessage(Component.text("No accounts found linked to Discord ID " + unlinkAllDiscordId, NamedTextColor.RED));
                    return true;
                }

                // Unlink all accounts
                int unlinkedCount = 0;
                for (String accountName : accountsToUnlink) {
                    org.bukkit.OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(accountName);
                    if (offlinePlayer != null) {
                        dataManager.removeRecord(offlinePlayer.getUniqueId());
                        unlinkedCount++;
                        
                        // Notify if online
                        if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
                            offlinePlayer.getPlayer().sendMessage(Component.text("An administrator has unlinked all accounts from your Discord.", NamedTextColor.YELLOW));
                        }
                    }
                }

                sender.sendMessage(Component.text("Successfully unlinked " + unlinkedCount + " account(s) from Discord ID " + unlinkAllDiscordId, NamedTextColor.GREEN));
                sender.sendMessage(Component.text("Unlinked accounts: " + String.join(", ", accountsToUnlink), NamedTextColor.GRAY));
                plugin.getLogger().info(sender.getName() + " unlinked all accounts from Discord ID " + unlinkAllDiscordId + ": " + String.join(", ", accountsToUnlink));
                
                return true;

            default:
                sender.sendMessage(Component.text("Unknown subcommand. Use /discordauth for help", NamedTextColor.RED));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.add("version");
            completions.add("about");
            completions.add("list");
            completions.add("unlink");
            completions.add("logout");
            if (sender.hasPermission("discordauth.admin")) {
                completions.add("reload");
                completions.add("link");
                completions.add("verify");
                completions.add("unlinkall");
            }
        } else if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            if ((subCmd.equals("unlink") || subCmd.equals("logout") || subCmd.equals("link") || subCmd.equals("verify")) 
                && sender.hasPermission("discordauth.admin")) {
                // Tab complete player names
                for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
                    completions.add(p.getName());
                }
            } else if (subCmd.equals("unlinkall") && sender.hasPermission("discordauth.admin")) {
                completions.add("<discordID>");
            }
        } else if (args.length == 3) {
            String subCmd = args[0].toLowerCase();
            if ((subCmd.equals("link") || subCmd.equals("verify")) && sender.hasPermission("discordauth.admin")) {
                completions.add("<discordID>");
            }
        }
        
        return completions;
    }
}
