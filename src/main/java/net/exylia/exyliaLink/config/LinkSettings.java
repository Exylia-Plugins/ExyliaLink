package net.exylia.exyliaLink.config;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;
import net.exylia.lib.config.Languages;

/**
 * {@code config.yml}. Almost nothing: what Link does is set on link.exylia.net, and the network
 * secret lives sealed in the shared database, never in a file an owner might paste somewhere.
 */
public record LinkSettings(
        @Comment("Language of this plugin's messages. 'default' follows the language set in ExyliaLib's")
        @Comment("config.yml; en or es set this plugin alone. Each one is a folder under lang/ you can edit.")
        String language,
        @Key("server-id")
        @Comment("This server's id on Exylia Link, shown on the dashboard. Keep it stable: the dashboard")
        @Comment("knows the server by it. Empty uses ExyliaLib's server id (its network settings).")
        String serverId,
        @Key("gateway-url")
        @Comment("Where the plugin links and connects. Leave as is unless Exylia support says otherwise.")
        String gatewayUrl) {

    public LinkSettings() {
        this(Languages.DEFAULT, "", "https://link-gateway.exylia.net");
    }
}
