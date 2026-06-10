package me.theTWIXhunter.discordauth;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages native Paper Dialog API screens for the Discord authentication flow.
 * Uses Dialog#create() — no external GUI libraries required.
 */
@SuppressWarnings("UnstableApiUsage")
public class DialogManager {
    private final DiscordAuthPlugin plugin;
    private final VerificationManager verificationManager;
    private final PlayerDataManager dataManager;
    private final BackupPasswordManager passwordManager;
    private final LanguageManager lang;
    /** Set after construction to avoid circular dependency with JoinListener. */
    private JoinListener joinListener;
    /** Accumulates keypad digit presses per player for the 2FA code dialog. */
    private final java.util.Map<java.util.UUID, String> enteredCodes = new java.util.HashMap<>();
    /** Tracks the last time each player triggered a code resend (ms). */
    private final java.util.Map<java.util.UUID, Long> lastResendTimes = new java.util.HashMap<>();
    /** Players who clicked "Reset password" — after Discord verification they see password-reset setup. */
    private final java.util.Set<java.util.UUID> pendingPasswordResets = new java.util.HashSet<>();
    /** Players who are setting a backup password for the first time — stores the chosen password until Discord code is verified. */
    private final java.util.Map<java.util.UUID, String> pendingBackupPasswords = new java.util.HashMap<>();
    /** Players who clicked "Remove Password" and are confirming via Discord code. */
    private final java.util.Set<java.util.UUID> pendingPasswordRemovals = new java.util.HashSet<>();
    /** Players who opened password/discord management from the /discordauth dashboard (already verified). */
    private final java.util.Set<java.util.UUID> dashboardContextPlayers = new java.util.HashSet<>();

    public DialogManager(DiscordAuthPlugin plugin, VerificationManager verificationManager,
                         PlayerDataManager dataManager, BackupPasswordManager passwordManager) {
        this.plugin = plugin;
        this.verificationManager = verificationManager;
        this.dataManager = dataManager;
        this.passwordManager = passwordManager;
        this.lang = plugin.getLanguageManager();
    }

