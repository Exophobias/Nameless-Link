package com.namelessmc.bot.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCommandRegistration {
    @Test
    void upsertsLinkCommandsWithoutReplacingDiscordSrvCommands() {
        Set<String> existing = new LinkedHashSet<>(List.of("discord", "say"));
        RestAction<?> upsert = (RestAction<?>) Proxy.newProxyInstance(
                RestAction.class.getClassLoader(), new Class<?>[]{RestAction.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("complete")) return null;
                    throw new AssertionError("Unexpected command action " + method.getName());
                });
        Guild guild = (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(),
                new Class<?>[]{Guild.class}, (proxy, method, args) -> {
                    if (method.getName().equals("upsertCommand")) {
                        existing.add(((CommandData) args[0]).getName());
                        return upsert;
                    }
                    throw new AssertionError("Bulk command replacement or unexpected Guild call: " + method.getName());
                });
        Command.upsertCommands(guild, List.of(
                Commands.slash("verify", "Verify a forum account"),
                Commands.slash("ping", "Check Link bot status")));
        assertEquals(Set.of("discord", "say", "verify", "ping"), existing);
    }
}
