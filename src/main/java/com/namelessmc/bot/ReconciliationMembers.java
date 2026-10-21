package com.namelessmc.bot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Shares a complete gateway roster between reconciliation passes for the same guild. */
final class ReconciliationMembers {
    static final ReconciliationMembers INSTANCE = new ReconciliationMembers(System::nanoTime);
    // Discord permits only one ALL-members request per guild/bot in 30 seconds. Keep a
    // small margin; this is also the maximum lifetime of a successfully shared roster.
    static final long REUSE_NANOS = Duration.ofSeconds(35).toNanos();

    private final LongSupplier clock;
    private final Map<Long, State> states = new ConcurrentHashMap<>();

    ReconciliationMembers(LongSupplier clock) {
        this.clock = clock;
    }

    List<Member> complete(Guild guild) {
        State state = states.computeIfAbsent(guild.getIdLong(), ignored -> new State());
        // Both schedulers, /verify and member events use this same per-guild lock.
        // Never hold it on JDA's event thread: callers run on Main's executor.
        synchronized (state) {
            long now = clock.getAsLong();
            if (state.attempted && now - state.started < REUSE_NANOS) {
                if (state.guild == guild && state.members != null) {
                    validate(guild, state.members, state.expectedCount);
                    return state.members;
                }
                // Failed requests and replaced guild instances must not cause a burst or
                // inherit a previous connection's roster. The next pass can retry.
                throw new IllegalStateException("Complete Discord member roster unavailable in refresh window");
            }
            state.attempted = true;
            state.started = now;
            state.guild = guild;
            state.members = null;
            state.expectedCount = guild.getMemberCount();
            List<Member> members = List.copyOf(guild.findMembers(member -> true)
                    .setTimeout(2, TimeUnit.MINUTES).get());
            validate(guild, members, state.expectedCount);
            state.members = members;
            return members;
        }
    }

    private static void validate(Guild guild, List<Member> members, int expectedCount) {
        if (members.size() != expectedCount || guild.getMemberCount() != expectedCount) {
            throw new IllegalStateException("Discord member roster incomplete or changed during retrieval");
        }
        var ids = new HashSet<Long>();
        for (Member member : members) {
            if (member.getIdLong() <= 0 || member.getGuild().getIdLong() != guild.getIdLong()
                    || !ids.add(member.getIdLong())) {
                throw new IllegalStateException("Discord member roster contains ambiguous identities");
            }
        }
    }

    private static final class State {
        private boolean attempted;
        private long started;
        private Guild guild;
        private int expectedCount;
        private List<Member> members;
    }
}
