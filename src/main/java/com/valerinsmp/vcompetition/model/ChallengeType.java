package com.valerinsmp.vcompetition.model;

import java.util.List;
import java.util.Locale;

public enum ChallengeType {
    MINING,
    WOODCUTTING,
    FISHING,
    SLAYER,
    FARMING;

    public static ChallengeType fromInput(String input) {
        return ChallengeType.valueOf(input.toUpperCase(Locale.ROOT));
    }

    public String displayName() {
        return switch (this) {
            case MINING -> "ᴍɪɴᴇʀɪᴀ";
            case WOODCUTTING -> "ᴛᴀʟᴀ";
            case FISHING -> "ᴘᴇꜱᴄᴀ";
            case SLAYER -> "ᴄᴀᴢᴀ";
            case FARMING -> "ꜰᴀʀᴍᴇᴏ";
        };
    }

    /** Challenge types eligible for random selection. PLAYTIME is excluded. */
    public static List<ChallengeType> randomPool() {
        return List.of(MINING, WOODCUTTING, FISHING, SLAYER, FARMING);
    }
}
