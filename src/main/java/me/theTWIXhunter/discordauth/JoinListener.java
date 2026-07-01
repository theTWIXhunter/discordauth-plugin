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

    private enum AuthSkipMode {
        FORCE,
        SKIP_LOGIN,
        NO_REGISTER
    }

    private enum PasswordAccessMode {
        DISCORD,
        PASSWORD,
        BOTH
    }

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
        AuthSkipMode skipMode = resolveAuthenticationSkipMode(player, record, currentIp);
        boolean forced = (record != null && record.forceVerify) || skipMode == AuthSkipMode.FORCE;

        if (!forced && skipMode == AuthSkipMode.NO_REGISTER) {
            player.sendMessage(lang.getComponent("already-verified"));
            return;
        }

        if (!forced && skipMode == AuthSkipMode.SKIP_LOGIN && record != null) {
            player.sendMessage(lang.getComponent("already-verified"));
            return;
        }

        // ── Existing registered account — needs re-authentication ─────────────
        if (record != null && record.discordId != null) {
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
     * Returns the effective authentication skip mode for this player.
     * If one or more groups match and all of them are disabled, returns null.
     * If no group matches, returns the top-level default.
     */
    private AuthSkipMode resolveAuthenticationSkipMode(Player player, PlayerDataManager.PlayerRecord record, String currentIp) {
        var cfg = plugin.getConfig();

        AuthSkipMode defaultMode = parseSkipMode(cfg.getString("authentication-skip.default-mode", "force"));
        AuthSkipMode result = null;
        boolean matchedAny = false;

        if (player.isOp()) {
            matchedAny = true;
            result = combineSkipModes(result, parseSkipMode(cfg.getString("authentication-skip.operators", "disabled")));
        }

        if (record != null && record.discordId != null && currentIp.equals(record.lastIp)) {
            matchedAny = true;
            result = combineSkipModes(result, parseSkipMode(cfg.getString("authentication-skip.known-ip", "disabled")));
        }

        AuthSkipMode specificPlayerMode = resolveSpecificPlayerMode(player);
        if (specificPlayerMode != null) {
            matchedAny = true;
            result = combineSkipModes(result, specificPlayerMode);
        }

        if (isBedrockPlayer(player)) {
            matchedAny = true;
            result = combineSkipModes(result, parseSkipMode(cfg.getString("authentication-skip.bedrock-players", "disabled")));
        }

        if (isPremiumPlayer(player)) {
            matchedAny = true;
            result = combineSkipModes(result, parseSkipMode(cfg.getString("authentication-skip.premium-users", "disabled")));
        }

        return matchedAny ? result : defaultMode;
    }

    public boolean isPasswordFeatureAllowed(Player player) {
        return canUsePasswordFeature(player);
    }

    public boolean canUseDiscordRegistration(Player player) {
        PlayerDataManager.PlayerRecord record = dataManager.getRecord(player.getUniqueId());
        return allowsDiscord(resolvePasswordAccessMode(player, record, getPlayerIp(player)));
    }

    public boolean canUsePasswordFeature(Player player) {
        PlayerDataManager.PlayerRecord record = dataManager.getRecord(player.getUniqueId());
        return allowsPassword(resolvePasswordAccessMode(player, record, getPlayerIp(player)));
    }

    private PasswordAccessMode resolvePasswordAccessMode(Player player, PlayerDataManager.PlayerRecord record, String currentIp) {
        var cfg = plugin.getConfig();

        PasswordAccessMode defaultMode = parsePasswordAccessMode(cfg.getString("authentication-skip.password-feature-default", "both"));
        PasswordAccessMode result = null;
        boolean matchedAny = false;

        if (player.isOp()) {
            matchedAny = true;
            result = combinePasswordAccessModes(result, parsePasswordAccessMode(cfg.getString("authentication-skip.operators-password-feature", "disabled")));
        }

        if (record != null && record.discordId != null && currentIp.equals(record.lastIp)) {
            matchedAny = true;
            result = combinePasswordAccessModes(result, parsePasswordAccessMode(cfg.getString("authentication-skip.known-ip-password-feature", "disabled")));
        }

        PasswordAccessMode specificPlayerMode = resolveSpecificPlayerPasswordAccessMode(player);
        if (specificPlayerMode != null) {
            matchedAny = true;
            result = combinePasswordAccessModes(result, specificPlayerMode);
        }

        if (isBedrockPlayer(player)) {
            matchedAny = true;
            result = combinePasswordAccessModes(result, parsePasswordAccessMode(cfg.getString("authentication-skip.bedrock-players-password-feature", "disabled")));
        }

        if (isPremiumPlayer(player)) {
            matchedAny = true;
            result = combinePasswordAccessModes(result, parsePasswordAccessMode(cfg.getString("authentication-skip.premium-users-password-feature", "disabled")));
        }

        return matchedAny ? result : defaultMode;
    }

    private PasswordAccessMode resolveSpecificPlayerPasswordAccessMode(Player player) {
        java.util.List<java.util.Map<?, ?>> entries = plugin.getConfig().getMapList("authentication-skip.specific-players");
        PasswordAccessMode result = null;

        for (java.util.Map<?, ?> entry : entries) {
            String configuredName = extractSpecificPlayerName(entry);
            if (configuredName == null || player.getName() == null || !player.getName().equalsIgnoreCase(configuredName)) {
                continue;
            }

            Object rawMode = entry.get("password-feature");
            result = combinePasswordAccessModes(result, parsePasswordAccessMode(rawMode != null ? rawMode.toString() : null));
        }

        return result;
    }

    private PasswordAccessMode parsePasswordAccessMode(String rawMode) {
        if (rawMode == null) {
            return null;
        }

        String normalized = rawMode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "discord" -> PasswordAccessMode.DISCORD;
            case "password" -> PasswordAccessMode.PASSWORD;
            case "both" -> PasswordAccessMode.BOTH;
            case "disabled", "default", "" -> null;
            default -> null;
        };
    }

    private PasswordAccessMode combinePasswordAccessModes(PasswordAccessMode current, PasswordAccessMode candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (current == candidate) {
            return current;
        }
        return PasswordAccessMode.BOTH;
    }

    private boolean allowsDiscord(PasswordAccessMode mode) {
        return mode == PasswordAccessMode.DISCORD || mode == PasswordAccessMode.BOTH;
    }

    private boolean allowsPassword(PasswordAccessMode mode) {
        return mode == PasswordAccessMode.PASSWORD || mode == PasswordAccessMode.BOTH;
    }

    private AuthSkipMode resolveSpecificPlayerMode(Player player) {
        java.util.List<java.util.Map<?, ?>> entries = plugin.getConfig().getMapList("authentication-skip.specific-players");
        AuthSkipMode result = null;

        for (java.util.Map<?, ?> entry : entries) {
            String configuredName = extractSpecificPlayerName(entry);
            if (configuredName == null || player.getName() == null || !player.getName().equalsIgnoreCase(configuredName)) {
                continue;
            }

            Object rawMode = entry.get("mode");
            result = combineSkipModes(result, parseSkipMode(rawMode != null ? rawMode.toString() : null));
        }

        return result;
    }

    private String extractSpecificPlayerName(java.util.Map<?, ?> entry) {
        Object name = entry.get("name");
        if (name == null) {
            name = entry.get("player");
        }
        if (name == null) {
            name = entry.get("username");
        }
        return name != null ? name.toString().trim() : null;
    }

    private AuthSkipMode parseSkipMode(String rawMode) {
        if (rawMode == null) {
            return null;
        }

        String normalized = rawMode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "force" -> AuthSkipMode.FORCE;
            case "skip-login" -> AuthSkipMode.SKIP_LOGIN;
            case "no-register" -> AuthSkipMode.NO_REGISTER;
            case "disabled", "default", "" -> null;
            default -> null;
        };
    }

    private AuthSkipMode combineSkipModes(AuthSkipMode current, AuthSkipMode candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (current == AuthSkipMode.FORCE || candidate == AuthSkipMode.FORCE) {
            return AuthSkipMode.FORCE;
        }
        if (current == AuthSkipMode.NO_REGISTER || candidate == AuthSkipMode.NO_REGISTER) {
            return AuthSkipMode.NO_REGISTER;
        }
        return AuthSkipMode.SKIP_LOGIN;
    }

    private boolean isPremiumPlayer(Player player) {
        return player.getUniqueId().version() == 4;
    }

    private boolean isBedrockPlayer(Player player) {
        Boolean floodgate = queryFloodgateApi(player.getUniqueId());
        if (floodgate != null) {
            return floodgate;
        }

        Boolean geyser = queryGeyserApi(player.getUniqueId());
        if (geyser != null) {
            return geyser;
        }

        return false;
    }

    private Boolean queryFloodgateApi(java.util.UUID uuid) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            java.lang.reflect.Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            if (api == null) {
                return null;
            }

            java.lang.reflect.Method isFloodgatePlayer = api.getClass().getMethod("isFloodgatePlayer", java.util.UUID.class);
            Object result = isFloodgatePlayer.invoke(api, uuid);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private Boolean queryGeyserApi(java.util.UUID uuid) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            java.lang.reflect.Method apiMethod = apiClass.getMethod("api");
            Object api = apiMethod.invoke(null);
            if (api == null) {
                return null;
            }

            java.lang.reflect.Method isBedrockPlayer = api.getClass().getMethod("isBedrockPlayer", java.util.UUID.class);
            Object result = isBedrockPlayer.invoke(api, uuid);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
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
