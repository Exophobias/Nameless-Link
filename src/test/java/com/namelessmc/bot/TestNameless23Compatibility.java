package com.namelessmc.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.NamelessVersion;
import com.namelessmc.java_api.exception.ApiError;
import com.namelessmc.java_api.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TestNameless23Compatibility {
    @Test void configurationAndGuildJoinVersionGateSupportsBothSites() throws Exception {
        assertTrue(NamelessVersion.isSupportedByJavaApi(NamelessVersion.parse("2.2.5")));
        assertTrue(NamelessVersion.isSupportedByJavaApi(NamelessVersion.parse("2.3.0")));
    }

    @Test void roleDeltaRemainsBoundToObservedIdentityAndDoesNotRetryRemovedRoute() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>();
        List<JsonObject> bodies = new ArrayList<>();
        AtomicReference<String> response = new AtomicReference<>("{}");
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            NamelessAPI api = NamelessAPI.builder(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/").toURL(), "local-test").build();
            DiscordRoleSync.send(api, api.userLazy(42), 123456789012345678L,
                    new long[]{123L}, new long[]{456L});
            assertEquals("/discord/id:42/sync-roles", paths.get(0));
            assertEquals("123456789012345678", bodies.get(0).get("discord_id").getAsString());
            assertEquals("[123]", bodies.get(0).get("add").toString());
            assertEquals("[456]", bodies.get(0).get("remove").toString());
            response.set("{\"error\":\"nameless:invalid_api_method\"}");
            ApiException error = assertThrows(ApiException.class,
                    () -> DiscordRoleSync.send(api, api.userLazy(42), 123456789012345678L,
                            new long[]{123L}, new long[0]));
            assertSame(ApiError.NAMELESS_INVALID_API_METHOD, error.apiError());
            assertEquals(List.of("/discord/id:42/sync-roles", "/discord/id:42/sync-roles"), paths);
        } finally {
            server.stop(0);
        }
    }
}
