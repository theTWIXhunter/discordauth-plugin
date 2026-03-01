package me.theTWIXhunter.discordauth;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Simple YAML-backed storage: playerdata.yml with entries per UUID: discordId, lastIp
 */
public class PlayerDataManager {
    private final JavaPlugin plugin;
    private final File file;
    private FileConfiguration cfg;

    public PlayerDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
        load();
    }

    public synchronized void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        try {
            if (!file.exists()) file.createNewFile();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create playerdata.yml: " + e.getMessage());
        }
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save playerdata.yml: " + e.getMessage());
        }
    }

    public boolean hasPlayer(UUID uuid) {
        String base = uuid.toString();
        return cfg.contains(base + ".discordId") || cfg.contains(base + ".discordIds")
            || cfg.contains(base + ".lastIp") || cfg.contains(base + ".hash");
    }

    public PlayerRecord getRecord(UUID uuid) {
        if (!cfg.contains(uuid.toString())) {
            // Check sub-keys individually (older saves may not use a section)
            if (!hasPlayer(uuid)) return null;
        }
        String base = uuid.toString();
        String ip = cfg.getString(base + ".lastIp", null);
        boolean forceVerify = cfg.getBoolean(base + ".forceVerify", false);

        // New format: discordIds list
        java.util.List<String> discordIds = cfg.getStringList(base + ".discordIds");
        if (!discordIds.isEmpty()) {
            return new PlayerRecord(uuid, discordIds, ip, forceVerify);
        }

        // Legacy: single discordId (could be "PASSWORD_ONLY" or a real Snowflake)
        String discordId = cfg.getString(base + ".discordId", null);
        if (discordId != null) {
            if ("PASSWORD_ONLY".equals(discordId)) {
                return new PlayerRecord(uuid, java.util.Collections.emptyList(), ip, forceVerify, true);
            }
            return new PlayerRecord(uuid, java.util.Collections.singletonList(discordId), ip, forceVerify);
        }

        // Record exists (e.g. only hash/salt) but no Discord ID
        return new PlayerRecord(uuid, java.util.Collections.emptyList(), ip, forceVerify);
    }

    public void setRecord(PlayerRecord r) {
        String base = r.uuid.toString();
        if (r.passwordOnly) {
            cfg.set(base + ".discordId", "PASSWORD_ONLY");
            cfg.set(base + ".discordIds", null);
        } else {
            cfg.set(base + ".discordId", null); // clear legacy single field
            cfg.set(base + ".discordIds", r.discordIds.isEmpty() ? null : r.discordIds);
        }
        cfg.set(base + ".lastIp", r.lastIp);
        cfg.set(base + ".forceVerify", r.forceVerify ? true : null);
        save();
    }

    /** Convenience: create or update a record keeping existing discordIds + forceVerify, just changing lastIp. */
    public void updateIp(UUID uuid, String newIp) {
        PlayerRecord r = getRecord(uuid);
        if (r == null) return;
        setRecord(new PlayerRecord(r.uuid, r.discordIds, newIp, r.forceVerify, r.passwordOnly));
    }

    public void removeRecord(UUID uuid) {
        cfg.set(uuid.toString(), null);
        save();
    }

    /** Returns whether this player prefers the visual keypad over text-field input. Defaults to false. */
    public boolean getPreferKeypad(UUID uuid) {
        return cfg.getBoolean(uuid.toString() + ".preferKeypad", false);
    }

    /** Persists the player's input-mode preference for the code verification dialog. */
    public void setPreferKeypad(UUID uuid, boolean preferKeypad) {
        cfg.set(uuid.toString() + ".preferKeypad", preferKeypad);
        save();
    }
    
    /**
     * Get all player names (from UUID) that are linked to a specific Discord ID.
     */
    public synchronized java.util.List<String> getPlayerNamesByDiscordId(String discordId) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (discordId == null) return names;
        
        for (String key : cfg.getKeys(false)) {
            String storedDiscordId = cfg.getString(key + ".discordId");
            if (discordId.equals(storedDiscordId)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    org.bukkit.OfflinePlayer player = plugin.getServer().getOfflinePlayer(uuid);
                    names.add(player.getName() != null ? player.getName() : uuid.toString());
                } catch (IllegalArgumentException e) {
                    // Invalid UUID, skip
                }
            }
        }
        
        return names;
    }
    
    /**
     * Count how many accounts are linked to a specific Discord ID.
     */
    public synchronized int countAccountsByDiscordId(String discordId) {
        if (discordId == null) return 0;
        
        int count = 0;
        for (String key : cfg.getKeys(false)) {
            String storedDiscordId = cfg.getString(key + ".discordId");
            if (discordId.equals(storedDiscordId)) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * Get UUID by player name (searches through all records).
     * Returns null if player not found.
     */
    public synchronized UUID getUuidByName(String playerName) {
        if (playerName == null) return null;
        
        for (String key : cfg.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                org.bukkit.OfflinePlayer player = plugin.getServer().getOfflinePlayer(uuid);
                if (player.getName() != null && player.getName().equalsIgnoreCase(playerName)) {
                    return uuid;
                }
            } catch (IllegalArgumentException e) {
                // Invalid UUID, skip
            }
        }
        
        return null;
    }

    // ── Multi-account helpers ────────────────────────────────────────────────

    /** Returns all Discord IDs linked to this player (never null, may be empty). */
    public java.util.List<String> getDiscordIds(UUID uuid) {
        PlayerRecord r = getRecord(uuid);
        return r != null ? r.discordIds : java.util.Collections.emptyList();
    }

    /** Appends a Discord ID to the player's linked accounts list. */
    public synchronized void addDiscordId(UUID uuid, String discordId) {
        PlayerRecord r = getRecord(uuid);
        java.util.List<String> ids = r != null ? new java.util.ArrayList<>(r.discordIds) : new java.util.ArrayList<>();
        if (!ids.contains(discordId)) ids.add(discordId);
        String ip = r != null ? r.lastIp : null;
        boolean forceVerify = r != null && r.forceVerify;
        setRecord(new PlayerRecord(uuid, ids, ip, forceVerify));
    }

    /** Removes a Discord ID from the player's linked accounts list. */
    public synchronized void removeDiscordId(UUID uuid, String discordId) {
        PlayerRecord r = getRecord(uuid);
        if (r == null) return;
        java.util.List<String> ids = new java.util.ArrayList<>(r.discordIds);
        ids.remove(discordId);
        setRecord(new PlayerRecord(r.uuid, ids, r.lastIp, r.forceVerify, r.passwordOnly));
    }

    /** Set or clear the persistent "always require 2FA" flag. */
    public synchronized void setForceVerify(UUID uuid, boolean force) {
        PlayerRecord r = getRecord(uuid);
        if (r == null) return;
        setRecord(new PlayerRecord(r.uuid, new java.util.ArrayList<>(r.discordIds), r.lastIp, force, r.passwordOnly));
    }

    public boolean isForceVerify(UUID uuid) {
        PlayerRecord r = getRecord(uuid);
        return r != null && r.forceVerify;
    }

    // ── Password storage (stored alongside player record in playerdata.yml) ─────

    public boolean hasPassword(UUID uuid) {
        return cfg.contains(uuid.toString() + ".hash");
    }

    public String getPasswordSalt(UUID uuid) {
        return cfg.getString(uuid.toString() + ".salt");
    }

    public String getPasswordHash(UUID uuid) {
        return cfg.getString(uuid.toString() + ".hash");
    }

    public synchronized void setPasswordData(UUID uuid, String salt, String hash) {
        cfg.set(uuid.toString() + ".salt", salt);
        cfg.set(uuid.toString() + ".hash", hash);
        save();
    }

    public synchronized void removePasswordData(UUID uuid) {
        cfg.set(uuid.toString() + ".salt", null);
        cfg.set(uuid.toString() + ".hash", null);
        save();
    }

    public static class PlayerRecord {
        /** First linked Discord ID, or null if none, or "PASSWORD_ONLY" sentinel. */
        public final String discordId;
        /** All linked Discord IDs (Snowflake strings). Empty for PASSWORD_ONLY / unregistered. */
        public final java.util.List<String> discordIds;
        public final UUID uuid;
        public final String lastIp;
        /** When true, every login always requires 2FA regardless of IP. */
        public final boolean forceVerify;
        /** True when this is a password-only account (no Discord). */
        public final boolean passwordOnly;

        /** Legacy 3-arg constructor — creates a single-ID or PASSWORD_ONLY record. */
        public PlayerRecord(UUID uuid, String discordId, String lastIp) {
            this.uuid = uuid;
            this.lastIp = lastIp;
            this.forceVerify = false;
            if ("PASSWORD_ONLY".equals(discordId)) {
                this.discordId = "PASSWORD_ONLY";
                this.discordIds = java.util.Collections.emptyList();
                this.passwordOnly = true;
            } else {
                this.discordId = discordId;
                this.discordIds = discordId != null
                    ? java.util.Collections.singletonList(discordId)
                    : java.util.Collections.emptyList();
                this.passwordOnly = false;
            }
        }

        /** Full constructor — multi-ID, forceVerify. */
        public PlayerRecord(UUID uuid, java.util.List<String> discordIds, String lastIp, boolean forceVerify) {
            this(uuid, discordIds, lastIp, forceVerify, false);
        }

        /** Full constructor with passwordOnly flag. */
        public PlayerRecord(UUID uuid, java.util.List<String> discordIds, String lastIp, boolean forceVerify, boolean passwordOnly) {
            this.uuid = uuid;
            this.lastIp = lastIp;
            this.forceVerify = forceVerify;
            this.passwordOnly = passwordOnly;
            if (passwordOnly) {
                this.discordId = "PASSWORD_ONLY";
                this.discordIds = java.util.Collections.emptyList();
            } else {
                java.util.List<String> ids = discordIds != null ? discordIds : java.util.Collections.emptyList();
                this.discordIds = java.util.Collections.unmodifiableList(ids);
                this.discordId = ids.isEmpty() ? null : ids.get(0);
            }
        }

        /** Returns true if the player has at least one linked Discord account. */
        public boolean hasDiscord() {
            return !discordIds.isEmpty();
        }
    }
}
