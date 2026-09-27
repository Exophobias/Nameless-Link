package com.namelessmc.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.namelessmc.bot.connections.BackendStorageException;
import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.NamelessUser;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.java_api.integrations.DetailedIntegrationData;
import com.namelessmc.java_api.integrations.StandardIntegrationTypes;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reconciles the Patriam Linked Discord role from complete, verified Minecraft+Discord links. */
public final class LinkedRoleReconciliation implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinkedRoleReconciliation.class);
    private static final String ROUTE = "minecraft/link-ranks";
    public static final long LINKED_ROLE_ID = 1210696213981036574L;
    private static final Set<Long> EXCLUDED_ROLE_IDS = Set.of(
            628381360125509644L,  // Owner
            679012387612786697L,  // Staff Manager
            1049437778351489124L  // Community Manager
    );
    private static final int MAX_ACCOUNTS = 5000;
    private static final int MAX_CHANGES_PER_PASS = 250;
    private static final long MAX_AGE_SECONDS = 180;
    private static final long MAX_FUTURE_SECONDS = 30;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicInteger NEXT_MEMBER_OFFSET = new AtomicInteger();
    public static final LinkedRoleReconciliation INSTANCE = new LinkedRoleReconciliation();

    private LinkedRoleReconciliation() { }

    record Account(int forumUserId, String minecraftUuid, String discordId) { }
    record Snapshot(String revision, Map<String, Account> eligibleAccounts) { }
    enum Action { ADD, REMOVE, NONE }

    static Snapshot parseSnapshot(JsonObject body, String expectedServerId, Instant now) {
        if (body == null || !body.keySet().equals(Set.of("protocol_version", "server_id", "generated_at",
                "revision", "accounts"))
                || integer(body.get("protocol_version"), 1, 1) != 1
                || !expectedServerId.equals(string(body.get("server_id")))) {
            throw new IllegalArgumentException("Invalid linked-rank protocol or server");
        }
        long generatedAt = integer(body.get("generated_at"), 1, Long.MAX_VALUE);
        if (generatedAt < now.getEpochSecond() - MAX_AGE_SECONDS
                || generatedAt > now.getEpochSecond() + MAX_FUTURE_SECONDS) {
            throw new IllegalArgumentException("Linked-rank roster is stale or from the future");
        }
        String revision = string(body.get("revision"));
        if (!revision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid linked-rank revision");
        }
        JsonElement accountsValue = body.get("accounts");
        if (accountsValue == null || !accountsValue.isJsonArray()) {
            throw new IllegalArgumentException("Linked-rank roster has no accounts array");
        }
        JsonArray rows = accountsValue.getAsJsonArray();
        if (rows.size() > MAX_ACCOUNTS) {
            throw new IllegalArgumentException("Linked-rank roster exceeds account limit");
        }
        Set<Integer> forumIds = new HashSet<>();
        Map<String, Account> eligible = new HashMap<>();
        JsonArray canonical = new JsonArray();
        String previousUuid = null;
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Invalid linked-rank account");
            }
            JsonObject row = element.getAsJsonObject();
            if (!row.keySet().equals(Set.of("uuid", "forum_user_id", "discord_id", "tier"))) {
                throw new IllegalArgumentException("Invalid linked-rank account fields");
            }
            String uuid = string(row.get("uuid"));
            if (!uuid.matches("[0-9a-f]{32}") || uuid.equals("00000000000000000000000000000000")
                    || (previousUuid != null && uuid.compareTo(previousUuid) <= 0)) {
                throw new IllegalArgumentException("Linked-rank UUIDs must be canonical, unique and sorted");
            }
            previousUuid = uuid;
            int forumId = Math.toIntExact(integer(row.get("forum_user_id"), 1, Integer.MAX_VALUE));
            if (!forumIds.add(forumId)) {
                throw new IllegalArgumentException("Duplicate linked-rank forum identity");
            }
            String tier = string(row.get("tier"));
            JsonElement discordValue = row.get("discord_id");
            String discordId = discordValue.isJsonNull() ? null : string(discordValue);
            if (!(tier.equals("limited") && discordId == null)
                    && !(tier.equals("member") && discordId != null)) {
                throw new IllegalArgumentException("Linked-rank tier and Discord link disagree");
            }
            if (discordId != null) {
                if (!discordId.matches("[1-9][0-9]{16,19}")
                        || (discordId.length() == 20
                            && discordId.compareTo("18446744073709551615") > 0)
                        || eligible.putIfAbsent(discordId, new Account(forumId, uuid, discordId)) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate linked-rank Discord identity");
                }
            }
            JsonObject canonicalRow = new JsonObject();
            canonicalRow.addProperty("uuid", uuid);
            canonicalRow.addProperty("forum_user_id", forumId);
            if (discordId == null) canonicalRow.add("discord_id", com.google.gson.JsonNull.INSTANCE);
            else canonicalRow.addProperty("discord_id", discordId);
            canonicalRow.addProperty("tier", tier);
            canonical.add(canonicalRow);
        }
        if (!revision.equals(sha256(canonical.toString()))) {
            throw new IllegalArgumentException("Linked-rank revision does not match the complete roster");
        }
        return new Snapshot(revision, Map.copyOf(eligible));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected string");
        }
        return value.getAsString();
    }

    static boolean isExcluded(boolean guildOwner, Set<Long> roleIds) {
        if (guildOwner) return true;
        for (long roleId : roleIds) {
            if (EXCLUDED_ROLE_IDS.contains(roleId)) return true;
        }
        return false;
    }

    static Action action(Snapshot snapshot, String discordId, boolean bot, boolean guildOwner,
                         Set<Long> roleIds) {
        if (bot || isExcluded(guildOwner, roleIds)) return Action.NONE;
        boolean expected = snapshot.eligibleAccounts().containsKey(discordId);
        boolean actual = roleIds.contains(LINKED_ROLE_ID);
        if (expected == actual) return Action.NONE;
        return expected ? Action.ADD : Action.REMOVE;
    }

    private static Action action(Snapshot snapshot, Member member) {
        Set<Long> roleIds = new HashSet<>();
        for (Role role : member.getRoles()) {
            roleIds.add(role.getIdLong());
        }
        return action(snapshot, member.getId(), member.getUser().isBot(), member.isOwner(), roleIds);
    }

    public static long[] withoutLinkedRole(long[] roleIds) {
        return java.util.Arrays.stream(roleIds).filter(roleId -> roleId != LINKED_ROLE_ID).toArray();
    }

    private static boolean currentLinkMatches(NamelessAPI api, Account account) throws NamelessException {
        NamelessUser user = api.byIntegrationIdentifierLazy(StandardIntegrationTypes.DISCORD,
                account.discordId());
        // Core's UserInfoEndpoint exposes the forum active flag as "validated".
        // Recheck it here in case the account was deactivated after the roster read.
        if (user.id() != account.forumUserId() || !user.isVerified() || user.isBanned()) {
            return false;
        }
        Map<String, DetailedIntegrationData> integrations = user.integrations();
        DetailedIntegrationData discord = integrations.get(StandardIntegrationTypes.DISCORD);
        DetailedIntegrationData minecraft = integrations.get(StandardIntegrationTypes.MINECRAFT);
        return discord != null && discord.isVerified() && discord.identifier().equals(account.discordId())
                && minecraft != null && minecraft.isVerified()
                && minecraft.identifier().replace("-", "").equalsIgnoreCase(account.minecraftUuid());
    }

    @Override public void run() {
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            Collection<NamelessAPI> connections = Main.getConnectionManager().listConnections();
            for (NamelessAPI api : connections) {
                Main.getConnectionManager().getGuildIdByApiConnection(api)
                        .ifPresent(guildId -> reconcile(guildId, api));
            }
        } catch (BackendStorageException error) {
            LOGGER.warn("Linked role reconciliation skipped: connection storage unavailable", error);
        } catch (RuntimeException error) {
            LOGGER.error("Linked role reconciliation failed unexpectedly; the next pass remains scheduled", error);
        } finally {
            RUNNING.set(false);
        }
    }

    private static Snapshot fetch(NamelessAPI api, String serverId) throws NamelessException {
        JsonObject request = new JsonObject();
        request.addProperty("protocol_version", 1);
        request.addProperty("server_id", serverId);
        try {
            return parseSnapshot(api.requests().post(ROUTE, request), serverId, Instant.now());
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new NamelessException(error);
        }
    }

    private void reconcile(long guildId, NamelessAPI api) {
        Guild guild = Main.getGuildById(guildId);
        if (guild == null) {
            LOGGER.warn("Linked role reconciliation skipped for guild {}: Discord guild unavailable", guildId);
            return;
        }
        Role linkedRole = guild.getRoleById(LINKED_ROLE_ID);
        if (linkedRole == null || !guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)
                || !guild.getSelfMember().canInteract(linkedRole)) {
            LOGGER.warn("Linked role reconciliation skipped for guild {}: Linked role unavailable or unmanageable", guildId);
            return;
        }
        for (long id : EXCLUDED_ROLE_IDS) {
            if (guild.getRoleById(id) == null) {
                LOGGER.warn("Linked role reconciliation skipped for guild {}: excluded Owner/Manager role {} unavailable", guildId, id);
                return;
            }
        }
        String serverId = Main.getLinkedRoleServerId();
        final Snapshot snapshot;
        try {
            snapshot = fetch(api, serverId);
        } catch (NamelessException error) {
            Main.logConnectionError(LOGGER, "Linked-rank roster unavailable for guild " + guildId, error);
            return;
        }
        final List<Member> members;
        try {
            members = guild.findMembers(member -> true).setTimeout(2, TimeUnit.MINUTES).get();
            if (members.size() != guild.getMemberCount()
                    || members.stream().map(Member::getId).distinct().count() != members.size()) {
                LOGGER.warn("Linked role reconciliation skipped for guild {}: Discord member list incomplete", guildId);
                return;
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Linked role reconciliation skipped for guild {}: Discord member list unavailable", guildId, error);
            return;
        }
        List<Member> candidates = new ArrayList<>();
        for (Member member : members) {
            if (action(snapshot, member) != Action.NONE) candidates.add(member);
        }
        if (candidates.isEmpty()) {
            LOGGER.info("Linked role reconciliation for guild {}: {} fully linked, 0 changed, 0 deferred",
                    guildId, snapshot.eligibleAccounts().size());
            return;
        }
        // A link could change while the Discord roster is fetched. Defer this pass if the
        // complete website revision changed, rather than applying a stale decision.
        try {
            if (!snapshot.revision().equals(fetch(api, serverId).revision())) {
                LOGGER.info("Linked role reconciliation deferred for guild {}: roster changed during the pass", guildId);
                return;
            }
        } catch (NamelessException error) {
            Main.logConnectionError(LOGGER, "Linked-rank confirmation unavailable for guild " + guildId, error);
            return;
        }
        int changed = 0;
        int deferred = 0;
        int start = Math.floorMod(NEXT_MEMBER_OFFSET.getAndAdd(MAX_CHANGES_PER_PASS), candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            if (i >= MAX_CHANGES_PER_PASS) {
                deferred++;
                continue;
            }
            Member cached = candidates.get((start + i) % candidates.size());
            final Member fresh;
            try {
                fresh = guild.retrieveMemberById(cached.getId())
                        .useCache(false).timeout(15, TimeUnit.SECONDS).complete();
            } catch (ErrorResponseException error) {
                if (error.getErrorResponse() != ErrorResponse.UNKNOWN_MEMBER) {
                    deferred++;
                    LOGGER.warn("Linked role member confirmation deferred for user {}: {}",
                            cached.getId(), error.getErrorResponse());
                }
                continue;
            } catch (RuntimeException error) {
                deferred++;
                LOGGER.warn("Linked role member confirmation deferred for user {}", cached.getId(), error);
                continue;
            }
            if (fresh == null) continue;
            Action action = action(snapshot, fresh);
            if (action == Action.NONE) continue;
            // The complete roster is the authority for active-account state and
            // exclusion records. Reconfirm its revision immediately before a write.
            try {
                if (!snapshot.revision().equals(fetch(api, serverId).revision())) {
                    deferred++;
                    continue;
                }
            } catch (NamelessException error) {
                deferred++;
                LOGGER.warn("Linked role roster confirmation deferred for user {}", fresh.getId(), error);
                continue;
            }
            if (action == Action.ADD) {
                try {
                    if (!currentLinkMatches(api, snapshot.eligibleAccounts().get(fresh.getId()))) {
                        deferred++;
                        continue;
                    }
                } catch (NamelessException | RuntimeException error) {
                    deferred++;
                    LOGGER.warn("Linked role current link confirmation deferred for user {}", fresh.getId(), error);
                    continue;
                }
            }
            try {
                if (action == Action.ADD) guild.addRoleToMember(fresh, linkedRole).timeout(15, TimeUnit.SECONDS).complete();
                else guild.removeRoleFromMember(fresh, linkedRole).timeout(15, TimeUnit.SECONDS).complete();
                changed++;
                LOGGER.info("Reconciled Linked role for guild {} user {}: {}",
                        guildId, fresh.getId(), action == Action.ADD ? "add" : "remove");
            } catch (RuntimeException error) {
                deferred++;
                LOGGER.warn("Linked role mutation deferred for guild {} user {}", guildId, fresh.getId(), error);
            }
        }
        LOGGER.info("Linked role reconciliation for guild {}: {} fully linked, {} changed, {} deferred",
                guildId, snapshot.eligibleAccounts().size(), changed, deferred);
    }
}
