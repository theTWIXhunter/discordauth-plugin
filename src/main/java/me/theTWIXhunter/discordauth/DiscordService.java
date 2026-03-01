package me.theTWIXhunter.discordauth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Minimal Discord helper using Discord REST API to create DM channel and send messages.
 * Assumes the configured token is a Bot token.
 */
public class DiscordService {
    private final JavaPlugin plugin;
    private final String token;
    private final HttpClient http;
    private boolean botTokenValid = false;

    public DiscordService(JavaPlugin plugin, String token) {
        this.plugin = plugin;
        this.token = token == null ? "" : token.trim();
        this.http = HttpClient.newHttpClient();
        
        // Test bot token on startup
        if (!this.token.isBlank() && !this.token.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            testBotToken();
        }
    }
    
    /**
     * Check if the bot token is valid and working.
     */
    public boolean isBotTokenValid() {
        return botTokenValid;
    }

    /**
     * Test if the bot token is valid by fetching bot user info.
     */
    private void testBotToken() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/users/@me"))
                    .header("Authorization", "Bot " + token)
                    .GET()
                    .build();
            
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                String username = extractJsonField(response.body(), "username");
                String id = extractJsonField(response.body(), "id");
                plugin.getLogger().info("Discord bot connected successfully: " + username + " (ID: " + id + ")");
                botTokenValid = true;
            } else {
                plugin.getLogger().severe("═══════════════════════════════════════════════════════════════");
                plugin.getLogger().severe("ERROR: Discord bot token test FAILED! HTTP " + response.statusCode());
                plugin.getLogger().severe("Response: " + response.body());
                plugin.getLogger().severe("");
                plugin.getLogger().severe("PLEASE CONFIGURE YOUR BOT TOKEN:");
                plugin.getLogger().severe("Guide: https://thetwixhunter.nekoweb.org/discordauth/guides/initial-setup.html");
                plugin.getLogger().severe("");
                plugin.getLogger().severe("Players will NOT be able to join until this is fixed!");
                plugin.getLogger().severe("═══════════════════════════════════════════════════════════════");
                botTokenValid = false;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("═══════════════════════════════════════════════════════════════");
            plugin.getLogger().severe("ERROR: Failed to test Discord bot token: " + e.getMessage());
            plugin.getLogger().severe("");
            plugin.getLogger().severe("PLEASE CONFIGURE YOUR BOT TOKEN:");
            plugin.getLogger().severe("Guide: https://thetwixhunter.nekoweb.org/discordauth/guides/initial-setup.html");
            plugin.getLogger().severe("");
            plugin.getLogger().severe("Players will NOT be able to join until this is fixed!");
            plugin.getLogger().severe("═══════════════════════════════════════════════════════════════");
            botTokenValid = false;
        }
    }

    /**
     * Send a direct message to the given discord numeric id (snowflake).
     * Returns true if the message was sent (HTTP 200/201) or false on failure.
     */
    public boolean sendDirectMessage(String discordId, String content) {
        if (token == null || token.isBlank() || token.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("Discord token not configured; message not sent");
            return false;
        }

        try {
            // Create DM channel
            String body = "{\"recipient_id\":\"" + discordId + "\"}";
            HttpRequest createChannel = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/users/@me/channels"))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> channelResp = http.send(createChannel, HttpResponse.BodyHandlers.ofString());
            if (channelResp.statusCode() != 200 && channelResp.statusCode() != 201) {
                plugin.getLogger().warning("Failed to create DM channel with user " + discordId + ": HTTP " + channelResp.statusCode());
                plugin.getLogger().warning("Response: " + channelResp.body());
                plugin.getLogger().warning("Possible causes: Invalid user ID, user has DMs disabled, or bot lacks permissions.");
                return false;
            }

            // Extract id from response (rudimentary, assume JSON contains "id":"<id>" )
            String resp = channelResp.body();
            String channelId = extractJsonField(resp, "id");
            if (channelId == null) {
                plugin.getLogger().warning("Could not parse channel id from discord response: " + resp);
                return false;
            }

            String msgBody = "{\"content\":\"" + escapeJson(content) + "\"}";
            HttpRequest sendMsg = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages"))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(msgBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> msgResp = http.send(sendMsg, HttpResponse.BodyHandlers.ofString());
            if (msgResp.statusCode() != 200 && msgResp.statusCode() != 201) {
                plugin.getLogger().warning("Failed to send DM to channel " + channelId + ": HTTP " + msgResp.statusCode());
                plugin.getLogger().warning("Response: " + msgResp.body());
                return false;
            }

            plugin.getLogger().info("Successfully sent DM to user " + discordId);
            return true;
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("Error sending DM to user " + discordId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Send a direct message with an embed to the given discord numeric id (snowflake).
     * Returns true if the message was sent (HTTP 200/201) or false on failure.
     */
    public boolean sendDirectMessageWithEmbed(String discordId, String title, String description, int color) {
        if (token == null || token.isBlank() || token.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("Discord token not configured; message not sent");
            return false;
        }

        try {
            // Create DM channel
            String body = "{\"recipient_id\":\"" + discordId + "\"}";
            HttpRequest createChannel = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/users/@me/channels"))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> channelResp = http.send(createChannel, HttpResponse.BodyHandlers.ofString());
            if (channelResp.statusCode() != 200 && channelResp.statusCode() != 201) {
                plugin.getLogger().warning("Failed to create DM channel with user " + discordId + ": HTTP " + channelResp.statusCode());
                plugin.getLogger().warning("Response: " + channelResp.body());
                plugin.getLogger().warning("Possible causes: Invalid user ID, user has DMs disabled, or bot lacks permissions.");
                return false;
            }

            // Extract id from response
            String resp = channelResp.body();
            String channelId = extractJsonField(resp, "id");
            if (channelId == null) {
                plugin.getLogger().warning("Could not parse channel id from discord response: " + resp);
                return false;
            }

            // Build embed JSON
            String msgBody = "{\"embeds\":[{\"title\":\"" + escapeJson(title) + "\"," +
                           "\"description\":\"" + escapeJson(description) + "\"," +
                           "\"color\":" + color + "}]}";
            
            HttpRequest sendMsg = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages"))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(msgBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> msgResp = http.send(sendMsg, HttpResponse.BodyHandlers.ofString());
            if (msgResp.statusCode() != 200 && msgResp.statusCode() != 201) {
                plugin.getLogger().warning("Failed to send DM to channel " + channelId + ": HTTP " + msgResp.statusCode());
                plugin.getLogger().warning("Response: " + msgResp.body());
                return false;
            }

            plugin.getLogger().info("Successfully sent embed DM to user " + discordId);
            return true;
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("Error sending DM to user " + discordId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Single user lookup
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Fetch a Discord user by their Snowflake ID.
     * Uses {@code GET /users/{id}} with the bot token.
     * Returns {@code null} on error or if the token is not configured.
     */
    public GuildMember getUserById(String userId) {
        if (token == null || token.isBlank() || token.equals("PUT_YOUR_BOT_TOKEN_HERE")) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/users/" + userId))
                    .header("Authorization", "Bot " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            String id         = extractJsonField(body, "id");
            String username   = extractJsonField(body, "username");
            String globalName = extractJsonField(body, "global_name");
            if (id == null || username == null) return null;
            return new GuildMember(id, username, globalName != null ? globalName : "", null);
        } catch (java.io.IOException | InterruptedException e) {
            plugin.getLogger().warning("Failed to fetch Discord user " + userId + ": " + e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Guild member search
    // ──────────────────────────────────────────────────────────────────────────────

    /** A Discord guild member returned by the username search API. */
    public record GuildMember(String id, String username, String globalName, String nick) {
        /** User-friendly label: shows display name, username handle, and server nickname when they differ. */
        public String displayLabel() {
            String base = (globalName != null && !globalName.isBlank() && !globalName.equals(username))
                ? globalName + " (@" + username + ")"
                : "@" + username;
            if (nick != null && !nick.isBlank()
                    && !nick.equals(globalName) && !nick.equals(username)) {
                base += "\n(AKA: " + nick + ")";
            }
            return base;
        }
    }

    /**
     * Returns the IDs of all guilds (servers) the bot is currently a member of.
     * Calls {@code GET /users/@me/guilds}.
     */
    public java.util.List<String> getBotGuilds() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/users/@me/guilds"))
                    .header("Authorization", "Bot " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                plugin.getLogger().warning("Failed to fetch bot guilds: HTTP " + resp.statusCode());
                return java.util.List.of();
            }
            return parseGuildIds(resp.body());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to fetch bot guilds: " + e.getMessage());
            return java.util.List.of();
        }
    }

    private java.util.List<String> parseGuildIds(String json) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        int pos = 0;
        while (true) {
            int objStart = json.indexOf('{', pos);
            if (objStart == -1) break;
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd == -1) break;
            String obj = json.substring(objStart, objEnd + 1);
            String id = extractJsonField(obj, "id");
            if (id != null) ids.add(id);
            pos = objEnd + 1;
        }
        return ids;
    }

    /**
     * Search for members matching {@code query} across <b>all guilds the bot is in</b>,
     * deduplicating results by user ID.
     * Requires the bot to have the <b>Server Members Intent</b> enabled in the
     * Discord Developer Portal.
     *
     * @param query partial username to search (at least 1 character)
     * @return up to 10 unique matching {@link GuildMember}s, or an empty list on failure
     */
    public java.util.List<GuildMember> searchGuildMembers(String query) {
        java.util.List<String> guildIds = getBotGuilds();
        if (guildIds.isEmpty()) return java.util.List.of();
        java.util.Map<String, GuildMember> byId = new java.util.LinkedHashMap<>();
        for (String gid : guildIds) {
            for (GuildMember m : searchGuildMembersInGuild(gid, query)) {
                byId.putIfAbsent(m.id(), m);
                if (byId.size() >= 10) break;
            }
            if (byId.size() >= 10) break;
        }
        return new java.util.ArrayList<>(byId.values());
    }

    /**
     * Search for members in a specific guild by partial username (internal helper).
     * Requires the bot to have the <b>Server Members Intent</b> enabled in the Discord Developer Portal.
     *
     * @param guildId numeric guild (server) snowflake ID
     * @param query   partial username to search (at least 1 character)
     * @return up to 10 matching {@link GuildMember}s, or an empty list on failure
     */
    private java.util.List<GuildMember> searchGuildMembersInGuild(String guildId, String query) {
        if (guildId == null || guildId.isBlank() || query == null || query.isBlank()) {
            return java.util.List.of();
        }
        try {
            String encoded = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/guilds/" + guildId
                            + "/members/search?query=" + encoded + "&limit=10"))
                    .header("Authorization", "Bot " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                plugin.getLogger().warning("Guild member search HTTP " + resp.statusCode()
                        + " for query \"" + query + "\": " + resp.body());
                return java.util.List.of();
            }
            return parseGuildMembers(resp.body());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to search guild members: " + e.getMessage());
            return java.util.List.of();
        }
    }

    // kept for direct internal use — callers should use searchGuildMembers(query)
    private java.util.List<GuildMember> parseGuildMembers(String json) {
        java.util.List<GuildMember> list = new java.util.ArrayList<>();
        int pos = 0;
        while (true) {
            int userIdx = json.indexOf("\"user\":", pos);
            if (userIdx == -1) break;
            // Extract nick from the text before "user": — it lives at member root level (outside the user object)
            String memberPrefix = json.substring(pos, userIdx);
            String nick = extractJsonField(memberPrefix, "nick");
            int brace = json.indexOf('{', userIdx + 7);
            if (brace == -1) break;
            int end = findMatchingBrace(json, brace);
            if (end == -1) break;
            String userObj    = json.substring(brace, end + 1);
            String id         = extractJsonField(userObj, "id");
            String username   = extractJsonField(userObj, "username");
            String globalName = extractJsonField(userObj, "global_name");
            if (id != null && username != null) {
                list.add(new GuildMember(id, username, globalName != null ? globalName : "", nick));
            }
            pos = end + 1;
        }
        return list;
    }

    private static int findMatchingBrace(String json, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) return i; }
        }
        return -1;
    }

    private static String extractJsonField(String json, String key) {
        // Look for "key":"value" pattern
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return null;
        
        // Find the colon after the key
        int colonIdx = json.indexOf(":", idx);
        if (colonIdx == -1) return null;
        
        // Skip whitespace after colon
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        // Check if value is quoted
        if (valueStart < json.length() && json.charAt(valueStart) == '"') {
            valueStart++; // Skip opening quote
            int valueEnd = json.indexOf('"', valueStart);
            if (valueEnd == -1) return null;
            return json.substring(valueStart, valueEnd);
        }
        
        return null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
