package me.theTWIXhunter.discordauth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages language files and message formatting with prefix support.
 * Includes fallback to default.yml for missing messages.
 */
public class LanguageManager {
    private final DiscordAuthPlugin plugin;
    private final Logger logger;
    private YamlConfiguration languageConfig;
    private YamlConfiguration defaultConfig;
    private String prefix;
    private String currentLanguageFile;
    private final Map<String, String> messageCache = new HashMap<>();
    
    public LanguageManager(DiscordAuthPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }
    
    /**
     * Load the language file specified in config.yml
     */
    public void loadLanguage() {
        String languageFile = plugin.getConfig().getString("language", "en-uk");
        if (!languageFile.endsWith(".yml")) {
            languageFile += ".yml";
        }
        
        this.currentLanguageFile = languageFile;
        
        File langFolder = new File(plugin.getDataFolder(), "languages");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        
        // ALWAYS regenerate default.yml (delete and recreate)
        File defaultFile = new File(langFolder, "default.yml");
        if (defaultFile.exists()) {
            defaultFile.delete();
        }
        
        try (InputStream defaultIn = plugin.getResource("languages/default.yml")) {
            if (defaultIn != null) {
                Files.copy(defaultIn, defaultFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("Regenerated default.yml language file");
            }
        } catch (Exception e) {
            logger.severe("Failed to regenerate default.yml: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Load default config as fallback
        if (defaultFile.exists()) {
            defaultConfig = YamlConfiguration.loadConfiguration(defaultFile);
        } else {
            defaultConfig = new YamlConfiguration();
            logger.warning("Could not load default.yml as fallback!");
        }
        
        // Now load the user's selected language file
        File langFile = new File(langFolder, languageFile);
        
        // Copy language file from resources if it doesn't exist (but not default.yml)
        if (!langFile.exists() && !languageFile.equals("default.yml")) {
            try (InputStream in = plugin.getResource("languages/" + languageFile)) {
                if (in != null) {
                    Files.copy(in, langFile.toPath());
                    logger.info("Created language file: " + languageFile);
                } else {
                    logger.warning("Language file not found in plugin resources: " + languageFile);
                    logger.warning("Using default.yml as fallback");
                    langFile = defaultFile;
                    this.currentLanguageFile = "default.yml";
                }
            } catch (Exception e) {
                logger.severe("Failed to copy language file: " + e.getMessage());
                e.printStackTrace();
            }
        } else if (languageFile.equals("default.yml")) {
            logger.warning("You are using default.yml as your language file!");
            logger.warning("Please create a custom language file (e.g., en-uk.yml) and set it in config.yml");
            logger.warning("The default.yml file is regenerated on every restart and cannot be edited!");
        }
        
        // Load the language file
        if (langFile.exists()) {
            languageConfig = YamlConfiguration.loadConfiguration(langFile);
            prefix = getConfigValue("prefix", "&6[DiscordAuth]&r ");
            messageCache.clear(); // Clear cache on reload
            logger.info("Loaded language file: " + languageFile);
        } else {
            logger.severe("Failed to load language file: " + languageFile);
            logger.warning("Using default.yml as fallback");
            languageConfig = defaultConfig;
            prefix = "&6[DiscordAuth]&r ";
        }
    }
    
    /**
     * Get a config value with fallback to default.yml
     */
    private String getConfigValue(String path, String defaultValue) {
        String value = languageConfig.getString(path);
        if (value == null && defaultConfig != null) {
            value = defaultConfig.getString(path);
            if (value != null) {
                logger.warning("Language file '" + currentLanguageFile + "' is missing key: " + path);
                logger.warning("Please update your language file to include this key. Using default for now.");
            }
        }
        return value != null ? value : defaultValue;
    }
    
    /**
     * Get a raw message string from the language file (without prefix).
     */
    public String getRawMessage(String path) {
        if (languageConfig == null) {
            return path;
        }
        
        String fullPath = "messages." + path;
        String message = messageCache.get(fullPath);
        
        if (message == null) {
            // Try user's language file first
            message = languageConfig.getString(fullPath);
            
            // Fall back to default.yml if not found
            if (message == null && defaultConfig != null) {
                message = defaultConfig.getString(fullPath);
                if (message != null) {
                    logger.warning("Language file '" + currentLanguageFile + "' is missing message: " + path);
                    logger.warning("Please update your language file to include this message. Using default for now.");
                }
            }
            
            // Ultimate fallback
            if (message == null) {
                message = path;
            }
            
            messageCache.put(fullPath, message);
        }
        
        return message;
    }
    
    /**
     * Get a message with the prefix applied.
     */
    public String getMessage(String path) {
        return prefix + getRawMessage(path);
    }
    
    /**
     * Get a message with the prefix and replace placeholders.
     */
    public String getMessage(String path, Map<String, String> replacements) {
        String message = getMessage(path);
        
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        
        return message;
    }
    
    /**
     * Get a Component message with prefix applied.
     */
    public Component getComponent(String path) {
        return parseComponent(getMessage(path));
    }
    
    /**
     * Get a Component message with prefix and replace placeholders.
     */
    public Component getComponent(String path, Map<String, String> replacements) {
        return parseComponent(getMessage(path, replacements));
    }
    
    /**
     * Get a Component message without prefix.
     */
    public Component getRawComponent(String path) {
        return parseComponent(getRawMessage(path));
    }
    
    /**
     * Parse a string with color codes into a Component.
     */
    private Component parseComponent(String text) {
        // Support both & and § color codes, and \n for newlines
        text = text.replace("\\n", "\n");
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
    
    /**
     * Get the prefix as a Component.
     */
    public Component getPrefixComponent() {
        return parseComponent(prefix);
    }
    
    /**
     * Get a dialog-specific message (for future dialog implementation).
     */
    public String getDialogMessage(String path) {
        if (languageConfig == null) {
            return path;
        }
        
        String fullPath = "dialog." + path;
        String message = languageConfig.getString(fullPath);
        
        // Fall back to default.yml if not found
        if (message == null && defaultConfig != null) {
            message = defaultConfig.getString(fullPath);
            if (message != null) {
                logger.warning("Language file '" + currentLanguageFile + "' is missing dialog message: " + path);
                logger.warning("Please update your language file to include this message. Using default for now.");
            }
        }
        
        return message != null ? message : path;
    }
    
    /**
     * Get the kick message.
     */
    public String getKickMessage() {
        return getRawMessage("kick-message");
    }
    
    /**
     * Get embed data from Discord section of language file.
     * Returns a map with title, description, and color.
     */
    public Map<String, Object> getDiscordEmbed(String embedType, Map<String, String> replacements) {
        Map<String, Object> embedData = new HashMap<>();
        
        String basePath = "discord." + embedType;
        
        // Get color
        int color = languageConfig.getInt(basePath + ".color", 0);
        if (color == 0 && defaultConfig != null) {
            color = defaultConfig.getInt(basePath + ".color", 0);
        }
        embedData.put("color", color);
        
        // Get title
        String title = languageConfig.getString(basePath + ".title");
        if (title == null && defaultConfig != null) {
            title = defaultConfig.getString(basePath + ".title");
        }
        if (title == null) title = "";
        
        // Replace placeholders in title
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                title = title.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        embedData.put("title", title);
        
        // Get description
        String description = languageConfig.getString(basePath + ".description");
        if (description == null && defaultConfig != null) {
            description = defaultConfig.getString(basePath + ".description");
        }
        if (description == null) description = "";
        
        // Replace placeholders in description
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                description = description.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        embedData.put("description", description);
        
        return embedData;
    }
    
    /**
     * Get the kick timeout message.
     */
    public String getKickTimeoutMessage() {
        return getRawMessage("kick-timeout-message");
    }
    
    /**
     * Get a Discord message (sent via Discord DMs).
     */
    public String getDiscordMessage(String path) {
        if (languageConfig == null) {
            return path;
        }
        
        String fullPath = "discord." + path;
        String message = languageConfig.getString(fullPath);
        
        // Fall back to default.yml if not found
        if (message == null && defaultConfig != null) {
            message = defaultConfig.getString(fullPath);
            if (message != null) {
                logger.warning("Language file '" + currentLanguageFile + "' is missing Discord message: " + path);
                logger.warning("Please update your language file to include this message. Using default for now.");
            }
        }
        
        return message != null ? message : path;
    }
    
    /**
     * Get a Discord message with placeholder replacement.
     */
    public String getDiscordMessage(String path, Map<String, String> replacements) {
        String message = getDiscordMessage(path);
        
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        
        return message;
    }
}
