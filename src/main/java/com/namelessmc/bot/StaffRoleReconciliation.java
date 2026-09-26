package com.namelessmc.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.namelessmc.bot.connections.BackendStorageException;
import com.namelessmc.bot.listeners.DiscordRoleListener;
import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.NamelessUser;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.java_api.integrations.DetailedIntegrationData;
import com.namelessmc.java_api.integrations.StandardIntegrationTypes;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Repairs missed Discord staff-role events for verified forum accounts. The website owns the
 * complete link and current-group roster; Discord owns the four mapped staff roles. Other groups,
 * including Owner, Manager and Architect, are outside this reconciler's scope.
 */
public final class StaffRoleReconciliation implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(StaffRoleReconciliation.class);
    private static final String ROUTE = "discord/staff-roster";
    private static final int MAX_ACCOUNTS = 5000;
    private static final int MAX_CONFIRMATIONS_PER_PASS = 250;
    private static final long MAX_AGE_SECONDS = 180;
    private static final long MAX_FUTURE_SECONDS = 30;

    // Dedicated Patriam rank roles only. Shared Moderation Perms is deliberately absent.
    private static final long[] ROLE_IDS = {
            665323124895645725L, // Helper
            665323333876973589L, // Moderator
            665322860578996254L, // Administrator
            657096201665249312L  // Senior Administrator
    };
    private static final int[] GROUP_IDS = {8, 3, 7, 6};
    private static final Set<Integer> MANAGED_GROUP_IDS = Set.of(8, 3, 7, 6);
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicInteger NEXT_ACCOUNT_OFFSET = new AtomicInteger();
    public static final StaffRoleReconciliation INSTANCE = new StaffRoleReconciliation();

    private StaffRoleReconciliation() { }

    record Account(int forumUserId, long discordId, Set<Integer> staffGroupIds) { }
    record Snapshot(long guildId, long generatedAt, List<Account> accounts) { }
    record RoleDelta(long[] add, long[] remove) {
        boolean empty() { return add.length == 0 && remove.length == 0; }
    }

    static Snapshot parseSnapshot(JsonObject body, long expectedGuildId, Instant now) {
        if (body == null || !body.keySet().equals(Set.of("protocol_version", "guild_id", "generated_at",
                "complete", "account_count", "accounts"))
                || integer(body.get("protocol_version"), 1, 1) != 1
                || snowflake(body.get("guild_id")) != expectedGuildId) {
            throw new IllegalArgumentException("Invalid staff-roster protocol or guild");
        }
        if (!body.get("complete").isJsonPrimitive()
                || !body.get("complete").getAsJsonPrimitive().isBoolean()
                || !body.get("complete").getAsBoolean()) {
            throw new IllegalArgumentException("Incomplete staff roster");
        }
        long generatedAt = integer(body.get("generated_at"), 1, Long.MAX_VALUE);
        if (generatedAt < now.getEpochSecond() - MAX_AGE_SECONDS
                || generatedAt > now.getEpochSecond() + MAX_FUTURE_SECONDS) {
            throw new IllegalArgumentException("Staff roster is stale or from the future");
        }
        JsonElement accountsValue = body.get("accounts");
        if (accountsValue == null || !accountsValue.isJsonArray()) {
            throw new IllegalArgumentException("Staff roster has no accounts array");
        }
        JsonArray accountsJson = accountsValue.getAsJsonArray();
        if (accountsJson.size() > MAX_ACCOUNTS) {
            throw new IllegalArgumentException("Staff roster exceeds account limit");
        }
        if (integer(body.get("account_count"), 0, MAX_ACCOUNTS) != accountsJson.size()) {
            throw new IllegalArgumentException("Staff roster count mismatch");
        }
        List<Account> accounts = new ArrayList<>(accountsJson.size());
        Set<Long> discordIds = new HashSet<>();
        Set<Integer> forumIds = new HashSet<>();
        for (JsonElement element : accountsJson) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Invalid staff-roster account");
            }
            JsonObject item = element.getAsJsonObject();
            if (!item.keySet().equals(Set.of("forum_user_id", "discord_id", "staff_group_ids"))) {
                throw new IllegalArgumentException("Invalid staff-roster account fields");
            }
            int forumUserId = Math.toIntExact(integer(item.get("forum_user_id"), 1, Integer.MAX_VALUE));
            long discordId = snowflake(item.get("discord_id"));
            if (!forumIds.add(forumUserId) || !discordIds.add(discordId)) {
                throw new IllegalArgumentException("Duplicate staff-roster account identity");
            }
            JsonElement groupsValue = item.get("staff_group_ids");
            if (groupsValue == null || !groupsValue.isJsonArray()) {
                throw new IllegalArgumentException("Invalid staff-roster groups");
            }
            Set<Integer> groups = new HashSet<>();
            for (JsonElement groupValue : groupsValue.getAsJsonArray()) {
                int group = Math.toIntExact(integer(groupValue, 1, Integer.MAX_VALUE));
                if (!MANAGED_GROUP_IDS.contains(group) || !groups.add(group)) {
                    throw new IllegalArgumentException("Unexpected or duplicate staff group");
                }
            }
            accounts.add(new Account(forumUserId, discordId, Set.copyOf(groups)));
        }
        return new Snapshot(expectedGuildId, generatedAt, List.copyOf(accounts));
    }

    private static long integer(JsonElement value, long minimum, long maximum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected integer");
        }
        String raw = value.getAsString();
        if (!raw.matches("[0-9]+")) {
            throw new IllegalArgumentException("Expected unsigned integer");
        }
        long parsed = Long.parseLong(raw);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("Integer out of range");
        }
        return parsed;
    }

    private static long snowflake(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !value.getAsString().matches("[1-9][0-9]{16,19}")) {
            throw new IllegalArgumentException("Invalid Discord snowflake");
        }
        return Long.parseLong(value.getAsString());
    }

    static RoleDelta difference(Account account, Set<Long> actualRoleIds) {
        List<Long> add = new ArrayList<>();
        List<Long> remove = new ArrayList<>();
        for (int i = 0; i < ROLE_IDS.length; i++) {
            boolean actual = actualRoleIds.contains(ROLE_IDS[i]);
            boolean forum = account.staffGroupIds().contains(GROUP_IDS[i]);
            if (actual && !forum) add.add(ROLE_IDS[i]);
            if (!actual && forum) remove.add(ROLE_IDS[i]);
        }
        return new RoleDelta(add.stream().mapToLong(Long::longValue).toArray(),
                remove.stream().mapToLong(Long::longValue).toArray());
    }

    @Override
    public void run() {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        try {
            Collection<NamelessAPI> connections = Main.getConnectionManager().listConnections();
            for (NamelessAPI api : connections) {
                Main.getConnectionManager().getGuildIdByApiConnection(api)
                        .ifPresent(guildId -> reconcile(guildId, api));
            }
        } catch (BackendStorageException error) {
            LOGGER.warn("Staff role reconciliation skipped: connection storage unavailable", error);
        } catch (RuntimeException error) {
            // ScheduledExecutorService otherwise silently cancels future runs.
            LOGGER.error("Staff role reconciliation failed unexpectedly; the next pass remains scheduled", error);
        } finally {
            RUNNING.set(false);
        }
    }

    private void reconcile(long guildId, NamelessAPI api) {
        Guild guild = Main.getGuildById(guildId);
        if (guild == null) {
            LOGGER.warn("Staff role reconciliation skipped for guild {}: Discord guild unavailable", guildId);
            return;
        }
        for (long roleId : ROLE_IDS) {
            if (guild.getRoleById(roleId) == null) {
                LOGGER.warn("Staff role reconciliation skipped for guild {}: dedicated role {} unavailable", guildId, roleId);
                return;
            }
        }

        final Snapshot snapshot;
        try {
            JsonObject request = new JsonObject();
            request.addProperty("protocol_version", 1);
            snapshot = parseSnapshot(api.requests().post(ROUTE, request), guildId, Instant.now());
        } catch (NamelessException | IllegalArgumentException | IllegalStateException error) {
            Main.logConnectionError(LOGGER, "Staff roster unavailable for guild " + guildId,
                    error instanceof NamelessException nameless ? nameless : new NamelessException(error));
            return;
        }

        final Map<Long, Member> members;
        try {
            members = guild.findMembers(member -> true).setTimeout(2, TimeUnit.MINUTES).get().stream()
                    .collect(Collectors.toMap(Member::getIdLong, member -> member));
            if (members.size() != guild.getMemberCount()) {
                LOGGER.warn("Staff role reconciliation skipped for guild {}: member list incomplete ({} of {})",
                        guildId, members.size(), guild.getMemberCount());
                return;
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Staff role reconciliation skipped for guild {}: Discord member list unavailable", guildId, error);
            return;
        }

        int changed = 0;
        int deferred = 0;
        int confirmations = 0;
        int start = snapshot.accounts().isEmpty() ? 0
                : Math.floorMod(NEXT_ACCOUNT_OFFSET.getAndAdd(MAX_CONFIRMATIONS_PER_PASS), snapshot.accounts().size());
        for (int i = 0; i < snapshot.accounts().size(); i++) {
            Account account = snapshot.accounts().get((start + i) % snapshot.accounts().size());
            Member member = members.get(account.discordId());
            if (member != null && member.getUser().isBot()) {
                continue;
            }
            Set<Long> actual = member == null ? Set.of() : member.getRoles().stream()
                    .map(Role::getIdLong).collect(Collectors.toSet());
            RoleDelta delta = difference(account, actual);
            if (delta.empty()) {
                continue;
            }

            if (++confirmations > MAX_CONFIRMATIONS_PER_PASS) {
                deferred++;
                continue;
            }
            // The member list may have been chunked just before a role event. Confirm every
            // nonempty change against REST, including absences, before changing the forum.
            try {
                member = guild.retrieveMemberById(account.discordId()).complete();
            } catch (ErrorResponseException error) {
                if (error.getErrorResponse() != ErrorResponse.UNKNOWN_MEMBER) {
                    deferred++;
                    LOGGER.warn("Staff role lookup deferred for Discord user {}: {}",
                            account.discordId(), error.getErrorResponse());
                    continue;
                }
                member = null;
            } catch (RuntimeException error) {
                deferred++;
                LOGGER.warn("Staff role lookup deferred for Discord user {}", account.discordId(), error);
                continue;
            }
            if (member != null && member.getUser().isBot()) {
                continue;
            }
            actual = member == null ? Set.of() : member.getRoles().stream()
                    .map(Role::getIdLong).collect(Collectors.toSet());
            delta = difference(account, actual);
            if (delta.empty()) {
                continue;
            }

            try {
                // The roster can change between read and write. Resolve the immutable forum ID
                // again through the Discord identity and reject unlink, replacement and bans.
                NamelessUser user = api.userByDiscordId(account.discordId());
                if (user == null || user.id() != account.forumUserId() || !user.isVerified() || user.isBanned()) {
                    deferred++;
                    continue;
                }
                DetailedIntegrationData discord = user.integrations().get(StandardIntegrationTypes.DISCORD);
                if (discord == null || !discord.isVerified()
                        || !discord.identifier().equals(Long.toString(account.discordId()))) {
                    deferred++;
                    continue;
                }
                synchronized (DiscordRoleListener.roleSendLock(guildId)) {
                    user.discord().syncRoles(delta.add(), delta.remove());
                }
                changed++;
                LOGGER.info("Reconciled staff roles for guild {} forum user {} ({} add, {} remove)",
                        guildId, account.forumUserId(), delta.add().length, delta.remove().length);
            } catch (NamelessException | RuntimeException error) {
                deferred++;
                LOGGER.warn("Staff role reconciliation deferred for guild {} forum user {}",
                        guildId, account.forumUserId(), error);
            }
        }
        LOGGER.info("Staff role reconciliation for guild {}: {} linked accounts, {} changed, {} deferred",
                guildId, snapshot.accounts().size(), changed, deferred);
    }
}
