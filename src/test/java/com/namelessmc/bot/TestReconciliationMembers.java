package com.namelessmc.bot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.internal.utils.concurrent.task.GatewayTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TestReconciliationMembers {
    private final AtomicLong clock = new AtomicLong();
    private final ReconciliationMembers roster = new ReconciliationMembers(clock::get);

    private static final class Discord {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger count = new AtomicInteger(2);
        final Guild guild;
        final List<Member> members;
        Supplier<CompletableFuture<List<Member>>> response;

        Discord(long guildId) {
            guild = (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(), new Class<?>[]{Guild.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getIdLong" -> guildId;
                        case "getMemberCount" -> count.get();
                        case "findMembers" -> {
                            requests.incrementAndGet();
                            yield new GatewayTask<>(response.get(), () -> {});
                        }
                        case "toString" -> "Test guild";
                        default -> throw new AssertionError("Unexpected guild call: " + method.getName());
                    });
            members = List.of(member(11, guild), member(12, guild));
            response = () -> CompletableFuture.completedFuture(members);
        }
    }

    private static Member member(long id, Guild guild) {
        return (Member) Proxy.newProxyInstance(Member.class.getClassLoader(), new Class<?>[]{Member.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getIdLong" -> id;
                    case "getGuild" -> guild;
                    case "toString" -> "Test member";
                    default -> throw new AssertionError("Unexpected member call: " + method.getName());
                });
    }

    @Test
    void concurrentPassesShareOneRequestEvenWhenDiscordRejectsADuplicate() throws Exception {
        Discord discord = new Discord(1);
        var response = new CompletableFuture<List<Member>>();
        var requested = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        discord.response = () -> {
            requested.countDown();
            return discord.requests.get() == 1 ? response
                    : CompletableFuture.failedFuture(new TimeoutException("Duplicate ALL-members request"));
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var staff = executor.submit(() -> roster.complete(discord.guild));
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            var linked = executor.submit(() -> {
                secondEntered.countDown();
                return roster.complete(discord.guild);
            });
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            response.complete(discord.members);
            assertEquals(discord.members, staff.get(5, TimeUnit.SECONDS));
            assertSame(staff.get(), linked.get(5, TimeUnit.SECONDS));
            assertEquals(1, discord.requests.get());
        }
    }

    @Test
    void quotaFixtureRejectsTheFormerUnsharedCalls() {
        Discord discord = new Discord(1);
        discord.response = () -> discord.requests.get() == 1
                ? CompletableFuture.completedFuture(discord.members)
                : CompletableFuture.failedFuture(new TimeoutException("Duplicate ALL-members request"));
        assertEquals(discord.members, discord.guild.findMembers(member -> true).get());
        assertInstanceOf(TimeoutException.class, assertThrows(CompletionException.class,
                () -> discord.guild.findMembers(member -> true).get()).getCause());
    }

    @Test
    void completedRosterIsSharedBrieflyThenFetchedAgain() {
        Discord discord = new Discord(1);
        List<Member> first = roster.complete(discord.guild);
        clock.set(ReconciliationMembers.REUSE_NANOS - 1);
        assertSame(first, roster.complete(discord.guild));
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        assertEquals(1, discord.requests.get());
        clock.incrementAndGet();
        assertEquals(discord.members, roster.complete(discord.guild));
        assertEquals(2, discord.requests.get());
    }

    @Test
    void failedFetchIsNotRetriedInABurstAndCanRecoverOnTheNextWindow() {
        Discord discord = new Discord(1);
        discord.response = () -> CompletableFuture.failedFuture(new TimeoutException());
        assertInstanceOf(TimeoutException.class,
                assertThrows(CompletionException.class, () -> roster.complete(discord.guild)).getCause());
        discord.response = () -> CompletableFuture.completedFuture(discord.members);
        assertThrows(IllegalStateException.class, () -> roster.complete(discord.guild));
        assertEquals(1, discord.requests.get());
        clock.set(ReconciliationMembers.REUSE_NANOS);
        assertEquals(discord.members, roster.complete(discord.guild));
        assertEquals(2, discord.requests.get());
    }

    @Test
    void partialDuplicateAndForeignGuildResultsNeverBecomeComplete() {
        for (int invalid = 0; invalid < 3; invalid++) {
            Discord discord = new Discord(10 + invalid);
            List<Member> bad = switch (invalid) {
                case 0 -> List.of(discord.members.get(0));
                case 1 -> List.of(discord.members.get(0), discord.members.get(0));
                default -> List.of(discord.members.get(0), member(12, new Discord(99).guild));
            };
            discord.response = () -> CompletableFuture.completedFuture(bad);
            assertThrows(IllegalStateException.class, () -> roster.complete(discord.guild));
            discord.response = () -> CompletableFuture.completedFuture(discord.members);
            assertThrows(IllegalStateException.class, () -> roster.complete(discord.guild));
            assertEquals(1, discord.requests.get());
        }
    }

    @Test
    void membershipChangesDuringFetchOrReuseFailClosed() {
        Discord during = new Discord(1);
        during.response = () -> {
            during.count.incrementAndGet();
            return CompletableFuture.completedFuture(during.members);
        };
        assertThrows(IllegalStateException.class, () -> roster.complete(during.guild));
        Discord after = new Discord(2);
        roster.complete(after.guild);
        after.count.incrementAndGet();
        assertThrows(IllegalStateException.class, () -> roster.complete(after.guild));
        assertEquals(1, after.requests.get());
    }

    @Test
    void guildsRemainIndependentAndReplacedConnectionsCannotReuseOldMembers() {
        Discord first = new Discord(1);
        Discord other = new Discord(2);
        roster.complete(first.guild);
        assertEquals(other.members, roster.complete(other.guild));
        Discord reconnected = new Discord(1);
        assertThrows(IllegalStateException.class, () -> roster.complete(reconnected.guild));
        assertEquals(0, reconnected.requests.get());
        clock.set(ReconciliationMembers.REUSE_NANOS);
        assertEquals(reconnected.members, roster.complete(reconnected.guild));
        assertEquals(1, reconnected.requests.get());
    }
}
