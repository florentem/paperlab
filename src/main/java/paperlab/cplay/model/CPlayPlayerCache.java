package paperlab.cplay.model;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CPlayPlayerCache {
    private final Map<UUID, String> players = new ConcurrentHashMap<>();

    public void put(UUID uuid, String name) {
        if (uuid != null && name != null) {
            players.put(uuid, name);
        }
    }

    public String get(UUID uuid) {
        return players.get(uuid);
    }

    public void remove(UUID uuid) {
        players.remove(uuid);
    }

    public Map<UUID, String> getAll() {
        return Collections.unmodifiableMap(players);
    }
}
