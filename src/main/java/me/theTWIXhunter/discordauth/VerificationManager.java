package me.theTWIXhunter.discordauth;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Manages pending verifications and handles verification flow.
 */
public class VerificationManager {
    private final DiscordAuthPlugin plugin;
    private final DiscordService discordService;
    private final PlayerDataManager dataManager;
    private final Map<UUID, Verification> pending = new HashMap<>();
    private final Random random = new Random();

    public VerificationManager(DiscordAuthPlugin plugin, DiscordService discordService, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.discordService = discordService;
        this.dataManager = dataManager;
    }

    /**
     * Create a new verification for the given player and Discord ID.
     * Generates a 4-digit code and sends it via DM.
     * When adding a NEW Discord ID to an existing account, the same code is also sent
     * to all already-linked Discord IDs so the owner can confirm it.
     * Returns the Verification object or null if sending DM failed.
     * Also checks max accounts per Discord limit.
     * Returns "MAX_ACCOUNTS_REACHED" special string if limit is reached.
     */
    public Verification createVerification(UUID playerUuid, String discordId, String playerIp) {
        // Check max accounts per Discord ID
        int maxAccounts = plugin.getConfig().getInt("max-accounts-per-discord", 0);
        if (maxAccounts > 0) {
            int currentCount = dataManager.countAccountsByDiscordId(discordId);

            // Don't count if this UUID is already linked to this Discord ID
            java.util.List<String> existingIds = dataManager.getDiscordIds(playerUuid);
            if (!existingIds.contains(discordId) && currentCount >= maxAccounts) {
                plugin.getLogger().warning("Discord ID " + discordId + " reached max accounts limit (" + maxAccounts + ")");
                return new Verification(playerUuid, discordId, "MAX_ACCOUNTS_REACHED", playerIp);
            }
        }

        // Detect whether this is a new link or re-authentication
        java.util.List<String> existingIds = dataManager.getDiscordIds(playerUuid);
        boolean isNewLink = !existingIds.isEmpty() && !existingIds.contains(discordId);

        String code = String.format("%04d", random.nextInt(10000));
        Verification v = new Verification(playerUuid, discordId, code, playerIp, isNewLink);

        // Get player name
        org.bukkit.OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(playerUuid);
        String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerUuid.toString();

        // Choose embed type
        boolean isFirstTime = existingIds.isEmpty();
        String embedType = isFirstTime ? "first-join-embed" : "verification-embed";

        Map<String, String> replacements = new HashMap<>();
        replacements.put("code", code);
        replacements.put("player", playerName);
        replacements.put("server", plugin.getConfig().getString("server-name", "Minecraft Server"));

        Map<String, Object> embedData = plugin.getLanguageManager().getDiscordEmbed(embedType, replacements);
        String title = (String) embedData.get("title");
        String description = (String) embedData.get("description");
        int color = (int) embedData.get("color");

        // Send code to the target Discord ID
        boolean sent = discordService.sendDirectMessageWithEmbed(discordId, title, description, color);

        if (sent) {
            // Also send the same code to all OTHER already-linked Discord IDs
            for (String otherId : existingIds) {
                if (!otherId.equals(discordId)) {
                    discordService.sendDirectMessageWithEmbed(otherId, title, description, color);
                }
            }

            pending.put(playerUuid, v);
            plugin.getLogger().info("Created verification for " + playerName + " (" + playerUuid + ") with Discord ID " + discordId
                + " (first-time: " + isFirstTime + ", new-link: " + isNewLink + ", sent to " + (existingIds.size() + 1) + " account(s))");
            return v;
        } else {
            plugin.getLogger().warning("Failed to send verification DM to Discord ID " + discordId);
            return null;
        }
    }

    /**
     * Get a pending verification for the player.
     */
    public Verification getPending(UUID playerUuid) {
        return pending.get(playerUuid);
    }

    /**
     * Remove a pending verification (on success or timeout).
     */
    public void removePending(UUID playerUuid) {
        pending.remove(playerUuid);
    }

    /**
     * Verify the code entered by the player. Returns true if correct.
     * If {@code isNewLink}, appends the Discord ID to the player's existing list.
     * For re-auth, the Discord ID list is preserved and only the IP is updated.
     * When forceVerify is set, the saved IP is kept as "LOGGED_OUT" so every login still requires 2FA.
     */
    public boolean verifyCode(UUID playerUuid, String enteredCode) {
        Verification v = pending.get(playerUuid);
        if (v == null) {
            return false;
        }
        if (v.code.equals(enteredCode)) {
            pending.remove(playerUuid);

            // Determine save IP: if forceVerify is active, don't cache the IP
            String saveIp = dataManager.isForceVerify(playerUuid) ? "LOGGED_OUT" : v.ip;

            PlayerDataManager.PlayerRecord existing = dataManager.getRecord(playerUuid);
            boolean forceVerify = existing != null && existing.forceVerify;

            if (v.isNewLink) {
                // Add the new Discord ID to the existing list
                java.util.List<String> ids = existing != null
                    ? new java.util.ArrayList<>(existing.discordIds)
                    : new java.util.ArrayList<>();
                if (!ids.contains(v.discordId)) ids.add(v.discordId);
                dataManager.setRecord(new PlayerDataManager.PlayerRecord(playerUuid, ids, saveIp, forceVerify));
            } else {
                // Re-auth or first link: keep existing list (or create new single-ID list)
                java.util.List<String> ids = (existing != null && !existing.discordIds.isEmpty())
                    ? new java.util.ArrayList<>(existing.discordIds)
                    : java.util.Collections.singletonList(v.discordId);
                // Ensure the verified ID is in the list
                if (!ids.contains(v.discordId)) ids = java.util.Collections.singletonList(v.discordId);
                dataManager.setRecord(new PlayerDataManager.PlayerRecord(playerUuid, ids, saveIp, forceVerify));
            }

            // Get player name
            org.bukkit.OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(playerUuid);
            String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerUuid.toString();

            plugin.getLogger().info("Player " + playerName + " (" + playerUuid + ") verified successfully"
                + (v.isNewLink ? " — new Discord ID added: " + v.discordId : ""));

            return true;
        }
        return false;
    }
}
