package com.namelessmc.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.namelessmc.java_api.NamelessAPI;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestStaffRoleReconciliation {
    private static final long GUILD_ID = 628381132261556229L;
    private static final long MODERATOR_ROLE = 665323333876973589L;
    private static final long ADMIN_ROLE = 665322860578996254L;

    private JsonObject roster(Instant now) {
        JsonObject response = new JsonObject();
        response.addProperty("protocol_version", 1);
        response.addProperty("guild_id", Long.toString(GUILD_ID));
        response.addProperty("generated_at", now.getEpochSecond());
        response.addProperty("complete", true);
        response.addProperty("account_count", 1);
        JsonObject account = new JsonObject();
        account.addProperty("forum_user_id", 22);
        account.addProperty("discord_id", "348173296640196609");
        JsonArray groups = new JsonArray();
        groups.add(3);
        account.add("staff_group_ids", groups);
        JsonArray accounts = new JsonArray();
        accounts.add(account);
        response.add("accounts", accounts);
        return response;
    }

    @Test
    void repairsMissedPromotionAndDemotionOnlyForManagedRoles() {
        Instant now = Instant.parse("2026-09-26T23:00:00Z");
        var account = StaffRoleReconciliation.parseSnapshot(roster(now), GUILD_ID, now).accounts().get(0);
        var unchanged = StaffRoleReconciliation.difference(account, Set.of(MODERATOR_ROLE, 919734331558199338L));
        assertEquals(true, unchanged.empty()); // Shared Moderation Perms never changes a rank.

        var demote = StaffRoleReconciliation.difference(account, Set.of());
        assertArrayEquals(new long[0], demote.add());
        assertArrayEquals(new long[]{MODERATOR_ROLE}, demote.remove());

        var promote = StaffRoleReconciliation.difference(account, Set.of(MODERATOR_ROLE, ADMIN_ROLE));
        assertArrayEquals(new long[]{ADMIN_ROLE}, promote.add());
        assertArrayEquals(new long[0], promote.remove());
    }

    @Test
    void refusesIncompleteStaleAndAmbiguousRostersBeforeAnyWrite() {
        Instant now = Instant.parse("2026-09-26T23:00:00Z");
        JsonObject response = roster(now);
        assertEquals(1, StaffRoleReconciliation.parseSnapshot(response, GUILD_ID, now).accounts().size());

        response.addProperty("complete", false);
        assertThrows(IllegalArgumentException.class,
                () -> StaffRoleReconciliation.parseSnapshot(response, GUILD_ID, now));
        response.addProperty("complete", true);

        response.addProperty("account_count", 2);
        assertThrows(IllegalArgumentException.class,
                () -> StaffRoleReconciliation.parseSnapshot(response, GUILD_ID, now));
        response.addProperty("account_count", 1);

        response.addProperty("generated_at", now.minusSeconds(181).getEpochSecond());
        assertThrows(IllegalArgumentException.class,
                () -> StaffRoleReconciliation.parseSnapshot(response, GUILD_ID, now));
        response.addProperty("generated_at", now.getEpochSecond());

        JsonObject duplicate = response.getAsJsonArray("accounts").get(0).getAsJsonObject().deepCopy();
        duplicate.addProperty("forum_user_id", 23);
        response.getAsJsonArray("accounts").add(duplicate);
        response.addProperty("account_count", 2);
        assertThrows(IllegalArgumentException.class,
                () -> StaffRoleReconciliation.parseSnapshot(response, GUILD_ID, now));
    }

    @Test
    void refusesUnexpectedStaffGroupAndWrongGuild() {
        Instant now = Instant.parse("2026-09-26T23:00:00Z");
        JsonObject response = roster(now);
        response.getAsJsonArray("accounts").get(0).getAsJsonObject()
                .getAsJsonArray("staff_group_ids").add(1); // Owner must remain outside this scope.
        assertThrows(IllegalArgumentException.class,
                () -> StaffRoleReconciliation.parseSnapshot(response, GUILD_ID, now));

        JsonObject wrongGuild = roster(now);
        assertThrows(IllegalArgumentException.class,
                () -> StaffRoleReconciliation.parseSnapshot(wrongGuild, GUILD_ID + 1, now));
    }

    @Test
    void bindsEveryStaffDeltaToTheObservedDiscordIdentity() throws Exception {
        NamelessAPI api = NamelessAPI.builder(URI.create("https://example.invalid/api/v2/").toURL(),
                "test-key").build();
        JsonObject request = DiscordRoleSync.request(348173296640196609L,
                new long[]{MODERATOR_ROLE}, new long[]{ADMIN_ROLE}, api);
        assertEquals("348173296640196609", request.get("discord_id").getAsString());
        assertEquals(MODERATOR_ROLE, request.getAsJsonArray("add").get(0).getAsLong());
        assertEquals(ADMIN_ROLE, request.getAsJsonArray("remove").get(0).getAsLong());
        assertEquals(true, StaffRoleReconciliation.containsManagedRole(new long[]{MODERATOR_ROLE}));
        assertEquals(false, StaffRoleReconciliation.containsManagedRole(new long[]{919734331558199338L}));
    }
}
