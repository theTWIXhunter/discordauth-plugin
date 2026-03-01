package me.theTWIXhunter.discordauth;

import java.util.UUID;

public class Verification {
    public final UUID playerUuid;
    public final String discordId;
    public final String code;
    public final String ip;
    /** True when this verification links a NEW Discord ID to a player who already has other IDs linked. */
    public final boolean isNewLink;

    public Verification(UUID playerUuid, String discordId, String code, String ip) {
        this(playerUuid, discordId, code, ip, false);
    }

    public Verification(UUID playerUuid, String discordId, String code, String ip, boolean isNewLink) {
        this.playerUuid = playerUuid;
        this.discordId = discordId;
        this.code = code;
        this.ip = ip;
        this.isNewLink = isNewLink;
    }
}
