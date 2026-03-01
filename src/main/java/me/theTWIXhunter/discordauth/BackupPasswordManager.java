package me.theTWIXhunter.discordauth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manages backup passwords for players who lose Discord access.
 * Passwords are hashed with SHA-256 and salted, stored in playerdata.yml
 * via {@link PlayerDataManager} so that a single reload covers both.
 */
public class BackupPasswordManager {
    private final JavaPlugin plugin;
    private final PlayerDataManager dataManager;
    private final SecureRandom random = new SecureRandom();

    public BackupPasswordManager(JavaPlugin plugin, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    /**
     * Check if a player has a backup password set.
     */
    public boolean hasPassword(UUID uuid) {
        return dataManager.hasPassword(uuid);
    }

    /**
     * Set a backup password for a player.
     */
    public void setPassword(UUID uuid, String password) {
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        String hash = hashPassword(password, salt);
        dataManager.setPasswordData(uuid, salt, hash);
    }

    /**
     * Verify a backup password.
     */
    public boolean verifyPassword(UUID uuid, String password) {
        if (!hasPassword(uuid)) return false;
        String salt = dataManager.getPasswordSalt(uuid);
        String storedHash = dataManager.getPasswordHash(uuid);
        String inputHash = hashPassword(password, salt);
        return inputHash != null && inputHash.equals(storedHash);
    }

    /**
     * Remove backup password for a player.
     */
    public void removePassword(UUID uuid) {
        dataManager.removePasswordData(uuid);
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes());
            byte[] hashedBytes = md.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hashedBytes);
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("SHA-256 algorithm not available!");
            return null;
        }
    }
}
