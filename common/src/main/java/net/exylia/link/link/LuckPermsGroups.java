package net.exylia.link.link;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A player's LuckPerms groups, primary first, then the rest as LuckPerms returns them (inherited
 * included). Only loaded once LuckPerms is known to be enabled; the same API on a server and on a
 * proxy (which reads the proxy's own LuckPerms).
 */
final class LuckPermsGroups {

    private final LuckPerms luckPerms = LuckPermsProvider.get();
    private EventSubscription<UserDataRecalculateEvent> subscription;

    List<String> of(UUID player) {
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) {
            return List.of();
        }
        List<String> groups = new ArrayList<>();
        groups.add(user.getPrimaryGroup());
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            if (!groups.contains(group.getName())) {
                groups.add(group.getName());
            }
        }
        return groups;
    }

    /** Any change to a loaded user's data: groups added, removed, expired or inherited differently. */
    void listen(Object plugin, Consumer<UUID> changed) {
        subscription = luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> changed.accept(event.getUser().getUniqueId()));
    }

    void close() {
        if (subscription != null) {
            subscription.close();
        }
    }
}
