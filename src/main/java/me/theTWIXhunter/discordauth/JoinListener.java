package me.theTWIXhunter.discordauth;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.net.InetSocketAddress;

/**
 * Handles player join and chat events for Discord verification flow.
 * Also blocks all interactions for unverified players.
 */
public class JoinListener implements Listener {
    private final DiscordAuthPlugin plugin;
    private final VerificationManager verificationManager;
    private final PlayerDataManager dataManager;
    private final BackupPasswordManager passwordManager;
    private final DialogManager dialogManager;
    private final LanguageManager lang;
    private final java.util.Map<java.util.UUID, GameMode> savedGameModes = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, org.bukkit.scheduler.BukkitTask> timeoutTasks = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> failedLoginAttempts = new java.util.HashMap<>();

    public JoinListener(DiscordAuthPlugin plugin, VerificationManager verificationManager, PlayerDataManager dataManager, BackupPasswordManager passwordManager, DialogManager dialogManager) {
        this.plugin = plugin;
        this.verificationManager = verificationManager;
        this.dataManager = dataManager;
        this.passwordManager = passwordManager;
        this.dialogManager = dialogManager;
        this.lang = plugin.getLanguageManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        failedLoginAttempts.remove(player.getUniqueId());
        
        // Check if bot token is configured and valid - show error but don't kick
        if (!plugin.getDiscordService().isBotTokenValid()) {
            player.sendMessage(lang.getComponent("bot-token-not-configured"));
            return;
        }
        
        PlayerDataManager.PlayerRecord record = dataManager.getRecord(player.getUniqueId());
        String currentIp = getPlayerIp(player);
        // Force auth if server config says so, OR if the player enabled always-require-2FA
        boolean forced = isForceAuthRequired(player) || (record != null && record.forceVerify);

        // ── Skip rules (only evaluated when NOT forced) ──────────────────────
        if (!forced && canSkipAuth(player, record, currentIp)) {
            player.sendMessage(lang.getComponent("already-verified"));
            return;
        }

        // ── Existing registered account — needs re-authentication ─────────────
        if (record != null && record.discordId != null) {
            // If skip-matching-ip is disabled but IP still matches, let them in
            // (when the skip is enabled it would have fired in canSkipAuth above)
            if (!forced && currentIp.equals(record.lastIp)) {
                player.sendMessage(lang.getComponent("already-verified"));
                return;
            }
            {  // re-auth block
                // IP changed - save current gamemode and lock player down
                savedGameModes.put(player.getUniqueId(), player.getGameMode());
                player.setGameMode(GameMode.ADVENTURE);
                // Allow flight to prevent kick while frozen
                player.setAllowFlight(true);
                player.setFlying(true);

                // PASSWORD_ONLY accounts: prompt for password, never try Discord DM
                if ("PASSWORD_ONLY".equals(record.discordId)) {
                    if (passwordManager.hasPassword(player.getUniqueId())) {
                        dialogManager.showPasswordLoginDialog(player);
                    } else {
                        // No password on record — reset and show registration
                        dataManager.removeRecord(player.getUniqueId());
                        showRegistrationForm(player);
                    }
                    startVerificationTimeout(player);
                    return;
                }

                player.sendMessage(lang.getComponent("verification-code-sent"));
                
                Verification v = verificationManager.createVerification(player.getUniqueId(), record.discordId, currentIp);
                if (v != null && v.code.equals("MAX_ACCOUNTS_REACHED")) {
                    // Max accounts reached - show message and kick
                    int maxAccounts = plugin.getConfig().getInt("max-accounts-per-discord", 0);
                    java.util.List<String> names = plugin.getDataManager().getPlayerNamesByDiscordId(record.discordId);
                    StringBuilder accountsList = new StringBuilder();
                    for (int i = 0; i < names.size(); i++) {
                        accountsList.append("&7").append(i + 1).append(". &e").append(names.get(i)).append("\n");
                    }
                    
                    java.util.Map<String, String> replacements = new java.util.HashMap<>();
                    replacements.put("max", String.valueOf(maxAccounts));
                    replacements.put("accounts", accountsList.toString().trim());
                    
                    player.sendMessage(lang.getComponent("max-accounts-reached", replacements));
                    
                    String kickMsg = lang.getRawMessage("kick-max-accounts")
                        .replace("{max}", String.valueOf(maxAccounts));
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        cancelVerificationTimeout(player.getUniqueId());
                        savedGameModes.remove(player.getUniqueId());
                        player.kick(Component.text(kickMsg));
                    }, 60L); // 3 second delay
                } else if (v != null) {
                    dialogManager.showCodeVerificationDialog(player);
                    startVerificationTimeout(player);
                } else {
                    // DM failed - kick immediately for safety
                    player.sendMessage(lang.getComponent("dm-failed"));
                    String discordInvite = plugin.getConfig().getString("discord-invite", "https://discord.gg/YOUR_INVITE_CODE");
                    player.sendMessage(lang.getComponent("dm-failed-instructions"));
                    player.sendMessage(Component.text(discordInvite, NamedTextColor.AQUA));
                    
                    String kickMsg = "Failed to send verification code. Please join our Discord at " + discordInvite + " or enable DMs, then rejoin.";
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        cancelVerificationTimeout(player.getUniqueId());
                        player.kick(Component.text(kickMsg));
                    }, 40L); // 2 second delay to let them see the message
                }
                return;
            }
        }
        
        // Not registered — lock and show registration
        savedGameModes.put(player.getUniqueId(), player.getGameMode());
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        showRegistrationForm(player);
        startVerificationTimeout(player);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up timeout task when player disconnects
        cancelVerificationTimeout(event.getPlayer().getUniqueId());
        savedGameModes.remove(event.getPlayer().getUniqueId());
        failedLoginAttempts.remove(event.getPlayer().getUniqueId());
        dialogManager.clearEnteredCode(event.getPlayer().getUniqueId());
    }
    
    private void showRegistrationForm(Player player) {
        dialogManager.showAuthenticationChoiceDialog(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        // Block chat for players who haven't completed authentication yet
        if (!isVerified(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    public String getPlayerIp(Player player) {
        InetSocketAddress addr = player.getAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }

    /**
     * Restores a player's saved gamemode and removes flight after successful registration.
     * Called by DialogManager when authentication completes.
     */
    public void restorePlayer(Player player) {
        failedLoginAttempts.remove(player.getUniqueId());
        cancelVerificationTimeout(player.getUniqueId());
        GameMode originalMode = savedGameModes.remove(player.getUniqueId());
        if (originalMode != null) {
            player.setGameMode(originalMode);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
        }
        if (originalMode != GameMode.CREATIVE && originalMode != GameMode.SPECTATOR) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    /**
     * Kicks a player who failed or aborted authentication.
     * Called by DialogManager on DM failure or max-accounts exceeded.
     */
    public void kickUnverifiedPlayer(Player player) {
        String kickMsg = lang.getKickMessage();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            failedLoginAttempts.remove(player.getUniqueId());
            cancelVerificationTimeout(player.getUniqueId());
            savedGameModes.remove(player.getUniqueId());
            player.kick(Component.text(kickMsg));
        }, 1L);
    }

    /**
     * Returns the configured maximum failed login attempts before a kick.
     * A value <= 0 disables this protection.
     */
    public int getMaxLoginAttempts() {
        return plugin.getConfig().getInt("max-login-attempts", 3);
    }

    /**
     * Increments and returns the failed password-attempt counter for this player.
     */
    public int incrementFailedLoginAttempts(java.util.UUID uuid) {
        int updated = failedLoginAttempts.getOrDefault(uuid, 0) + 1;
        failedLoginAttempts.put(uuid, updated);
        return updated;
    }

    /**
     * Clears any stored failed-attempt count for this player.
     */
    public void resetFailedLoginAttempts(java.util.UUID uuid) {
        failedLoginAttempts.remove(uuid);
    }

    /**
     * Kicks a player after they exceeded the configured failed login attempt limit.
     */
    public void kickForFailedLoginAttempts(Player player) {
        String kickMsg = "Too many failed login attempts.";
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            failedLoginAttempts.remove(player.getUniqueId());
            cancelVerificationTimeout(player.getUniqueId());
            savedGameModes.remove(player.getUniqueId());
            player.kick(Component.text(kickMsg));
        }, 1L);
    }

    /**
     * Returns true if this player must authenticate regardless of any skip rules.
     * Checks force-ops and force-permission under authentication-skip.force-authentication.
     */
    private boolean isForceAuthRequired(Player player) {
        var cfg = plugin.getConfig();
        if (cfg.getBoolean("authentication-skip.force-authentication.force-ops", false)
                && player.isOp()) {
            return true;
        }
        if (cfg.getBoolean("authentication-skip.force-authentication.force-permission", false)
                && player.hasPermission("discordauth.force.login")) {
            return true;
        }
        return false;
    }

    /**
     * Returns true if any active skip rule allows this player to bypass authentication.
     * Never call this when {@link #isForceAuthRequired(Player)} is true.
     */
    private boolean canSkipAuth(Player player, PlayerDataManager.PlayerRecord record, String currentIp) {
        var cfg = plugin.getConfig();

        // skip-specific-players
        if (cfg.getBoolean("authentication-skip.skip-specific-players.enabled", false)) {
            if (cfg.getStringList("authentication-skip.skip-specific-players.players")
                    .contains(player.getName())) {
                boolean requireReg = cfg.getBoolean(
                    "authentication-skip.skip-specific-players.require-registration", false);
                if (!requireReg || (record != null && record.discordId != null)) return true;
            }
        }

        // skip-premium-accounts — Mojang UUIDs are version 4; offline-computed UUIDs are version 3
        if (cfg.getBoolean("authentication-skip.skip-premium-accounts.enabled", false)) {
            if (player.getUniqueId().version() == 4) {
                boolean requireReg = cfg.getBoolean(
                    "authentication-skip.skip-premium-accounts.require-registration", true);
                if (!requireReg || (record != null && record.discordId != null)) return true;
            }
        }

        // skip-matching-ip
        if (cfg.getBoolean("authentication-skip.skip-matching-ip.enabled", true)) {
            if (record != null && record.discordId != null && currentIp.equals(record.lastIp)
                    && !record.forceVerify) {
                return true; // require-registration is implicitly satisfied: record already exists
            }
        }

        return false;
    }

    /**
     * A player is considered verified (and allowed to interact) when they are NOT
     * sitting in the lockdown map — i.e. they either passed auth this session or were skipped.
     */
    private boolean isVerified(Player player) {
        return !savedGameModes.containsKey(player.getUniqueId());
    }

    // ========== LOCKDOWN EVENTS - Block all actions for unverified players ==========

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isVerified(event.getPlayer())) {
            // Cancel movement (but allow looking around)
            if (event.hasChangedPosition()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isVerified(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isVerified(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!isVerified(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!isVerified(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (!isVerified(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!isVerified(event.getPlayer())) {
            // Allow only basic commands like /help
            String command = event.getMessage().toLowerCase();
            if (!command.startsWith("/help")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Component.text("You must verify before using commands.", NamedTextColor.RED));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (!isVerified(player)) {
                // Prevent damage to unverified players
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Prevent unverified players from damaging entities
        if (event.getDamager() instanceof Player player) {
            if (!isVerified(player)) {
                event.setCancelled(true);
            }
        }
    }
    
    /**
     * Start the verification timeout for a player.
     * If enabled in config, kicks the player after the timeout period.
     */
    private void startVerificationTimeout(Player player) {
        int timeoutSeconds = plugin.getConfig().getInt("verification-timeout", 600);
        
        // 0 means disabled
        if (timeoutSeconds <= 0) {
            return;
        }
        
        // Cancel any existing timeout
        cancelVerificationTimeout(player.getUniqueId());
        
        // Schedule timeout task
        org.bukkit.scheduler.BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isVerified(player)) {
                player.sendMessage(lang.getComponent("verification-timeout"));
                String kickMsg = lang.getKickTimeoutMessage();
                
                // Clean up
                savedGameModes.remove(player.getUniqueId());
                timeoutTasks.remove(player.getUniqueId());
                
                player.kick(Component.text(kickMsg));
            }
        }, timeoutSeconds * 20L); // Convert seconds to ticks
        
        timeoutTasks.put(player.getUniqueId(), task);
    }
    
    /**
     * Cancel the verification timeout for a player.
     */
    private void cancelVerificationTimeout(java.util.UUID uuid) {
        org.bukkit.scheduler.BukkitTask task = timeoutTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }
}
