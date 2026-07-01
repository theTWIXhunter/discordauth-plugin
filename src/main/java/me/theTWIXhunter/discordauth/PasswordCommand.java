package me.theTWIXhunter.discordauth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles /password commands for backup password management.
 */
public class PasswordCommand implements CommandExecutor, TabCompleter {
    private final DiscordAuthPlugin plugin;
    private final PlayerDataManager dataManager;
    private final BackupPasswordManager passwordManager;
    private final DiscordService discordService;
    private final JoinListener joinListener;
    private final LanguageManager lang;
    private final SecureRandom random = new SecureRandom();

    public PasswordCommand(DiscordAuthPlugin plugin, PlayerDataManager dataManager, 
                          BackupPasswordManager passwordManager, DiscordService discordService,
                          JoinListener joinListener) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.passwordManager = passwordManager;
        this.discordService = discordService;
        this.joinListener = joinListener;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle admin password reset from console
        if (!(sender instanceof Player)) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("forgot")) {
                return handleForgotAdmin(sender, args[1]);
            }
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return true;
        }

        Player player = (Player) sender;

        if (joinListener != null && !joinListener.isPasswordFeatureAllowed(player)) {
            sender.sendMessage(Component.text("Password features are disabled for your account.", NamedTextColor.RED));
            return true;
        }

        // Check if player is verified
        PlayerDataManager.PlayerRecord record = dataManager.getRecord(player.getUniqueId());
        if (record == null || record.discordId == null) {
            sender.sendMessage(Component.text("You must link your Discord account before managing passwords.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "set":
                return handleSet(player, args, record);
            case "change":
                return handleChange(player, args);
            case "forgot":
                // Check if admin is resetting for another player
                if (args.length >= 2 && sender.hasPermission("discordauth.admin")) {
                    return handleForgotAdmin(sender, args[1]);
                }
                return handleForgot(player, record);
            default:
                showHelp(player);
                return true;
        }
    }

    private void showHelp(Player player) {
        player.sendMessage(Component.text("═══════════════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Password Management", NamedTextColor.GOLD));
        player.sendMessage(Component.text("═══════════════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/password set <password> <confirm> - Set a backup password", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/password change <old> <new> <confirm> - Change password", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/password forgot - Reset via Discord", NamedTextColor.GRAY));
        if (player.hasPermission("discordauth.admin")) {
            player.sendMessage(Component.text("/password forgot <player> - Reset player's password (Admin)", NamedTextColor.YELLOW));
        }
        player.sendMessage(Component.text("═══════════════════════════════════════", NamedTextColor.GOLD));
    }

    private boolean handleSet(Player player, String[] args, PlayerDataManager.PlayerRecord record) {
        if (passwordManager.hasPassword(player.getUniqueId())) {
            player.sendMessage(Component.text("You already have a password set!", NamedTextColor.RED));
            player.sendMessage(Component.text("Use /password change to update it.", NamedTextColor.GRAY));
            return true;
        }

        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /password set <password> <confirm_password>", NamedTextColor.RED));
            return true;
        }

        String password = args[1];
        String confirm = args[2];

        // Validate password strength
        if (password.length() < 8) {
            player.sendMessage(Component.text("Password must be at least 8 characters long.", NamedTextColor.RED));
            return true;
        }

        // Check if passwords match
        if (!password.equals(confirm)) {
            player.sendMessage(Component.text("Passwords do not match!", NamedTextColor.RED));
            return true;
        }

        // Set the password
        passwordManager.setPassword(player.getUniqueId(), password);
        player.sendMessage(Component.text("✓ Backup password set successfully!", NamedTextColor.GREEN));
        player.sendMessage(Component.text("You can now use this password to verify if you lose Discord access.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Keep your password safe and secret!", NamedTextColor.YELLOW));
        
        plugin.getLogger().info(player.getName() + " set a backup password");
        return true;
    }

    private boolean handleChange(Player player, String[] args) {
        if (!passwordManager.hasPassword(player.getUniqueId())) {
            player.sendMessage(Component.text("You don't have a password set!", NamedTextColor.RED));
            player.sendMessage(Component.text("Use /password set to create one.", NamedTextColor.GRAY));
            return true;
        }

        if (args.length < 4) {
            player.sendMessage(Component.text("Usage: /password change <old_password> <new_password> <confirm_new_password>", NamedTextColor.RED));
            return true;
        }

        String oldPassword = args[1];
        String newPassword = args[2];
        String confirm = args[3];

        // Verify old password
        if (!passwordManager.verifyPassword(player.getUniqueId(), oldPassword)) {
            player.sendMessage(Component.text("✗ Incorrect current password!", NamedTextColor.RED));
            return true;
        }

        // Validate new password strength
        if (newPassword.length() < 8) {
            player.sendMessage(Component.text("New password must be at least 8 characters long.", NamedTextColor.RED));
            return true;
        }

        // Check if new passwords match
        if (!newPassword.equals(confirm)) {
            player.sendMessage(Component.text("New passwords do not match!", NamedTextColor.RED));
            return true;
        }

        // Check if new password is different from old
        if (oldPassword.equals(newPassword)) {
            player.sendMessage(Component.text("New password must be different from current password!", NamedTextColor.RED));
            return true;
        }

        // Update the password
        passwordManager.setPassword(player.getUniqueId(), newPassword);
        player.sendMessage(Component.text("✓ Password changed successfully!", NamedTextColor.GREEN));
        
        plugin.getLogger().info(player.getName() + " changed their backup password");
        return true;
    }

    private boolean handleForgot(Player player, PlayerDataManager.PlayerRecord record) {
        // Generate a random temporary password
        String tempPassword = generateRandomPassword();
        
        // Set the temporary password
        passwordManager.setPassword(player.getUniqueId(), tempPassword);
        
        // Send to Discord using embed
        Map<String, String> replacements = new HashMap<>();
        replacements.put("player", player.getName());
        replacements.put("server", plugin.getConfig().getString("server-name", "Minecraft Server"));
        replacements.put("password", tempPassword);
        
        // Get embed data from language file
        Map<String, Object> embedData = lang.getDiscordEmbed("password-reset-embed", replacements);
        
        boolean sent = discordService.sendDirectMessageWithEmbed(
            record.discordId,
            (String) embedData.get("title"),
            (String) embedData.get("description"),
            (Integer) embedData.get("color")
        );
        
        if (sent) {
            player.sendMessage(Component.text("✓ A temporary password has been sent to your Discord!", NamedTextColor.GREEN));
            player.sendMessage(Component.text("Check your DMs and change the password immediately after logging in.", NamedTextColor.YELLOW));
            plugin.getLogger().info(player.getName() + " requested a password reset");
        } else {
            player.sendMessage(Component.text("✗ Failed to send password reset to Discord!", NamedTextColor.RED));
            player.sendMessage(Component.text("Make sure your DMs are open and try again.", NamedTextColor.GRAY));
            // Revert password change since DM failed
            passwordManager.removePassword(player.getUniqueId());
        }
        
        return true;
    }

    private boolean handleForgotAdmin(CommandSender sender, String targetPlayerName) {
        // Find the target player's UUID
        Player targetPlayer = plugin.getServer().getPlayer(targetPlayerName);
        java.util.UUID targetUuid = null;
        
        if (targetPlayer != null) {
            targetUuid = targetPlayer.getUniqueId();
        } else {
            // Try to find in dataManager
            targetUuid = dataManager.getUuidByName(targetPlayerName);
        }
        
        if (targetUuid == null) {
            sender.sendMessage(Component.text("✗ Player not found: " + targetPlayerName, NamedTextColor.RED));
            return true;
        }
        
        // Get player record
        PlayerDataManager.PlayerRecord record = dataManager.getRecord(targetUuid);
        if (record == null || record.discordId == null) {
            sender.sendMessage(Component.text("✗ Player " + targetPlayerName + " has no Discord account linked!", NamedTextColor.RED));
            return true;
        }
        
        // Check if it's a password-only account
        if (record.discordId.equals("PASSWORD_ONLY")) {
            // Generate a new password for password-only accounts
            String tempPassword = generateRandomPassword();
            passwordManager.setPassword(targetUuid, tempPassword);
            
            // If run from console, show the password
            if (!(sender instanceof Player)) {
                // Console - show password
                String consoleMessage = lang.getRawMessage("password-reset-admin-passwordonly-console")
                    .replace("{player}", targetPlayerName)
                    .replace("{password}", tempPassword);
                sender.sendMessage(consoleMessage);
                plugin.getLogger().info(sender.getName() + " reset password for " + targetPlayerName + " (password-only account)");
                
                // Notify target player if online
                if (targetPlayer != null) {
                    targetPlayer.sendMessage(lang.getComponent("admin-password-reset-notification-no-discord"));
                }
            } else {
                // In-game player - suggest console
                Map<String, String> replacements = new HashMap<>();
                replacements.put("player", targetPlayerName);
                sender.sendMessage(lang.getComponent("password-reset-password-only-ingame", replacements));
            }
            return true;
        }
        
        // Generate a random temporary password
        String tempPassword = generateRandomPassword();
        
        // Set the temporary password
        passwordManager.setPassword(targetUuid, tempPassword);
        
        // Send to Discord using embed
        Map<String, String> replacements = new HashMap<>();
        replacements.put("player", targetPlayerName);
        replacements.put("server", plugin.getConfig().getString("server-name", "Minecraft Server"));
        replacements.put("password", tempPassword);
        
        // Get embed data from language file
        Map<String, Object> embedData = lang.getDiscordEmbed("password-reset-embed", replacements);
        
        boolean sent = discordService.sendDirectMessageWithEmbed(
            record.discordId,
            (String) embedData.get("title"),
            (String) embedData.get("description"),
            (Integer) embedData.get("color")
        );
        
        if (sent) {
            // Show password in console only (for security)
            if (!(sender instanceof Player)) {
                // Console - show password with success message
                String consoleMessage = lang.getRawMessage("password-reset-admin-success-console")
                    .replace("{player}", targetPlayerName)
                    .replace("{password}", tempPassword);
                sender.sendMessage(consoleMessage);
            } else {
                // In-game OP - suggest console usage for password visibility
                sender.sendMessage(lang.getComponent("password-reset-console-tip"));
            }
            
            plugin.getLogger().info(sender.getName() + " reset password for " + targetPlayerName);
            
            // Notify target player if online
            if (targetPlayer != null) {
                targetPlayer.sendMessage(lang.getComponent("admin-password-reset-notification"));
            }
        } else {
            // Show password in console so admin can manually provide it
            if (!(sender instanceof Player)) {
                // Console - show password with failure message
                String consoleMessage = lang.getRawMessage("password-reset-admin-failed-console")
                    .replace("{player}", targetPlayerName)
                    .replace("{password}", tempPassword);
                sender.sendMessage(consoleMessage);
            } else {
                // In-game - suggest console
                sender.sendMessage(lang.getComponent("password-reset-console-required"));
            }
            
            // Don't revert password if run from console - admin needs to give it manually
            if (sender instanceof Player) {
                passwordManager.removePassword(targetUuid);
            }
        }
        
        return true;
    }

    /**
     * Generate a random password with letters, numbers, and symbols.
     */
    private String generateRandomPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            password.append(chars.charAt(random.nextInt(chars.length())));
        }
        return password.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.add("set");
            completions.add("change");
            completions.add("forgot");
        } else if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("set") || subCmd.equals("change")) {
                completions.add("<password>");
            } else if (subCmd.equals("forgot") && sender.hasPermission("discordauth.admin")) {
                // Show online player names for admins
                for (Player player : sender.getServer().getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(player.getName());
                    }
                }
            }
        } else if (args.length == 3) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("set")) {
                completions.add("<confirm_password>");
            } else if (subCmd.equals("change")) {
                completions.add("<new_password>");
            }
        } else if (args.length == 4) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("change")) {
                completions.add("<confirm_new_password>");
            }
        }
        
        return completions;
    }
}
