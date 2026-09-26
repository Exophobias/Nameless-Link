package com.namelessmc.bot;

import com.google.gson.JsonObject;
import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.NamelessUser;
import com.namelessmc.java_api.exception.NamelessException;

/** Sends a role delta bound to the Discord identity that was observed by the bot. */
public final class DiscordRoleSync {
    private DiscordRoleSync() { }

    static JsonObject request(long discordId, long[] add, long[] remove, NamelessAPI api) {
        JsonObject body = new JsonObject();
        body.addProperty("discord_id", Long.toString(discordId));
        body.add("add", api.requests().gson().toJsonTree(add));
        body.add("remove", api.requests().gson().toJsonTree(remove));
        return body;
    }

    public static void send(NamelessAPI api, NamelessUser user, long discordId,
                            long[] add, long[] remove) throws NamelessException {
        api.requests().post("discord/id:" + user.id() + "/sync-roles",
                request(discordId, add, remove, api));
    }
}