    public void setJoinListener(JoinListener joinListener) {
        this.joinListener = joinListener;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public dialog entry points
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Show the initial authentication method choice dialog to a player.
     * Called on first join when no record exists.
     */
    public void showAuthenticationChoiceDialog(Player player) {
        boolean allowPasswordOnly = plugin.getConfig().getBoolean("allow-password-only-registration", false);

        ActionButton discordBtn = ActionButton.builder(
                Component.text("Discord 2FA", NamedTextColor.BLUE, TextDecoration.BOLD))
            .tooltip(Component.text("Recommended — link your Discord for secure login", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        showDiscordUsernameSearchDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();

        boolean allowDiscord = plugin.getConfig().getBoolean("allow-discord-registration", true);
        // Password button: show always, but disabled action if not allowed
        ActionButton passwordBtn = ActionButton.builder(
                allowPasswordOnly
                    ? Component.text("Password",
                        NamedTextColor.GOLD, TextDecoration.BOLD)
                    : Component.text("Password (disabled)", NamedTextColor.DARK_GRAY))
            .tooltip(allowPasswordOnly
                ? Component.text("Set a password — no Discord needed", NamedTextColor.GRAY)
                : Component.text("Password-only registration is disabled on this server", NamedTextColor.RED))
            .action(allowPasswordOnly
                ? DialogAction.customClick(
                    (view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            p.closeInventory();
                            showPasswordSetupDialog(p);
                        });
                    },
                    ClickCallback.Options.builder().uses(1).build())
                : DialogAction.customClick(
                    (view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        p.sendMessage(Component.text(
                            "Password-only registration is disabled on this server.",
                            NamedTextColor.RED));
                    },
                    ClickCallback.Options.builder().uses(1).build()))
            .build();

        ActionButton cancelChoiceBtn = ActionButton.builder(Component.text("Cancel", NamedTextColor.RED))
            .tooltip(Component.text("Leave the server"))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        if (joinListener != null) joinListener.kickUnverifiedPlayer(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Authentication Required", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(List.of(
                    DialogBody.plainMessage(Component.text(
                        "This server requires authentication.\n" +
                        "Please choose your method below.", NamedTextColor.GRAY)),
                    DialogBody.plainMessage(Component.text(
                        "Discord 2FA  —  most secure, recommended", NamedTextColor.GREEN)),
                    DialogBody.plainMessage(Component.text(
                        "Password  —  works without Discord",
                        allowPasswordOnly ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY))
                ))
                .build())
            .type(DialogType.multiAction(List.of(discordBtn, passwordBtn, cancelChoiceBtn)).columns(2).build())
        );

        player.showDialog(dialog);
    }

    /**
     * Show the Discord ID manual-entry dialog (fallback from username search).
     * Renamed internally; calling code that previously called showDiscordSetupDialog
     * should now call showDiscordUsernameSearchDialog as the default entry point.
     */
    public void showDiscordSetupDialog(Player player) {
        showDiscordIdEntryDialog(player);
    }

    /** ID-entry fallback dialog — reached via "Enter ID manually" in the search screen. */
    private void showDiscordIdEntryDialog(Player player) {
        String discordInvite = plugin.getConfig().getString("discord-invite", "https://discord.gg/YOUR_INVITE_CODE");

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Discord 2FA Setup", NamedTextColor.BLUE, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(List.of(
                    DialogBody.plainMessage(Component.text(
                        "How to find your Discord User ID:", NamedTextColor.YELLOW)),
                    DialogBody.plainMessage(Component.text(
                        "1. Open Discord → Settings → Advanced → enable Developer Mode.\n" +
                        "2. Right-click your username → Copy User ID.\n" +
                        "3. Paste the numeric ID (17-19 digits) below.", NamedTextColor.GRAY)),
                    DialogBody.plainMessage(Component.text(
                        "Not in the Discord server yet? " + discordInvite, NamedTextColor.AQUA)),
                    DialogBody.plainMessage(Component.text(
                        "A 4-digit code will be sent to your Discord DMs.", NamedTextColor.WHITE))
                ))
                .inputs(List.of(
                    DialogInput.text("discord_id",
                        Component.text("Discord User ID")).build()
                ))
                .build())
            .type(DialogType.multiAction(List.of(
                ActionButton.builder(Component.text("← Go Back", NamedTextColor.RED))
                    .tooltip(Component.text("Return to username search"))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                showDiscordUsernameSearchDialog(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Submit ID", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .tooltip(Component.text("Send verification code to your Discord DMs"))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String rawId = view.getText("discord_id");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handleDiscordIdSubmission(p, rawId);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Search by username", NamedTextColor.AQUA))
                    .tooltip(Component.text("Find your Discord account by username, displayname or nickname.", NamedTextColor.GRAY))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                showDiscordUsernameSearchDialog(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build()
            )).columns(2).build())
        );

        player.showDialog(dialog);
    }

    /**
     * Show the Discord username search dialog (default Discord 2FA entry point).
     * The bot automatically searches every guild it is in.
     */
    public void showDiscordUsernameSearchDialog(Player player) {
        showDiscordUsernameSearchDialog(player, null);
    }

    private void showDiscordUsernameSearchDialog(Player player, String errorMsg) {
        String discordInvite = plugin.getConfig().getString("discord-invite", "https://discord.gg/YOUR_INVITE_CODE");

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
            "Search by display name, username, or server nickname.", NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(Component.text(
            "Not in the Discord server yet? Join first: " + discordInvite, NamedTextColor.AQUA)));
        if (errorMsg != null) {
            body.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }

        final List<DialogBody> fb = body;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Discord 2FA Setup", NamedTextColor.BLUE, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(fb)
                .inputs(List.of(
                    DialogInput.text("username", Component.text("Discord name")).build()))
                .build())
            .type(DialogType.multiAction(List.of(
                ActionButton.builder(Component.text("← Go Back", NamedTextColor.RED))
                    .tooltip(Component.text("Return to method selection"))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                showAuthenticationChoiceDialog(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Search", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String q = view.getText("username");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                if (q == null || q.isBlank()) {
                                    showDiscordUsernameSearchDialog(p, "Please enter a name to search.");
                                    return;
                                }
                                handleUsernameSearch(p, q.trim());
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Enter ID manually", NamedTextColor.YELLOW))
                    .tooltip(Component.text("Paste your numeric Discord User ID instead", NamedTextColor.GRAY))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                showDiscordIdEntryDialog(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build()
            )).columns(2).build())
        );
        player.showDialog(dialog);
    }

    /**
     * Show a list of Discord members found by the search, letting the player pick theirs.
     */
    private void showDiscordSearchResultsDialog(Player player,
            java.util.List<DiscordService.GuildMember> members, String query) {
        // Single result: show "Is this you?" confirmation
        if (members.size() == 1) {
            DiscordService.GuildMember m = members.get(0);
            Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(
                        Component.text("Is this you?", NamedTextColor.BLUE, TextDecoration.BOLD))
                    .canCloseWithEscape(false)
                    .body(List.of(
                        DialogBody.plainMessage(Component.text(
                            "Found 1 account matching \"" + query + "\":", NamedTextColor.GRAY)),
                        DialogBody.plainMessage(Component.text(
                            m.displayLabel(), NamedTextColor.WHITE, TextDecoration.BOLD))))
                    .build())
                .type(DialogType.confirmation(
                    ActionButton.builder(Component.text("← Search again", NamedTextColor.RED))
                        .action(DialogAction.customClick(
                            (view, audience) -> {
                                if (!(audience instanceof Player p)) return;
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    p.closeInventory();
                                    showDiscordUsernameSearchDialog(p);
                                });
                            },
                            ClickCallback.Options.builder().uses(1).build()
                        ))
                        .build(),
                    ActionButton.builder(Component.text("Yes, that's me!", NamedTextColor.GREEN, TextDecoration.BOLD))
                        .action(DialogAction.customClick(
                            (view, audience) -> {
                                if (!(audience instanceof Player p)) return;
                                String id = m.id();
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    p.closeInventory();
                                    handleDiscordIdSubmission(p, id);
                                });
                            },
                            ClickCallback.Options.builder().uses(1).build()
                        ))
                        .build()
                ))
            );
            player.showDialog(dialog);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (DiscordService.GuildMember member : members) {
            buttons.add(ActionButton.builder(
                        Component.text(member.displayLabel(), NamedTextColor.WHITE))
                    .width(200)
                    .tooltip(Component.text("ID: " + member.id(), NamedTextColor.GRAY))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String id = member.id();
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handleDiscordIdSubmission(p, id);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build());
        }
        buttons.add(ActionButton.builder(Component.text("← Search again", NamedTextColor.RED))
            .width(200)
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        showDiscordUsernameSearchDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build());

        final List<ActionButton> fbt = buttons;
        final int count = members.size();
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Select Your Account", NamedTextColor.BLUE, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(List.of(
                    DialogBody.plainMessage(Component.text(
                        count + " account" + (count == 1 ? "" : "s") + " found for \"" + query + "\" — click yours.",
                        NamedTextColor.GREEN))))
                .build())
            .type(DialogType.multiAction(fbt).columns(1).build())
        );
        player.showDialog(dialog);
    }

    /**
     * Show the password setup dialog (first-time registration).
     */
    public void showPasswordSetupDialog(Player player) {
        showPasswordSetupDialog(player, null);
    }

    private void showPasswordSetupDialog(Player player, String errorMsg) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
            "Create a password for your account.\n" +
            "Requirements: 8+ characters, both fields must match.\n" +
            "Never share your password!", NamedTextColor.WHITE)));
        if (errorMsg != null) {
            body.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }
        final List<DialogBody> fb = body;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Set a Password", NamedTextColor.GOLD, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(fb)
                .inputs(List.of(
                    DialogInput.text("password",
                        Component.text("Password (min 8 characters)")).build(),
                    DialogInput.text("confirm",
                        Component.text("Confirm Password")).build()
                ))
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(Component.text("← Go Back", NamedTextColor.RED))
                    .tooltip(Component.text("Return to method selection"))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                showAuthenticationChoiceDialog(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Set Password", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .tooltip(Component.text("Save your password and start playing"))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String password = view.getText("password");
                            String confirm  = view.getText("confirm");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handlePasswordSubmission(p, password, confirm);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build()
            ))
        );
        player.showDialog(dialog);
    }

    /**
     * Show a password login dialog for returning PASSWORD_ONLY players whose IP changed.
     */
    public void showPasswordLoginDialog(Player player) {
        showPasswordLoginDialog(player, null);
    }

    private void showPasswordLoginDialog(Player player, String errorMsg) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
            "Welcome back! Enter your password to log in.", NamedTextColor.WHITE)));
        if (errorMsg != null) {
            body.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }
        final List<DialogBody> fb = body;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Password Login", NamedTextColor.GOLD, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(fb)
                .inputs(List.of(
                    DialogInput.text("password",
                        Component.text("Password")).build()
                ))
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(Component.text("Cancel", NamedTextColor.RED))
                    .tooltip(Component.text("Leave the server"))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                if (joinListener != null) joinListener.kickUnverifiedPlayer(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Log In", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .tooltip(Component.text("Verify and enter the game"))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String pw = view.getText("password");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handlePasswordLogin(p, pw);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build()
            ))
        );
        player.showDialog(dialog);
    }

    private void handlePasswordLogin(Player player, String password) {
        if (password == null || password.isBlank()) {
            showPasswordLoginDialog(player, "Please enter your password.");
            return;
        }
        if (!passwordManager.hasPassword(player.getUniqueId()) ||
                !passwordManager.verifyPassword(player.getUniqueId(), password)) {
            int maxAttempts = joinListener != null ? joinListener.getMaxLoginAttempts() : plugin.getConfig().getInt("max-login-attempts", 3);
            int failedAttempts = joinListener != null ? joinListener.incrementFailedLoginAttempts(player.getUniqueId()) : 1;

            if (maxAttempts > 0 && failedAttempts >= maxAttempts) {
                player.sendMessage(Component.text("Too many failed login attempts.", NamedTextColor.RED));
                if (joinListener != null) {
                    joinListener.kickForFailedLoginAttempts(player);
                } else {
                    player.kick(Component.text("Too many failed login attempts."));
                }
                return;
            }

            if (maxAttempts > 0) {
                int remainingAttempts = Math.max(0, maxAttempts - failedAttempts);
                showPasswordLoginDialog(player, "Incorrect password! " + remainingAttempts + " attempt(s) remaining.");
            } else {
                showPasswordLoginDialog(player, "Incorrect password! Please try again.");
            }
            return;
        }
        // Correct — update IP record
        String currentIp = joinListener != null ? joinListener.getPlayerIp(player) : "0.0.0.0";
        dataManager.setRecord(new PlayerDataManager.PlayerRecord(
            player.getUniqueId(), "PASSWORD_ONLY", currentIp));
        if (joinListener != null) {
            joinListener.resetFailedLoginAttempts(player.getUniqueId());
        }
        player.sendMessage(Component.text("✓ Logged in with password!", NamedTextColor.GREEN));
        plugin.getLogger().info(player.getName() + " logged in with password.");
        if (joinListener != null) {
            joinListener.restorePlayer(player);
        } else {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Code verification keypad dialog
    // ──────────────────────────────────────────────────────────────────────────

    /** Show the 2FA code verification dialog, using the player's saved input preference. */
    public void showCodeVerificationDialog(Player player) {
        enteredCodes.put(player.getUniqueId(), "");
        if (dataManager.getPreferKeypad(player.getUniqueId())) {
            renderCodeKeypad(player, "", null);
        } else {
            renderCodeTextField(player, null);
        }
    }

    /**
     * Show a choice dialog for returning Discord-linked players who also have a backup password set.
     * Lets them choose between Discord 2FA (use the code we already sent) or their backup password.
     */
    public void showDiscordLoginChoiceDialog(Player player) {
        ActionButton discordBtn = ActionButton.builder(
                Component.text("Discord 2FA", NamedTextColor.BLUE, TextDecoration.BOLD))
            .tooltip(Component.text("Enter the code sent to your Discord DMs", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        showCodeVerificationDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();

        ActionButton passwordBtn = ActionButton.builder(
                Component.text("Use password", NamedTextColor.GOLD, TextDecoration.BOLD))
            .tooltip(Component.text("Use your backup password instead", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        showBackupPasswordDialogFor2FA(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();

        ActionButton cancelBtn = ActionButton.builder(Component.text("Cancel", NamedTextColor.RED))
            .tooltip(Component.text("Leave the server"))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        if (joinListener != null) joinListener.kickUnverifiedPlayer(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Authentication Required", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(List.of(
                    DialogBody.plainMessage(Component.text(
                        "A verification code was sent to your Discord DMs.\n" +
                        "Choose how you'd like to log in.", NamedTextColor.GRAY))
                ))
                .build())
            .type(DialogType.multiAction(List.of(discordBtn, passwordBtn, cancelBtn)).columns(2).build())
        );

        player.showDialog(dialog);
    }

    /** Clear stored keypad/text state (call on player quit). */
    public void clearEnteredCode(java.util.UUID uuid) {
        enteredCodes.remove(uuid);
        lastResendTimes.remove(uuid);
        pendingPasswordResets.remove(uuid);
        pendingBackupPasswords.remove(uuid);
        dashboardContextPlayers.remove(uuid);
    }

    // ── Keypad mode ───────────────────────────────────────────────────────────

    private void renderCodeKeypad(Player player, String current, String errorMsg) {
        // ● for entered digits, ○ for remaining
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) display.append("  ");
            display.append(i < current.length() ? "●" : "○");
        }

        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(DialogBody.plainMessage(Component.text(
            "Check your Discord DMs for a 4-digit code.", NamedTextColor.GRAY)));
        bodies.add(DialogBody.plainMessage(Component.empty()));
        bodies.add(DialogBody.plainMessage(
            Component.text(display.toString(), NamedTextColor.WHITE, TextDecoration.BOLD)));
        if (errorMsg != null) {
            bodies.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }

        // Row 1-3: digits 1-9  |  Row 4: ⌫ 0 ✓  |  Row 5: Type code  Resend  [spacer]  |  Row 6: No password set
        List<ActionButton> buttons = new ArrayList<>();
        for (int d = 1; d <= 9; d++) buttons.add(buildDigitButton(String.valueOf(d), current));
        buttons.add(buildBackspaceButton(current));
        buttons.add(buildDigitButton("0", current));
        buttons.add(buildSubmitFromKeypadButton(current));
        buttons.add(buildSwitchToTextButton(player));
        buttons.add(buildResendButton(player));
        // Spacer at position 15 (row 5, col 3) pushes the password button onto its own row
        buttons.add(ActionButton.builder(Component.empty()).width(40)
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        String cur = enteredCodes.getOrDefault(p.getUniqueId(), "");
                        renderCodeKeypad(p, cur, null);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()))
            .build());
        buttons.add(buildPasswordFallbackButton(player, 120));

        final List<DialogBody> fb = bodies;
        final List<ActionButton> fbt = buttons;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Enter Verification Code", NamedTextColor.BLUE, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(fb)
                .build())
            .type(DialogType.multiAction(fbt).columns(3).build())
        );
        player.showDialog(dialog);
    }

    // ── Text-field mode ───────────────────────────────────────────────────────

    private void renderCodeTextField(Player player, String errorMsg) {
        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(DialogBody.plainMessage(Component.text(
            "Check your Discord DMs for a 4-digit code.", NamedTextColor.GRAY)));
        if (errorMsg != null) {
            bodies.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }

        ActionButton submitBtn = ActionButton.builder(
                Component.text("✓ Submit", NamedTextColor.GREEN, TextDecoration.BOLD))
            .width(150)
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    String typed = view.getText("code");
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        if (typed == null || !typed.matches("\\d{4}")) {
                            renderCodeTextField(p, "Please enter exactly 4 digits.");
                            return;
                        }
                        handleCodeSubmission(p, typed);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(submitBtn);
        buttons.add(buildSwitchToKeypadButton(player));
        buttons.add(buildPasswordFallbackButton(player, 150));
        buttons.add(buildResendButton(player));

        final List<DialogBody> fb = bodies;
        final List<ActionButton> fbt = buttons;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Enter Verification Code", NamedTextColor.BLUE, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(fb)
                .inputs(List.of(
                    DialogInput.text("code", Component.text("4-digit code"))
                        .maxLength(4).build()
                ))
                .build())
            .type(DialogType.multiAction(fbt).columns(2).build())
        );
        player.showDialog(dialog);
    }

    // ── Backup-password fallback (for Discord-linked accounts) ────────────────

    private void showBackupPasswordDialogFor2FA(Player player) {
        showBackupPasswordDialogFor2FA(player, null);
    }

    private void showBackupPasswordDialogFor2FA(Player player, String errorMsg) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
            "Enter your backup password to skip the Discord 2FA code.", NamedTextColor.WHITE)));
        if (errorMsg != null) {
            body.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }
        final List<DialogBody> fb = body;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Use Backup Password", NamedTextColor.GOLD, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(fb)
                .inputs(List.of(
                    DialogInput.text("password", Component.text("Backup password")).build()
                ))
                .build())
            .type(DialogType.multiAction(List.of(
                ActionButton.builder(Component.text("← Back to code", NamedTextColor.GRAY))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                showCodeVerificationDialog(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("✓ Verify", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String pw = view.getText("password");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handleBackupPasswordFor2FA(p, pw);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Reset password", NamedTextColor.YELLOW))
                    .tooltip(Component.text("Forgot your password? Verify via Discord code to reset it", NamedTextColor.GRAY))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handleResetPasswordRequest(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build()
            )).build())
        );
        player.showDialog(dialog);
    }

    private void handleBackupPasswordFor2FA(Player player, String password) {
        if (!plugin.getConfig().getBoolean("enable-backup-password", true)) {
            player.sendMessage(Component.text("Backup passwords are disabled on this server.", NamedTextColor.RED));
            showCodeVerificationDialog(player);
            return;
        }
        if (password == null || password.isBlank()) {
            showBackupPasswordDialogFor2FA(player);
            return;
        }
        if (!passwordManager.hasPassword(player.getUniqueId()) ||
                !passwordManager.verifyPassword(player.getUniqueId(), password)) {
            int maxAttempts = joinListener != null ? joinListener.getMaxLoginAttempts() : plugin.getConfig().getInt("max-login-attempts", 3);
            int failedAttempts = joinListener != null ? joinListener.incrementFailedLoginAttempts(player.getUniqueId()) : 1;

            if (maxAttempts > 0 && failedAttempts >= maxAttempts) {
                player.sendMessage(Component.text("Too many failed login attempts.", NamedTextColor.RED));
                if (joinListener != null) {
                    joinListener.kickForFailedLoginAttempts(player);
                } else {
                    player.kick(Component.text("Too many failed login attempts."));
                }
                return;
            }

            if (maxAttempts > 0) {
                int remainingAttempts = Math.max(0, maxAttempts - failedAttempts);
                showBackupPasswordDialogFor2FA(player, "Incorrect password! " + remainingAttempts + " attempt(s) remaining.");
            } else {
                showBackupPasswordDialogFor2FA(player, "Incorrect password! Please try again.");
            }
            return;
        }
        if (joinListener != null) {
            joinListener.resetFailedLoginAttempts(player.getUniqueId());
        }
        // Correct — save using the pending verification's data, preserving all Discord IDs
        Verification pending = verificationManager.getPending(player.getUniqueId());
        if (pending != null) {
            PlayerDataManager.PlayerRecord existing = dataManager.getRecord(player.getUniqueId());
            java.util.List<String> ids = (existing != null && !existing.discordIds.isEmpty())
                ? new java.util.ArrayList<>(existing.discordIds)
                : java.util.Collections.singletonList(pending.discordId);
            boolean forceV = existing != null && existing.forceVerify;
            String saveIp = forceV ? "LOGGED_OUT" : pending.ip;
            dataManager.setRecord(new PlayerDataManager.PlayerRecord(player.getUniqueId(), ids, saveIp, forceV));
            verificationManager.removePending(player.getUniqueId());
        }
        player.sendMessage(lang.getComponent("verification-success"));
        plugin.getLogger().info(player.getName() + " verified via backup password (2FA bypass).");
        if (joinListener != null) {
            joinListener.restorePlayer(player);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    // ── Button builders ───────────────────────────────────────────────────────

    private ActionButton buildDigitButton(String digit, String current) {
        boolean canAdd = current.length() < 4;
        return ActionButton.builder(
                Component.text(digit,
                    canAdd ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY,
                    TextDecoration.BOLD))
            .width(40)
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        String cur = enteredCodes.getOrDefault(p.getUniqueId(), "");
                        if (cur.length() < 4) cur = cur + digit;
                        if (cur.length() == 4) {
                            enteredCodes.remove(p.getUniqueId());
                            handleCodeSubmission(p, cur);
                        } else {
                            enteredCodes.put(p.getUniqueId(), cur);
                            renderCodeKeypad(p, cur, null);
                        }
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();
    }

    private ActionButton buildBackspaceButton(String current) {
        boolean canDelete = !current.isEmpty();
        return ActionButton.builder(
                Component.text("⌫",
                    canDelete ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY,
                    TextDecoration.BOLD))
            .width(40)
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        String cur = enteredCodes.getOrDefault(p.getUniqueId(), "");
                        if (!cur.isEmpty()) cur = cur.substring(0, cur.length() - 1);
                        enteredCodes.put(p.getUniqueId(), cur);
                        renderCodeKeypad(p, cur, null);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();
    }

    private ActionButton buildSubmitFromKeypadButton(String current) {
        boolean ready = current.length() == 4;
        return ActionButton.builder(
                ready
                    ? Component.text("Submit", NamedTextColor.GREEN, TextDecoration.BOLD)
                    : Component.text("Submit", NamedTextColor.DARK_GRAY))
            .width(40)
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        String cur = enteredCodes.getOrDefault(p.getUniqueId(), "");
                        if (cur.length() < 4) {
                            renderCodeKeypad(p, cur, "Enter all 4 digits first.");
                            return;
                        }
                        enteredCodes.remove(p.getUniqueId());
                        handleCodeSubmission(p, cur);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();
    }

    private ActionButton buildSwitchToTextButton(Player player) {
        return ActionButton.builder(Component.text("Type code", NamedTextColor.AQUA))
            .width(80)
            .tooltip(Component.text("Switch to typing the code manually", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        dataManager.setPreferKeypad(p.getUniqueId(), false);
                        enteredCodes.remove(p.getUniqueId());
                        renderCodeTextField(p, null);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();
    }

    private ActionButton buildSwitchToKeypadButton(Player player) {
        return ActionButton.builder(Component.text("Keypad", NamedTextColor.AQUA))
            .width(80)
            .tooltip(Component.text("Switch to the on-screen keypad", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        dataManager.setPreferKeypad(p.getUniqueId(), true);
                        enteredCodes.put(p.getUniqueId(), "");
                        renderCodeKeypad(p, "", null);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();
    }

    private ActionButton buildResendButton(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastResendTimes.get(player.getUniqueId());
        long elapsed = last != null ? now - last : Long.MAX_VALUE;
        boolean onCooldown = elapsed < 10_000L;
        int secsLeft = onCooldown ? (int) Math.ceil((10_000L - elapsed) / 1000.0) : 0;
        return ActionButton.builder(
                onCooldown
                    ? Component.text("Resend (" + secsLeft + "s)", NamedTextColor.GRAY)
                    : Component.text("Resend code", NamedTextColor.WHITE))
            .width(80)
            .tooltip(onCooldown
                ? Component.text("Please wait before resending", NamedTextColor.GRAY)
                : Component.text("Send the code to your Discord DMs again", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        handleResend(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();
    }

    private void handleResend(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastResendTimes.get(player.getUniqueId());
        if (last != null && now - last < 10_000L) {
            long secsLeft = (long) Math.ceil((10_000L - (now - last)) / 1000.0);
            String msg = "Please wait " + secsLeft + " second" + (secsLeft == 1 ? "" : "s") + " before resending.";
            enteredCodes.put(player.getUniqueId(), "");
            if (dataManager.getPreferKeypad(player.getUniqueId())) renderCodeKeypad(player, "", msg);
            else renderCodeTextField(player, msg);
            return;
        }
        Verification pending = verificationManager.getPending(player.getUniqueId());
        if (pending == null) {
            player.sendMessage(Component.text("No pending verification found. Please start over.", NamedTextColor.RED));
            showDiscordSetupDialog(player);
            return;
        }
        String ip = joinListener != null ? joinListener.getPlayerIp(player) : pending.ip;
        verificationManager.createVerification(player.getUniqueId(), pending.discordId, ip);
        lastResendTimes.put(player.getUniqueId(), now);
        player.sendMessage(Component.text("Verification code resent to your Discord DMs.", NamedTextColor.GREEN));
        enteredCodes.put(player.getUniqueId(), "");
        if (dataManager.getPreferKeypad(player.getUniqueId())) renderCodeKeypad(player, "", null);
        else renderCodeTextField(player, null);
    }

    private ActionButton buildPasswordFallbackButton(Player player, int width) {
        boolean available = plugin.getConfig().getBoolean("enable-backup-password", true)
            && passwordManager.hasPassword(player.getUniqueId());
        return ActionButton.builder(
                available
                    ? Component.text("Use password", NamedTextColor.YELLOW)
                    : Component.text("No password set", NamedTextColor.GRAY))
            .width(width)
            .tooltip(available
                ? Component.text("Use your backup password instead of the 2FA code", NamedTextColor.GRAY)
                : Component.text("Set a backup password with /password set", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        if (available) {
                            showBackupPasswordDialogFor2FA(p);
                        } else {
                            showSetupBackupPasswordDialog(p);
                        }
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            ))
            .build();
    }

    private void handleCodeSubmission(Player player, String code) {
        if (verificationManager.verifyCode(player.getUniqueId(), code)) {
            if (joinListener != null) {
                joinListener.resetFailedLoginAttempts(player.getUniqueId());
            }
            boolean fromDashboard = dashboardContextPlayers.remove(player.getUniqueId());
            if (pendingPasswordResets.remove(player.getUniqueId())) {
                // Came here via "Reset password" — show password reset setup instead of logging in
                showPasswordResetSetupDialog(player);
                return;
            }
            if (pendingPasswordRemovals.remove(player.getUniqueId())) {
                // Came here via "Remove Password" Discord confirmation
                passwordManager.removePassword(player.getUniqueId());
                player.sendMessage(Component.text("Backup password removed.", NamedTextColor.GREEN));
                plugin.getLogger().info(player.getName() + " removed their backup password via Discord code.");
                if (fromDashboard) {
                    showMainDashboardDialog(player);
                } else {
                    if (joinListener != null) joinListener.restorePlayer(player);
                    else { player.setGameMode(GameMode.SURVIVAL); player.setFlying(false); player.setAllowFlight(false); }
                }
                return;
            }
            String newBackupPw = pendingBackupPasswords.remove(player.getUniqueId());
            if (newBackupPw != null) {
                // Came here via "No password set" setup — save the password then continue
                passwordManager.setPassword(player.getUniqueId(), newBackupPw);
                player.sendMessage(lang.getComponent("verification-success"));
                player.sendMessage(Component.text("Backup password saved! You can use it next time you log in.", NamedTextColor.GREEN));
                plugin.getLogger().info(player.getName() + " set up a backup password and verified via Discord code.");
                if (!fromDashboard) {
                    if (joinListener != null) joinListener.restorePlayer(player);
                    else { player.setGameMode(GameMode.SURVIVAL); player.setFlying(false); player.setAllowFlight(false); }
                } else {
                    showMainDashboardDialog(player);
                }
                return;
            }
            player.sendMessage(lang.getComponent("verification-success"));
            plugin.getLogger().info(player.getName() + " verified via keypad dialog.");
            if (!fromDashboard) {
                if (joinListener != null) joinListener.restorePlayer(player);
                else { player.setGameMode(GameMode.SURVIVAL); player.setFlying(false); player.setAllowFlight(false); }
            } else {
                showMainDashboardDialog(player);
            }
        } else {
            int maxAttempts = joinListener != null ? joinListener.getMaxLoginAttempts() : plugin.getConfig().getInt("max-login-attempts", 3);
            int failedAttempts = joinListener != null ? joinListener.incrementFailedLoginAttempts(player.getUniqueId()) : 1;

            if (maxAttempts > 0 && failedAttempts >= maxAttempts) {
                player.sendMessage(Component.text("Too many failed login attempts.", NamedTextColor.RED));
                if (joinListener != null) {
                    joinListener.kickForFailedLoginAttempts(player);
                } else {
                    player.kick(Component.text("Too many failed login attempts."));
                }
                return;
            }

            String retryMessage = "Incorrect code! Try again.";
            String dialogMessage = "Incorrect code — please try again.";
            if (maxAttempts > 0) {
                int remainingAttempts = Math.max(0, maxAttempts - failedAttempts);
                retryMessage = "Incorrect code! " + remainingAttempts + " attempt(s) remaining.";
                dialogMessage = "Incorrect code — " + remainingAttempts + " attempt(s) remaining.";
            }

            player.sendMessage(Component.text(retryMessage, NamedTextColor.RED));
            enteredCodes.put(player.getUniqueId(), "");
            if (dataManager.getPreferKeypad(player.getUniqueId())) {
                renderCodeKeypad(player, "", dialogMessage);
            } else {
                renderCodeTextField(player, dialogMessage);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal submission handlers
    // ──────────────────────────────────────────────────────────────────────────

    private void handleUsernameSearch(Player player, String query) {
        // Run the HTTP requests off the main thread to avoid blocking the server
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            java.util.List<DiscordService.GuildMember> members =
                plugin.getDiscordService().searchGuildMembers(query);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (members.isEmpty()) {
                    showDiscordUsernameSearchDialog(player,
                        "No accounts found for \"" + query + "\". Try a different name or join the Discord server first.");
                } else {
                    showDiscordSearchResultsDialog(player, members, query);
                }
            });
        });
    }

    private void handleDiscordIdSubmission(Player player, String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            player.sendMessage(Component.text("Please enter your Discord User ID.", NamedTextColor.RED));
            showDiscordSetupDialog(player);
            return;
        }

        String discordId = extractDiscordId(rawInput.trim());
        if (discordId == null) {
            player.sendMessage(Component.text(
                "Invalid Discord User ID! It must be 17–19 digits.", NamedTextColor.RED));
            player.sendMessage(Component.text(
                "(Settings → Advanced → Developer Mode, then right-click your name → Copy User ID)",
                NamedTextColor.GRAY));
            showDiscordSetupDialog(player);
            return;
        }

        String currentIp = joinListener != null ? joinListener.getPlayerIp(player) : "unknown";

        // Create the verification — this sends the code to Discord DMs
        Verification v = verificationManager.createVerification(player.getUniqueId(), discordId, currentIp);

        if (v != null && v.code.equals("MAX_ACCOUNTS_REACHED")) {
            int maxAccounts = plugin.getConfig().getInt("max-accounts-per-discord", 0);
            java.util.List<String> names = dataManager.getPlayerNamesByDiscordId(discordId);
            player.sendMessage(lang.getComponent("max-accounts-reached",
                java.util.Map.of("max", String.valueOf(maxAccounts),
                                 "accounts", String.join(", ", names))));
            if (joinListener != null) joinListener.kickUnverifiedPlayer(player);

        } else if (v != null) {
            // Code sent — show keypad to enter it
            player.sendMessage(lang.getComponent("verification-code-sent"));
            showCodeVerificationDialog(player);

        } else {
            // DM failed
            String discordInvite = plugin.getConfig().getString("discord-invite",
                "https://discord.gg/YOUR_INVITE_CODE");
            player.sendMessage(lang.getComponent("dm-failed"));
            player.sendMessage(lang.getComponent("dm-failed-instructions"));
            player.sendMessage(Component.text(discordInvite, NamedTextColor.AQUA));
            if (joinListener != null) joinListener.kickUnverifiedPlayer(player);
        }
    }

    private void handlePasswordSubmission(Player player, String password, String confirm) {
        if (password == null || password.isBlank() || confirm == null || confirm.isBlank()) {
            showPasswordSetupDialog(player, "Please fill in both password fields.");
            return;
        }
        if (password.length() < 8) {
            showPasswordSetupDialog(player, "Password must be at least 8 characters long!");
            return;
        }
        if (!password.equals(confirm)) {
            showPasswordSetupDialog(player, "Passwords don't match! Please try again.");
            return;
        }

        // Persist
        passwordManager.setPassword(player.getUniqueId(), password);
        String currentIp = joinListener != null ? joinListener.getPlayerIp(player) : "0.0.0.0";
        dataManager.setRecord(new PlayerDataManager.PlayerRecord(
            player.getUniqueId(), "PASSWORD_ONLY", currentIp));

        player.sendMessage(lang.getComponent("password-registration-success"));
        plugin.getLogger().info(player.getName() + " registered with password-only authentication.");

        if (joinListener != null) {
            joinListener.restorePlayer(player);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────────────
    // Password reset flow (triggered from backup-password dialog)
    // ──────────────────────────────────────────────────────────────────────────────

    private void handleResetPasswordRequest(Player player) {
        pendingPasswordResets.add(player.getUniqueId());
        enteredCodes.put(player.getUniqueId(), "");
        // Send a fresh code so the player must prove Discord access
        Verification pending = verificationManager.getPending(player.getUniqueId());
        String discordId = pending != null ? pending.discordId : null;
        if (discordId == null) {
            PlayerDataManager.PlayerRecord rec = dataManager.getRecord(player.getUniqueId());
            if (rec != null && !"PASSWORD_ONLY".equals(rec.discordId)) discordId = rec.discordId;
        }
        if (discordId != null) {
            String ip = joinListener != null ? joinListener.getPlayerIp(player) : "unknown";
            verificationManager.createVerification(player.getUniqueId(), discordId, ip);
            lastResendTimes.put(player.getUniqueId(), System.currentTimeMillis());
            player.sendMessage(lang.getComponent("verification-code-sent"));
        } else {
            player.sendMessage(Component.text(
                "Verify with your Discord code to reset your backup password.", NamedTextColor.YELLOW));
        }
        showCodeVerificationDialog(player);
    }

    public void showSetupBackupPasswordDialog(Player player) {
        showSetupBackupPasswordDialog(player, null);
    }

    private void showSetupBackupPasswordDialog(Player player, String errorMsg) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
            "Set a backup password to log in without Discord next time.\n" +
            "You'll confirm with a new Discord code (8+ characters).", NamedTextColor.WHITE)));
        if (errorMsg != null) {
            body.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }
        final List<DialogBody> fb = body;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Set Backup Password", NamedTextColor.GOLD, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(fb)
                .inputs(List.of(
                    DialogInput.text("password", Component.text("New password (min 8 characters)")).build(),
                    DialogInput.text("confirm",  Component.text("Confirm password")).build()
                ))
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(Component.text("\u2190 Back", NamedTextColor.RED))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                if (dashboardContextPlayers.contains(p.getUniqueId())) {
                                    dashboardContextPlayers.remove(p.getUniqueId());
                                    showMainDashboardDialog(p);
                                } else {
                                    showCodeVerificationDialog(p);
                                }
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Confirm & verify", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .tooltip(Component.text("A new Discord code will be sent to confirm", NamedTextColor.GRAY))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String pw = view.getText("password");
                            String cf = view.getText("confirm");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handleSetupBackupPasswordSubmit(p, pw, cf);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build()
            ))
        );
        player.showDialog(dialog);
    }

    private void handleSetupBackupPasswordSubmit(Player player, String password, String confirm) {
        if (password == null || password.isBlank() || confirm == null || confirm.isBlank()) {
            showSetupBackupPasswordDialog(player, "Please fill in both fields.");
            return;
        }
        if (password.length() < 8) {
            showSetupBackupPasswordDialog(player, "Password must be at least 8 characters long!");
            return;
        }
        if (!password.equals(confirm)) {
            showSetupBackupPasswordDialog(player, "Passwords don't match! Please try again.");
            return;
        }
        // Determine Discord ID for sending a fresh code
        Verification pending = verificationManager.getPending(player.getUniqueId());
        String discordId = pending != null ? pending.discordId : null;
        if (discordId == null) {
            PlayerDataManager.PlayerRecord rec = dataManager.getRecord(player.getUniqueId());
            if (rec != null && !"PASSWORD_ONLY".equals(rec.discordId)) discordId = rec.discordId;
        }
        if (discordId == null) {
            showSetupBackupPasswordDialog(player, "Could not find your Discord account. Please re-link.");
            return;
        }
        // Store the chosen password and send a fresh verification code
        pendingBackupPasswords.put(player.getUniqueId(), password);
        String ip = joinListener != null ? joinListener.getPlayerIp(player) : "unknown";
        verificationManager.createVerification(player.getUniqueId(), discordId, ip);
        lastResendTimes.put(player.getUniqueId(), System.currentTimeMillis());
        enteredCodes.put(player.getUniqueId(), "");
        player.sendMessage(lang.getComponent("verification-code-sent"));
        showCodeVerificationDialog(player);
    }

    private void showPasswordResetSetupDialog(Player player) {
        showPasswordResetSetupDialog(player, null);
    }

    private void showPasswordResetSetupDialog(Player player, String errorMsg) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
            "Discord verification successful! Set your new backup password.\n" +
            "Requirements: 8+ characters, both fields must match.", NamedTextColor.WHITE)));
        if (errorMsg != null) {
            body.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }
        final List<DialogBody> fb = body;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Reset Backup Password", NamedTextColor.GOLD, TextDecoration.BOLD))
                .canCloseWithEscape(false)
                .body(fb)
                .inputs(List.of(
                    DialogInput.text("password",
                        Component.text("New password (min 8 characters)")).build(),
                    DialogInput.text("confirm",
                        Component.text("Confirm new password")).build()
                ))
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(Component.text("Cancel", NamedTextColor.RED))
                    .tooltip(Component.text("Leave the server"))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                if (joinListener != null) joinListener.kickUnverifiedPlayer(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Save Password", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String pw = view.getText("password");
                            String cf = view.getText("confirm");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handlePasswordReset(p, pw, cf);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build()
            ))
        );
        player.showDialog(dialog);
    }

    private void handlePasswordReset(Player player, String password, String confirm) {
        if (password == null || password.isBlank() || confirm == null || confirm.isBlank()) {
            showPasswordResetSetupDialog(player, "Please fill in both fields.");
            return;
        }
        if (password.length() < 8) {
            showPasswordResetSetupDialog(player, "Password must be at least 8 characters long!");
            return;
        }
        if (!password.equals(confirm)) {
            showPasswordResetSetupDialog(player, "Passwords don't match! Please try again.");
            return;
        }
        passwordManager.setPassword(player.getUniqueId(), password);
        player.sendMessage(Component.text("Backup password reset successfully!", NamedTextColor.GREEN));
        plugin.getLogger().info(player.getName() + " reset their backup password.");
        if (joinListener != null) {
            joinListener.restorePlayer(player);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────────

    /** Extracts a numeric Discord snowflake ID from plain text or {@code <@ID>} mention format. */
    private String extractDiscordId(String input) {
        if (input.startsWith("<@") && input.endsWith(">")) {
            String inner = input.substring(2, input.length() - 1);
            if (inner.startsWith("!")) inner = inner.substring(1);
            if (inner.matches("\\d+")) return inner;
        }
        if (input.startsWith("@")) return null; // username format — no ID extractable
        if (input.matches("\\d{17,20}")) return input;
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // /discordauth Dashboard
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Remove-password flow that sends a Discord code first (if Discord is linked).
     * Falls back to simple confirmation dialog if no Discord account is linked.
     */
    private void handleRemovePasswordRequest(Player player) {
        java.util.List<String> discordIds = dataManager.getDiscordIds(player.getUniqueId());
        if (discordIds.isEmpty()) {
            // No Discord account — fall back to simple confirmation
            showRemovePasswordConfirmation(player);
            return;
        }
        pendingPasswordRemovals.add(player.getUniqueId());
        dashboardContextPlayers.add(player.getUniqueId());
        enteredCodes.put(player.getUniqueId(), "");
        String discordId = discordIds.get(0);
        String ip = joinListener != null ? joinListener.getPlayerIp(player) : "unknown";
        verificationManager.createVerification(player.getUniqueId(), discordId, ip);
        lastResendTimes.put(player.getUniqueId(), System.currentTimeMillis());
        player.sendMessage(Component.text(
            "A verification code has been sent to your Discord — enter it to confirm password removal.",
            NamedTextColor.YELLOW));
        showCodeVerificationDialog(player);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Change-password dialog (dashboard)
    // ──────────────────────────────────────────────────────────────────────────

    private void showChangePasswordDialog(Player player) {
        showChangePasswordDialog(player, null);
    }

    private void showChangePasswordDialog(Player player, String errorMsg) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
            "Enter your current password, then choose a new one (8+ characters).",
            NamedTextColor.WHITE)));
        if (errorMsg != null) {
            body.add(DialogBody.plainMessage(Component.text(errorMsg, NamedTextColor.RED)));
        }
        final List<DialogBody> fb = body;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Change Password", NamedTextColor.GOLD, TextDecoration.BOLD))
                .canCloseWithEscape(true)
                .body(fb)
                .inputs(List.of(
                    DialogInput.text("current", Component.text("Current password")).build(),
                    DialogInput.text("password", Component.text("New password (min 8 characters)")).build(),
                    DialogInput.text("confirm",  Component.text("Confirm new password")).build()
                ))
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(Component.text("\u2190 Cancel", NamedTextColor.RED))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                showMainDashboardDialog(p);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build(),
                ActionButton.builder(Component.text("Save", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            String cur = view.getText("current");
                            String pw  = view.getText("password");
                            String cf  = view.getText("confirm");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                handleChangePasswordSubmit(p, cur, pw, cf);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    ))
                    .build()
            ))
        );
        player.showDialog(dialog);
    }

    private void handleChangePasswordSubmit(Player player, String current, String newPw, String confirm) {
        if (current == null || current.isBlank()) {
            showChangePasswordDialog(player, "Please enter your current password.");
            return;
        }
        if (newPw == null || newPw.isBlank() || confirm == null || confirm.isBlank()) {
            showChangePasswordDialog(player, "Please fill in all fields.");
            return;
        }
        if (newPw.length() < 8) {
            showChangePasswordDialog(player, "New password must be at least 8 characters long.");
            return;
        }
        if (!newPw.equals(confirm)) {
            showChangePasswordDialog(player, "New passwords don\u2019t match. Please try again.");
            return;
        }
        if (!passwordManager.verifyPassword(player.getUniqueId(), current)) {
            showChangePasswordDialog(player, "Incorrect current password. Please try again.");
            return;
        }
        passwordManager.setPassword(player.getUniqueId(), newPw);
        player.sendMessage(Component.text("Password changed successfully!", NamedTextColor.GREEN));
        plugin.getLogger().info(player.getName() + " changed their backup password.");
        showMainDashboardDialog(player);
    }



    /**
     * Opens the main /discordauth management dialog for an already-verified player.
     * Asynchronously resolves Discord usernames, then builds the dialog on the main thread.
     */
    public void showMainDashboardDialog(Player player) {
        PlayerDataManager.PlayerRecord record = dataManager.getRecord(player.getUniqueId());
        java.util.List<String> ids = record != null
            ? java.util.Collections.unmodifiableList(record.discordIds)
            : java.util.Collections.emptyList();

        if (ids.isEmpty()) {
            buildMainDashboardDialog(player, java.util.Collections.emptyMap());
        } else {
            final java.util.List<String> snapshotIds = new java.util.ArrayList<>(ids);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                java.util.Map<String, String> usernameMap = new java.util.LinkedHashMap<>();
                for (String id : snapshotIds) {
                    DiscordService.GuildMember m = plugin.getDiscordService().getUserById(id);
                    usernameMap.put(id, m != null ? m.displayLabel() : id);
                }
                plugin.getServer().getScheduler().runTask(plugin,
                    () -> buildMainDashboardDialog(player, usernameMap));
            });
        }
    }

    private void buildMainDashboardDialog(Player player, java.util.Map<String, String> usernameMap) {
        PlayerDataManager.PlayerRecord record = dataManager.getRecord(player.getUniqueId());
        java.util.List<String> discordIds = record != null ? record.discordIds : java.util.Collections.emptyList();
        boolean hasPassword = passwordManager.hasPassword(player.getUniqueId());
        boolean isForceVerify = record != null && record.forceVerify;
        boolean passwordOnly = record != null && record.passwordOnly;
        String version = plugin.getDescription().getVersion();

        List<ActionButton> buttons = new ArrayList<>();

        // ── Info section ────────────────────────────────────────────────────
        buttons.add(ActionButton.builder(
                Component.text("DiscordAuth v" + version + "  \u00b7  by theTWIXhunter", NamedTextColor.GOLD))
            .width(300).action(dashNoOp(player)).build());

        buttons.add(ActionButton.builder(
                Component.text("Help & Documentation \u2192", NamedTextColor.AQUA))
            .width(300)
            .action(DialogAction.staticAction(
                net.kyori.adventure.text.event.ClickEvent.openUrl(
                    "https://thetwixhunter.nekoweb.org/discordauth/")))
            .build());

        // ── Linked Discord Accounts section ─────────────────────────────────
        buttons.add(dashSectionHeader("Linked Discord Accounts"));

        if (discordIds.isEmpty()) {
            buttons.add(ActionButton.builder(
                    Component.text("(no Discord accounts linked)", NamedTextColor.DARK_GRAY))
                .width(300).action(dashNoOp(player)).build());
        } else {
            for (String discordId : discordIds) {
                final String fId = discordId;
                String displayName = usernameMap.getOrDefault(discordId, discordId);
                buttons.add(ActionButton.builder(
                        Component.text(displayName, NamedTextColor.WHITE)
                            .append(Component.text("  [\u2715 Remove]", NamedTextColor.RED)))
                    .width(300)
                    .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (!(audience instanceof Player p)) return;
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                p.closeInventory();
                                showRemoveDiscordConfirmation(p, fId);
                            });
                        },
                        ClickCallback.Options.builder().uses(1).build()
                    )).build());
            }
        }

        buttons.add(ActionButton.builder(
                Component.text(passwordOnly ? "+ Link a Discord Account" : "+ Add Discord Account",
                    NamedTextColor.GREEN))
            .width(300)
            .tooltip(Component.text("Search Discord by username and link a new account", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        dashboardContextPlayers.add(p.getUniqueId());
                        showDiscordUsernameSearchDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            )).build());

        // ── Account Security section ─────────────────────────────────────────
        buttons.add(dashSectionHeader("Account Security"));

        if (hasPassword) {
            buttons.add(ActionButton.builder(Component.text("Change Password", NamedTextColor.YELLOW))
                .width(300)
                .action(DialogAction.customClick(
                    (view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            p.closeInventory();
                            showChangePasswordDialog(p);
                        });
                    },
                    ClickCallback.Options.builder().uses(1).build()
                )).build());

            buttons.add(ActionButton.builder(Component.text("Remove Password", NamedTextColor.RED))
                .width(300)
                .tooltip(Component.text("Permanently delete your backup password", NamedTextColor.GRAY))
                .action(DialogAction.customClick(
                    (view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            p.closeInventory();
                            handleRemovePasswordRequest(p);
                        });
                    },
                    ClickCallback.Options.builder().uses(1).build()
                )).build());
        } else {
            buttons.add(ActionButton.builder(Component.text("Add Password", NamedTextColor.GREEN))
                .width(300)
                .tooltip(Component.text("Set a backup password to log in without Discord", NamedTextColor.GRAY))
                .action(DialogAction.customClick(
                    (view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            p.closeInventory();
                            dashboardContextPlayers.add(p.getUniqueId());
                            showSetupBackupPasswordDialog(p);
                        });
                    },
                    ClickCallback.Options.builder().uses(1).build()
                )).build());
        }

        // ── Session section ──────────────────────────────────────────────────
        buttons.add(dashSectionHeader("Session"));

        buttons.add(ActionButton.builder(Component.text("Logout", NamedTextColor.RED))
            .width(300)
            .tooltip(Component.text("Require 2FA on your next login (IP forgotten)", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        plugin.getServer().dispatchCommand(p, "discordauth logout");
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            )).build());

        if (isForceVerify) {
            buttons.add(ActionButton.builder(
                    Component.text("Disable Always-Require-2FA", NamedTextColor.GRAY))
                .width(300)
                .tooltip(Component.text("Allow IP trust: skip 2FA when joining from a known IP", NamedTextColor.GRAY))
                .action(DialogAction.customClick(
                    (view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            p.closeInventory();
                            dataManager.setForceVerify(p.getUniqueId(), false);
                            p.sendMessage(Component.text(
                                "Always-require-2FA disabled. Your IP will be trusted after the next login.",
                                NamedTextColor.GREEN));
                        });
                    },
                    ClickCallback.Options.builder().uses(1).build()
                )).build());
        } else {
            buttons.add(ActionButton.builder(Component.text("Always Require 2FA", NamedTextColor.GOLD))
                .width(300)
                .tooltip(Component.text("Every login will require 2FA regardless of IP", NamedTextColor.GRAY))
                .action(DialogAction.customClick(
                    (view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            p.closeInventory();
                            dataManager.setForceVerify(p.getUniqueId(), true);
                            dataManager.updateIp(p.getUniqueId(), "LOGGED_OUT");
                            p.sendMessage(Component.text(
                                "Always-require-2FA enabled. Every login will now require 2FA.",
                                NamedTextColor.YELLOW));
                        });
                    },
                    ClickCallback.Options.builder().uses(1).build()
                )).build());
        }

        final List<ActionButton> fbt = buttons;
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("DiscordAuth", NamedTextColor.GOLD, TextDecoration.BOLD))
                .canCloseWithEscape(true)
                .body(List.of(DialogBody.plainMessage(
                    Component.text("Manage your account settings.", NamedTextColor.GRAY))))
                .build())
            .type(DialogType.multiAction(fbt).columns(1).build())
        );
        player.showDialog(dialog);
    }

    private void showRemoveDiscordConfirmation(Player player, String discordId) {
        String abbr = discordId.length() > 12
            ? discordId.substring(0, 6) + "\u2026" + discordId.substring(discordId.length() - 4)
            : discordId;

        java.util.List<String> remaining = new java.util.ArrayList<>(dataManager.getDiscordIds(player.getUniqueId()));
        remaining.remove(discordId);
        boolean willLoseAuth = remaining.isEmpty() && !passwordManager.hasPassword(player.getUniqueId());
        String warning = willLoseAuth
            ? "\n\n\u26a0 This is your only linked account. You will need to register again on next login."
            : "";

        ActionButton cancelBtn = ActionButton.builder(Component.text("\u2190 Cancel", NamedTextColor.GREEN))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        showMainDashboardDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            )).build();

        ActionButton removeBtn = ActionButton.builder(
                Component.text("Remove", NamedTextColor.RED, TextDecoration.BOLD))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        dataManager.removeDiscordId(p.getUniqueId(), discordId);
                        p.sendMessage(Component.text("Discord account " + abbr + " removed.", NamedTextColor.GREEN));
                        plugin.getLogger().info(p.getName() + " unlinked Discord ID " + discordId);
                        showMainDashboardDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            )).build();

        final List<DialogBody> fb = List.of(
            DialogBody.plainMessage(Component.text("Discord ID: " + abbr, NamedTextColor.WHITE)),
            DialogBody.plainMessage(Component.text(
                "This account will no longer be able to verify your logins." + warning,
                NamedTextColor.GRAY))
        );
        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Remove Discord Account?", NamedTextColor.RED, TextDecoration.BOLD))
                .canCloseWithEscape(true)
                .body(fb)
                .build())
            .type(DialogType.confirmation(cancelBtn, removeBtn))
        );
        player.showDialog(dialog);
    }

    private void showRemovePasswordConfirmation(Player player) {
        ActionButton cancelBtn = ActionButton.builder(Component.text("\u2190 Cancel", NamedTextColor.GREEN))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        showMainDashboardDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            )).build();

        ActionButton removeBtn = ActionButton.builder(
                Component.text("Remove Password", NamedTextColor.RED, TextDecoration.BOLD))
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        passwordManager.removePassword(p.getUniqueId());
                        p.sendMessage(Component.text("Backup password removed.", NamedTextColor.GREEN));
                        plugin.getLogger().info(p.getName() + " removed their backup password.");
                        showMainDashboardDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(1).build()
            )).build();

        Dialog dialog = Dialog.create(b -> b.empty()
            .base(DialogBase.builder(
                    Component.text("Remove Backup Password?", NamedTextColor.RED, TextDecoration.BOLD))
                .canCloseWithEscape(true)
                .body(List.of(DialogBody.plainMessage(Component.text(
                    "Your backup password will be permanently deleted.\n"
                    + "You will only be able to log in via Discord 2FA.",
                    NamedTextColor.GRAY))))
                .build())
            .type(DialogType.confirmation(cancelBtn, removeBtn))
        );
        player.showDialog(dialog);
    }

    /** Non-interactive button whose click reopens the dashboard. */
    private DialogAction dashNoOp(Player player) {
        return DialogAction.customClick(
            (view, audience) -> {
                if (!(audience instanceof Player p)) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    p.closeInventory();
                    showMainDashboardDialog(p);
                });
            },
            ClickCallback.Options.builder().uses(-1).build()
        );
    }

    /** Bold coloured section header button (non-interactive). */
    private ActionButton dashSectionHeader(String title) {
        return ActionButton.builder(
                Component.text("\u2500\u2500 " + title + " \u2500\u2500",
                    NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
            .width(300)
            .action(DialogAction.customClick(
                (view, audience) -> {
                    if (!(audience instanceof Player p)) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        showMainDashboardDialog(p);
                    });
                },
                ClickCallback.Options.builder().uses(-1).build()
            ))
            .build();
    }
}
