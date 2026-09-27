# Nameless-Link

The "Nameless Link" Discord bot synchronizes user roles to and from a specific Discord Guild.

For documentation please consult the [wiki](https://github.com/NamelessMC/Nameless-Link/wiki).

## Translations

<a href="https://translate.namelessmc.com/engage/namelessmc/">
<img src="https://translate.namelessmc.com/widgets/namelessmc/-/discord-bot/multi-auto.svg" alt="Translation status" />
</a>

## Compiling

Requirements: Maven, JDK 11, JDK 17, git

`apt install maven openjdk-11-jdk openjdk-17-jdk git`

```sh
git clone https://github.com/NamelessMC/Nameless-Java-API
cd Nameless-Java-API
# You may need to manually set JAVA_HOME to your JDK 11 installation
mvn install
cd ..

git clone https://github.com/NamelessMC/Nameless-Link
cd Nameless-Link
# You may need to manually set JAVA_HOME to your JDK 17 installation
mvn package
cd target
# find jar file here
```

## Patriam Linked role

The maintained Patriam fork can reconcile Discord role `1210696213981036574`
from the website's complete `minecraft/link-ranks` roster. Set
`ENABLE_LINKED_ROLE_RECONCILIATION=true` and `LINKED_ROLE_SERVER_ID=1` on the
self-hosted Link process. It uses that process's existing Core API connection;
the feature is disabled by default for other installations.

Only an active forum account with both a verified Minecraft link and a verified
Discord link receives Linked. A complete, fresh roster also removes Linked
after either link is lost. The guild owner and holders of Patriam's Owner,
Staff Manager, or Community Manager roles are excluded from both changes.
The bot checks missed events at startup and every five minutes, and after
successful `/verify` or a guild join. Its role changes do not flow back into
the forum's Discord role mapper. Link's guild commands are upserted by name so
another Patriam process using the same application can keep its commands;
old Link-owned command names are not automatically deleted.
