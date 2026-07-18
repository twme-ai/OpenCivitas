package dev.opencivitas.protection;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class ProtectionRegistry {
    private volatile Map<ProtectionKey, BlockProtection> protections = Map.of();
    private volatile Map<GroupKey, ProtectionGroup> groups = Map.of();
    private volatile Map<UUID, Map<ProtectionSource, ProtectionAccess>> trust = Map.of();

    public synchronized void replaceAll(ProtectionState state) {
        Map<ProtectionKey, BlockProtection> loadedProtections = new HashMap<>();
        for (BlockProtection protection : state.protections()) {
            loadedProtections.put(protection.key(), protection);
        }
        Map<GroupKey, ProtectionGroup> loadedGroups = new HashMap<>();
        for (ProtectionGroup group : state.groups()) {
            loadedGroups.put(new GroupKey(group.ownerId(), group.name()), group);
        }
        protections = Map.copyOf(loadedProtections);
        groups = Map.copyOf(loadedGroups);
        trust = state.trust();
    }

    public Optional<BlockProtection> at(ProtectionKey key) {
        return Optional.ofNullable(protections.get(key));
    }

    public synchronized void upsert(BlockProtection protection) {
        Map<ProtectionKey, BlockProtection> updated = new HashMap<>(protections);
        updated.put(protection.key(), protection);
        protections = Map.copyOf(updated);
    }

    public synchronized void remove(ProtectionKey key) {
        Map<ProtectionKey, BlockProtection> updated = new HashMap<>(protections);
        updated.remove(key);
        protections = Map.copyOf(updated);
    }

    public synchronized void upsert(ProtectionGroup group) {
        Map<GroupKey, ProtectionGroup> updated = new HashMap<>(groups);
        updated.put(new GroupKey(group.ownerId(), group.name()), group);
        groups = Map.copyOf(updated);
    }

    public synchronized void removeGroup(UUID ownerId, String name) {
        Map<GroupKey, ProtectionGroup> updated = new HashMap<>(groups);
        updated.remove(new GroupKey(ownerId, name));
        groups = Map.copyOf(updated);
    }

    public Optional<ProtectionGroup> group(UUID ownerId, String name) {
        return Optional.ofNullable(groups.get(new GroupKey(ownerId, name)));
    }

    public Set<String> groupNames(UUID ownerId) {
        Set<String> names = new LinkedHashSet<>();
        groups.forEach((key, group) -> {
            if (key.ownerId().equals(ownerId)) names.add(group.name());
        });
        return Set.copyOf(names);
    }

    public synchronized void setTrust(UUID ownerId, Map<ProtectionSource, ProtectionAccess> sources) {
        Map<UUID, Map<ProtectionSource, ProtectionAccess>> updated = new HashMap<>(trust);
        if (sources.isEmpty()) updated.remove(ownerId);
        else updated.put(ownerId, Map.copyOf(sources));
        trust = Map.copyOf(updated);
    }

    public Map<ProtectionSource, ProtectionAccess> trust(UUID ownerId) {
        return trust.getOrDefault(ownerId, Map.of());
    }

    public ProtectionAccess effectiveAccess(
            BlockProtection protection,
            Player player,
            Set<String> authorizedPasswordHashes
    ) {
        if (player.hasPermission("opencivitas.protection.bypass")) return ProtectionAccess.ADMIN;
        return effectiveAccess(
                protection, player.getUniqueId(), player::hasPermission, authorizedPasswordHashes::contains);
    }

    ProtectionAccess effectiveAccess(
            BlockProtection protection,
            UUID playerId,
            Predicate<String> hasPermission,
            Predicate<String> hasPasswordHash
    ) {
        if (protection.ownerId().equals(playerId)) return ProtectionAccess.ADMIN;
        ProtectionAccess best = null;
        best = strongest(best, matching(
                protection.ownerId(), protection.access(), playerId, hasPermission, hasPasswordHash));
        best = strongest(best, matching(
                protection.ownerId(), trust(protection.ownerId()), playerId, hasPermission, hasPasswordHash));
        return best;
    }

    public Set<String> passwordHashes(String fingerprint) {
        Set<String> hashes = new LinkedHashSet<>();
        for (BlockProtection protection : protections.values()) {
            protection.access().keySet().stream()
                    .filter(source -> source.type() == ProtectionSourceType.PASSWORD)
                    .map(ProtectionSource::identifier)
                    .filter(hash -> PasswordHasher.storedFingerprint(hash).equals(fingerprint))
                    .forEach(hashes::add);
        }
        trust.values().forEach(sources -> sources.keySet().stream()
                .filter(source -> source.type() == ProtectionSourceType.PASSWORD)
                .map(ProtectionSource::identifier)
                .filter(hash -> PasswordHasher.storedFingerprint(hash).equals(fingerprint))
                .forEach(hashes::add));
        return Set.copyOf(hashes);
    }

    private ProtectionAccess matching(
            UUID ownerId,
            Map<ProtectionSource, ProtectionAccess> sources,
            UUID playerId,
            Predicate<String> hasPermission,
            Predicate<String> hasPasswordHash
    ) {
        ProtectionAccess best = null;
        for (Map.Entry<ProtectionSource, ProtectionAccess> entry : sources.entrySet()) {
            ProtectionSource source = entry.getKey();
            boolean matches = switch (source.type()) {
                case PLAYER -> source.identifier().equals(playerId.toString());
                case GROUP -> group(ownerId, source.identifier())
                        .map(group -> group.members().contains(playerId)).orElse(false);
                case PERMISSION -> hasPermission.test(source.identifier());
                case PASSWORD -> hasPasswordHash.test(source.identifier());
            };
            if (matches) best = strongest(best, entry.getValue());
        }
        return best;
    }

    private static ProtectionAccess strongest(ProtectionAccess first, ProtectionAccess second) {
        if (first == ProtectionAccess.ADMIN || second == ProtectionAccess.ADMIN) return ProtectionAccess.ADMIN;
        if (first == ProtectionAccess.NORMAL || second == ProtectionAccess.NORMAL) return ProtectionAccess.NORMAL;
        return null;
    }

    private record GroupKey(UUID ownerId, String name) {
    }
}
