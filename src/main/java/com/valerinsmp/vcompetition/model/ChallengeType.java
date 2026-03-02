package com.valerinsmp.vcompetition.model;

import java.util.Locale;

public enum ChallengeType {
    MINING,
    WOODCUTTING,
    FISHING,
    SLAYER,
    PLAYTIME;

    public static ChallengeType fromInput(String input) {
        return ChallengeType.valueOf(input.toUpperCase(Locale.ROOT));
    }

    public String displayName() {
        return switch (this) {
            case MINING -> "ᴍɪɴᴇʀɪᴀ";
            case WOODCUTTING -> "ᴛᴀʟᴀ";
            case FISHING -> "ᴘᴇꜱᴄᴀ";
            case SLAYER -> "ᴄᴀᴢᴀ";
            case PLAYTIME -> "ᴛɪᴇᴍᴘᴏ ᴊᴜɢᴀᴅᴏ";
        };
    }
}
