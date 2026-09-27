package com.namelessmc.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import static com.namelessmc.bot.LinkedRoleReconciliation.Action.ADD;
import static com.namelessmc.bot.LinkedRoleReconciliation.Action.NONE;
import static com.namelessmc.bot.LinkedRoleReconciliation.Action.REMOVE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestLinkedRoleReconciliation {
    private static final Instant NOW = Instant.parse("2026-09-27T23:00:00Z");
    private static final String SERVER_ID = "1";
    private static final String LINKED_DISCORD = "348173296640196609";
    private static final String OTHER_DISCORD = "348173296640196610";
    private static final long LINKED_ROLE = LinkedRoleReconciliation.LINKED_ROLE_ID;
    private static final long OWNER_ROLE = 628381360125509644L;
    private static final long STAFF_MANAGER_ROLE = 679012387612786697L;
    private static final long COMMUNITY_MANAGER_ROLE = 1049437778351489124L;

    private JsonObject row(String uuid, int forumId, String discord, String tier) {
        JsonObject row = new JsonObject();
        row.addProperty("uuid", uuid);
        row.addProperty("forum_user_id", forumId);
        if (discord == null) row.add("discord_id", JsonNull.INSTANCE);
        else row.addProperty("discord_id", discord);
        row.addProperty("tier", tier);
        return row;
    }

    private JsonObject roster() throws Exception {
        JsonArray rows = new JsonArray();
        rows.add(row("00000000000000000000000000000001", 10, null, "limited"));
        rows.add(row("c46a1b70141f47ecb73f47b879bf596b", 22, LINKED_DISCORD, "member"));
        JsonObject roster = new JsonObject();
        roster.addProperty("protocol_version", 1);
        roster.addProperty("server_id", SERVER_ID);
        roster.addProperty("generated_at", NOW.getEpochSecond());
        roster.addProperty("revision", revision(rows));
        roster.add("accounts", rows);
        return roster;
    }

    private String revision(JsonArray rows) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(rows.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void grantsOnlyForBothVerifiedLinksAndRevokesAfterEitherLinkIsLost() throws Exception {
        var snapshot = LinkedRoleReconciliation.parseSnapshot(roster(), SERVER_ID, NOW);
        assertEquals(ADD, LinkedRoleReconciliation.action(snapshot, LINKED_DISCORD,
                false, false, Set.of()));
        assertEquals(NONE, LinkedRoleReconciliation.action(snapshot, LINKED_DISCORD,
                false, false, Set.of(LINKED_ROLE)));
        assertEquals(REMOVE, LinkedRoleReconciliation.action(snapshot, OTHER_DISCORD,
                false, false, Set.of(LINKED_ROLE)));
        // The Minecraft-only Limited account has no Discord ID and receives no Linked role.
        assertEquals(NONE, LinkedRoleReconciliation.action(snapshot, OTHER_DISCORD,
                false, false, Set.of()));

        JsonObject afterUnlink = roster();
        afterUnlink.getAsJsonArray("accounts").get(1).getAsJsonObject()
                .add("discord_id", JsonNull.INSTANCE);
        afterUnlink.getAsJsonArray("accounts").get(1).getAsJsonObject()
                .addProperty("tier", "limited");
        afterUnlink.addProperty("revision", revision(afterUnlink.getAsJsonArray("accounts")));
        var unlinked = LinkedRoleReconciliation.parseSnapshot(afterUnlink, SERVER_ID, NOW);
        assertEquals(REMOVE, LinkedRoleReconciliation.action(unlinked, LINKED_DISCORD,
                false, false, Set.of(LINKED_ROLE)));
    }

    @Test
    void leavesGuildOwnerAndOwnerOrManagerRoleHoldersUntouched() throws Exception {
        var snapshot = LinkedRoleReconciliation.parseSnapshot(roster(), SERVER_ID, NOW);
        for (long role : new long[]{OWNER_ROLE, STAFF_MANAGER_ROLE, COMMUNITY_MANAGER_ROLE}) {
            assertEquals(NONE, LinkedRoleReconciliation.action(snapshot, LINKED_DISCORD,
                    false, false, Set.of(role))); // No grant.
            assertEquals(NONE, LinkedRoleReconciliation.action(snapshot, OTHER_DISCORD,
                    false, false, Set.of(role, LINKED_ROLE))); // No removal.
        }
        assertEquals(NONE, LinkedRoleReconciliation.action(snapshot, LINKED_DISCORD,
                false, true, Set.of()));
        assertEquals(NONE, LinkedRoleReconciliation.action(snapshot, OTHER_DISCORD,
                false, true, Set.of(LINKED_ROLE)));
        assertEquals(NONE, LinkedRoleReconciliation.action(snapshot, LINKED_DISCORD,
                true, false, Set.of()));
    }

    @Test
    void refusesStaleIncompleteOrAmbiguousRostersBeforeRoleChanges() throws Exception {
        JsonObject valid = roster();
        assertEquals(1, LinkedRoleReconciliation.parseSnapshot(valid, SERVER_ID, NOW)
                .eligibleAccounts().size());
        assertThrows(IllegalArgumentException.class,
                () -> LinkedRoleReconciliation.parseSnapshot(valid, "another", NOW));

        JsonObject stale = roster();
        stale.addProperty("generated_at", NOW.minusSeconds(181).getEpochSecond());
        assertThrows(IllegalArgumentException.class,
                () -> LinkedRoleReconciliation.parseSnapshot(stale, SERVER_ID, NOW));

        JsonObject truncated = roster();
        truncated.getAsJsonArray("accounts").remove(1);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedRoleReconciliation.parseSnapshot(truncated, SERVER_ID, NOW));

        JsonObject duplicateDiscord = roster();
        duplicateDiscord.getAsJsonArray("accounts").add(
                row("ffffffffffffffffffffffffffffffff", 23, LINKED_DISCORD, "member"));
        duplicateDiscord.addProperty("revision", revision(duplicateDiscord.getAsJsonArray("accounts")));
        assertThrows(IllegalArgumentException.class,
                () -> LinkedRoleReconciliation.parseSnapshot(duplicateDiscord, SERVER_ID, NOW));

        JsonObject inconsistentTier = roster();
        inconsistentTier.getAsJsonArray("accounts").get(1).getAsJsonObject().addProperty("tier", "limited");
        inconsistentTier.addProperty("revision", revision(inconsistentTier.getAsJsonArray("accounts")));
        assertThrows(IllegalArgumentException.class,
                () -> LinkedRoleReconciliation.parseSnapshot(inconsistentTier, SERVER_ID, NOW));
    }

    @Test
    void linkedRoleEventsDoNotFlowBackIntoForumRoleSync() {
        assertArrayEquals(new long[]{665323333876973589L, 919734331558199338L},
                LinkedRoleReconciliation.withoutLinkedRole(new long[]{LINKED_ROLE,
                        665323333876973589L, 919734331558199338L}));
    }

    @Test
    void acceptsUnsignedDiscordSnowflakesUpToTheWebsiteProtocolLimit() throws Exception {
        JsonObject maximum = roster();
        maximum.getAsJsonArray("accounts").get(1).getAsJsonObject()
                .addProperty("discord_id", "18446744073709551615");
        maximum.addProperty("revision", revision(maximum.getAsJsonArray("accounts")));
        assertEquals(ADD, LinkedRoleReconciliation.action(
                LinkedRoleReconciliation.parseSnapshot(maximum, SERVER_ID, NOW),
                "18446744073709551615", false, false, Set.of()));

        maximum.getAsJsonArray("accounts").get(1).getAsJsonObject()
                .addProperty("discord_id", "18446744073709551616");
        maximum.addProperty("revision", revision(maximum.getAsJsonArray("accounts")));
        assertThrows(IllegalArgumentException.class,
                () -> LinkedRoleReconciliation.parseSnapshot(maximum, SERVER_ID, NOW));
    }
}
