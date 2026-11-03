package net.exylia.exyliaLink.importer;

import github.scarsz.discordsrv.DiscordSRV;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DiscordSRV's own link manager, whatever storage it uses (file or SQL). Only loaded when
 * DiscordSRV is enabled, so the plugin starts without it.
 */
final class DiscordSrvApi {

    private DiscordSrvApi() {
    }

    /** Discord id to UUID. Blocking on DiscordSRV's SQL backend: called off the server's threads. */
    static Map<String, String> links() {
        Map<String, String> links = new LinkedHashMap<>();
        for (Map.Entry<String, UUID> entry : DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts().entrySet()) {
            links.put(entry.getKey(), entry.getValue().toString());
        }
        return links;
    }
}
