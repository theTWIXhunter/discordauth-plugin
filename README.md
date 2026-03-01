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
- `allow-discord-registration`: Enable Discord account linking (default: true)
- `allow-password-only-registration`: Allow registration with just a password (default: true)
- `enable-backup-password`: Allow backup passwords for Discord-linked accounts (default: true)

### Authentication Skip Rules
- `skip-premium-accounts`: Skip auth for Microsoft/Mojang authenticated players
- `skip-matching-ip`: Skip auth when IP matches last known IP
- `skip-specific-players`: Skip auth for specific usernames (debug only)
- `force-authentication`: Force OPs or permission holders to always authenticate

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



