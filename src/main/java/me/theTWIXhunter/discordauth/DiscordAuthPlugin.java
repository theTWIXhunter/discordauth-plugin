package me.theTWIXhunter.discordauth;

import org.bukkit.plugin.java.JavaPlugin;

public class DiscordAuthPlugin extends JavaPlugin {
    private DiscordService discordService;
    private PlayerDataManager dataManager;
    private VerificationManager verificationManager;
    private LanguageManager languageManager;
    private BackupPasswordManager passwordManager;
    private DialogManager dialogManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        // Load language manager first
        this.languageManager = new LanguageManager(this);
        this.languageManager.loadLanguage();
        
        // Load bot token from file or config
        String token = loadBotToken();
        if (token == null || token.isBlank() || token.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            getLogger().severe("═══════════════════════════════════════════════════════════════");
            getLogger().severe("ERROR: Discord bot token is NOT configured!");
            getLogger().severe("");
            getLogger().severe("PLEASE CONFIGURE YOUR BOT TOKEN:");
            getLogger().severe("Guide: https://thetwixhunter.nekoweb.org/discordauth/guides/initial-setup.html");
            getLogger().severe("");
            getLogger().severe("Players will NOT be able to join until this is fixed!");
            getLogger().severe("═══════════════════════════════════════════════════════════════");
        }

        this.discordService = new DiscordService(this, token);
        this.dataManager = new PlayerDataManager(this);
        this.passwordManager = new BackupPasswordManager(this, dataManager);
        this.verificationManager = new VerificationManager(this, discordService, dataManager);
        this.dialogManager = new DialogManager(this, verificationManager, dataManager, passwordManager);

        JoinListener joinListener = new JoinListener(this, verificationManager, dataManager, passwordManager, dialogManager);
        dialogManager.setJoinListener(joinListener);
        getServer().getPluginManager().registerEvents(joinListener, this);

        // Register commands
        DiscordAuthCommand commandHandler = new DiscordAuthCommand(this, dataManager);
        getCommand("discordauth").setExecutor(commandHandler);
        getCommand("discordauth").setTabCompleter(commandHandler);
        
        PasswordCommand passwordCommandHandler = new PasswordCommand(this, dataManager, passwordManager, discordService, joinListener);
        getCommand("password").setExecutor(passwordCommandHandler);
        getCommand("password").setTabCompleter(passwordCommandHandler);
        
        // Register shortcut commands
        LogoutCommand logoutCommandHandler = new LogoutCommand(commandHandler);
        getCommand("logout").setExecutor(logoutCommandHandler);
        getCommand("logout").setTabCompleter(logoutCommandHandler);
        
        UnlinkCommand unlinkCommandHandler = new UnlinkCommand(commandHandler);
        getCommand("unlink").setExecutor(unlinkCommandHandler);
        getCommand("unlink").setTabCompleter(unlinkCommandHandler);

        getLogger().info("DiscordAuth enabled");
    }

    @Override
    public void onDisable() {
        dataManager.save();
        getLogger().info("DiscordAuth disabled");
    }

    public DiscordService getDiscordService() {
        return discordService;
    }

    public PlayerDataManager getDataManager() {
        return dataManager;
    }

    public VerificationManager getVerificationManager() {
        return verificationManager;
    }
    
    public LanguageManager getLanguageManager() {
        return languageManager;
    }
    
    public DialogManager getDialogManager() {
        return dialogManager;
    }
    
    /**
     * Load bot token from file or config.
     * Prioritizes bot-token-file if it exists and is valid.
     * Supports both YAML (.yml) and plain text (.txt) formats.
     */
    private String loadBotToken() {
        String tokenFilePath = getConfig().getString("bot-token-file", "bot-token.yml");
        
        if (tokenFilePath != null && !tokenFilePath.isBlank()) {
            java.io.File tokenFile = new java.io.File(getDataFolder(), tokenFilePath);
            
            // If path is absolute, use it directly
            if (new java.io.File(tokenFilePath).isAbsolute()) {
                tokenFile = new java.io.File(tokenFilePath);
            }
            
            if (tokenFile.exists()) {
                try {
                    // Check if it's a YAML file
                    if (tokenFile.getName().endsWith(".yml") || tokenFile.getName().endsWith(".yaml")) {
                        org.bukkit.configuration.file.YamlConfiguration tokenConfig = 
                            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(tokenFile);
                        
                        String fileToken = tokenConfig.getString("bot-token", "");
                        
                        if (fileToken != null && !fileToken.isBlank() && !fileToken.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
                            getLogger().info("Bot token loaded from YAML file: " + tokenFile.getName());
                            return fileToken;
                        }
                    } else {
                        // Plain text file - read entire content
                        String fileToken = new String(java.nio.file.Files.readAllBytes(tokenFile.toPath()), 
                                                      java.nio.charset.StandardCharsets.UTF_8).trim();
                        
                        if (!fileToken.isBlank() && !fileToken.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
                            getLogger().info("Bot token loaded from text file: " + tokenFile.getName());
                            return fileToken;
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to read bot token from file: " + tokenFile.getAbsolutePath());
                    getLogger().warning("Error: " + e.getMessage());
                    getLogger().warning("Falling back to bot-token from config.yml");
                }
            } else {
                // Create default token file
                try {
                    tokenFile.getParentFile().mkdirs();
                    
                    if (tokenFile.getName().endsWith(".yml") || tokenFile.getName().endsWith(".yaml")) {
                        // Create YAML format
                        String yamlContent = "# Discord Bot Token Configuration\n" +
                                           "# This file is separate from config.yml for security reasons\n" +
                                           "# You can safely share config.yml without exposing your bot token\n\n" +
                                           "# Your Discord bot token (requires Send Messages and Create DM permissions)\n" +
                                           "bot-token: \"PUT_YOUR_BOT_TOKEN_HERE\"\n";
                        java.nio.file.Files.write(tokenFile.toPath(), 
                                                 yamlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        // Create plain text format
                        java.nio.file.Files.write(tokenFile.toPath(), 
                                                 "PUT_YOUR_BOT_TOKEN_HERE".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    
                    getLogger().info("Created default bot token file: " + tokenFile.getAbsolutePath());
                    getLogger().info("Please edit this file to add your bot token.");
                } catch (java.io.IOException e) {
                    getLogger().warning("Failed to create bot token file: " + e.getMessage());
                }
            }
        }
        
        // Fall back to config.yml
        String configToken = getConfig().getString("bot-token", "");
        if (configToken != null && !configToken.isBlank() && !configToken.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            getLogger().info("Bot token loaded from config.yml");
        }
        return configToken;
    }
}
