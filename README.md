# DiscordAuth Plugin

A Discord verification authentication plugin for Minecraft servers (Paper 1.21+) by theTWIXhunter. Links player accounts to Discord via DM verification codes or allows password-based authentication.

## Features

- **Discord Verification**: Players verify by entering their Discord User ID and receiving a 4-digit code via bot DM
- **Backup Password System**: Set backup passwords for when Discord access is lost
- **Password-Only Mode**: Allow registration with just a password (no Discord required)
- **Multi-Language Support**: Built-in support for multiple languages (English UK, Dutch Belgium)
- **Smart Authentication Skip**: Skip login for premium accounts or matching IPs (configurable)
- **Account Management**: Players can unlink Discord accounts, logout, and manage passwords
- **Security Options**:
  - Max accounts per Discord account limit
  - Verification timeout
  - Force authentication for OPs/specific permissions
- **Experimental Features**:
  - Discord role sync (grant Minecraft permissions based on Discord roles)
  - DiscordSRV compatibility mode

## Commands

- `/discordauth <reload|unlink|logout>` - Main command (aliases: `/dauth`)
  - `reload` - Reload the plugin configuration (admin)
  - `unlink [player]` - Unlink Discord account
  - `logout [player]` - Logout from verification session
- `/password <set|change|forgot>` - Manage backup passwords (aliases: `/pw`, `/passwd`)
- `/logout [player]` - Quick logout command
- `/unlink [player]` - Quick unlink command

## Permissions

- `discordauth.use` - Allows use of basic commands (default: true)
- `discordauth.admin` - Allows use of admin commands (default: op)
- `discordauth.force.login` - Force players with this permission to always authenticate

## Configuration

Edit `config.yml` to customize the plugin:

### General Settings
- `server-name`: Server name shown in Discord messages
- `discord-invite`: Discord invite link (shown when DMs fail)
- `max-accounts-per-discord`: Maximum Minecraft accounts per Discord account (0 = unlimited)
- `language`: Language file to use (en-uk, nl-be)
- `verification-timeout`: Timeout in seconds before kicking unverified players (0 to disable)

### Verification Methods
- Discord registration is always available.
- Password registration and backup password access are controlled under the Authentication Skip Rules section.

### Authentication Skip Rules
- `default-mode`: Fallback mode used when a player does not match any group
- `password-feature-default`: Fallback password access mode used when a player does not match any group
- `operators`: Control how operators are handled, or set to `disabled` to ignore the group
- `operators-password-feature`: Password access for operators (`discord`, `password`, `both`, `disabled`)
- `known-ip`: Control how players with a matching last IP are handled, or set to `disabled` to ignore the group
- `known-ip-password-feature`: Password access for players with a matching last IP (`discord`, `password`, `both`, `disabled`)
- `specific-players`: Per-player overrides using `name`, `mode`, and `password-feature`
- `bedrock-players`: Control how Floodgate/Geyser players are handled, or set to `disabled` to ignore the group
- `bedrock-players-password-feature`: Password access for Bedrock players (`discord`, `password`, `both`, `disabled`)
- `premium-users`: Control how premium Java players are handled, or set to `disabled` to ignore the group
- `premium-users-password-feature`: Password access for premium Java players (`discord`, `password`, `both`, `disabled`)

### Experimental Features
- `discord-role-sync`: Sync Discord roles to Minecraft permissions
- `discordsrv-compatibility`: Import accounts from DiscordSRV

## Initial Setup

1. **Create a Discord Bot**
   - Go to [Discord Developer Portal](https://discord.com/developers/applications)
   - Create a new application
   - Add a bot and copy the bot token
   - Enable these Privileged Gateway Intents:
     - Server Members Intent
     - Message Content Intent

2. **Configure the Plugin**
   - Edit `plugins/DiscordAuth/bot-token.yml`
   - Replace `PUT_YOUR_BOT_TOKEN_HERE` with your bot token
   - Edit `plugins/DiscordAuth/config.yml` to your preferences
   - Set your `server-name` and `discord-invite` link

3. **Invite the Bot**
   - Use your bot's OAuth2 URL with these scopes: `bot`
   - Required permissions: Send Messages, Read Messages, Embed Links

For detailed setup instructions, visit: https://thetwixhunter.nekoweb.org/discordauth/guides/initial-setup.html

## Building

Run `mvn clean package` to build the plugin. The compiled JAR will be in the `target` folder.

## Installation

1. Build the plugin or download the JAR
2. Place the JAR in your server's `plugins` folder
3. Restart the server
4. Follow the Initial Setup guide above
5. Configure `plugins/DiscordAuth/bot-token.yml` and `config.yml`
6. Reload or restart the server

## How It Works

### First-Time Registration
1. Player joins the server
2. Plugin prompts for Discord User ID or password setup
3. If Discord: Bot sends 4-digit code via DM → Player enters code
4. If password-only: Player sets a password
5. Account is registered and player can join

### Returning Players
1. Player joins the server
2. If skip rules apply (premium/IP match), player joins immediately
3. Otherwise, player must verify with Discord code or password
4. After verification, player can join

## Author

theTWIXhunter

## Version

1.1.0 - Paper 1.21+



