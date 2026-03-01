package me.theTWIXhunter.discordauth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shortcut command for /discordauth unlink
 */
public class UnlinkCommand implements CommandExecutor, TabCompleter {
    
    private final DiscordAuthCommand mainCommand;
    
    public UnlinkCommand(DiscordAuthCommand mainCommand) {
        this.mainCommand = mainCommand;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Build args array with "unlink" as first argument
        String[] newArgs = new String[args.length + 1];
        newArgs[0] = "unlink";
        System.arraycopy(args, 0, newArgs, 1, args.length);
        
        // Delegate to main command
        return mainCommand.onCommand(sender, command, label, newArgs);
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Only show player names for admins
        if (args.length == 1 && sender.hasPermission("discordauth.admin")) {
            List<String> players = new ArrayList<>();
            for (Player player : sender.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    players.add(player.getName());
                }
            }
            return players;
        }
        return new ArrayList<>();
    }
}
