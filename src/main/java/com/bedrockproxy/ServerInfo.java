package com.bedrockproxy;

public class ServerInfo {
    public int    protocol   = 686;
    public String version    = "1.26.0";
    public String motdLine1  = "Bedrock Server";
    public String motdLine2  = "Bedrock Server";
    public int    players    = 0;
    public int    maxPlayers = 100;
    public long   guid       = 0;

    /** Rebuild a full MCPE MOTD string with a custom display name. */
    public String buildMotd(String displayName, int port) {
        return "MCPE"
            + ";" + displayName
            + ";" + protocol
            + ";" + version
            + ";" + players
            + ";" + maxPlayers
            + ";" + Math.abs(guid)
            + ";" + displayName
            + ";Survival;1;" + port + ";19133;";
    }
}
