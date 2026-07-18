package dev.opencivitas.protection;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ProtectionSessionService {
    private final Map<UUID, ProtectionAction> actions = new HashMap<>();
    private final Map<UUID, EnumSet<ProtectionMode>> modes = new HashMap<>();
    private final Map<UUID, Set<String>> passwordHashes = new HashMap<>();
    private final Map<UUID, String> commandSecrets = new HashMap<>();
    private final Map<UUID, ProtectionAction> secretActions = new HashMap<>();
    private final Set<UUID> passwordPrompts = new java.util.HashSet<>();

    public synchronized void setAction(UUID playerId, ProtectionAction action) {
        actions.put(playerId, action);
    }

    public synchronized Optional<ProtectionAction> consumeAction(UUID playerId) {
        ProtectionAction action = actions.get(playerId);
        if (action != null && !hasMode(playerId, ProtectionMode.PERSIST)) actions.remove(playerId);
        return Optional.ofNullable(action);
    }

    public synchronized void cancelPending(UUID playerId) {
        actions.remove(playerId);
        commandSecrets.remove(playerId);
        secretActions.remove(playerId);
        passwordPrompts.remove(playerId);
    }

    public synchronized boolean toggleMode(UUID playerId, ProtectionMode mode) {
        EnumSet<ProtectionMode> selected = modes.computeIfAbsent(
                playerId, ignored -> EnumSet.noneOf(ProtectionMode.class));
        boolean enabled;
        if (selected.contains(mode)) {
            selected.remove(mode);
            enabled = false;
        } else {
            selected.add(mode);
            enabled = true;
        }
        if (selected.isEmpty()) modes.remove(playerId);
        return enabled;
    }

    public synchronized boolean hasMode(UUID playerId, ProtectionMode mode) {
        return modes.getOrDefault(playerId, EnumSet.noneOf(ProtectionMode.class)).contains(mode);
    }

    public synchronized void addPasswordAuthorizations(UUID playerId, Set<String> hashes) {
        if (hashes.isEmpty()) return;
        Set<String> authorized = new java.util.HashSet<>(passwordHashes.getOrDefault(playerId, Set.of()));
        authorized.addAll(hashes);
        passwordHashes.put(playerId, Set.copyOf(authorized));
    }

    public synchronized Set<String> passwordHashes(UUID playerId) {
        return passwordHashes.getOrDefault(playerId, Set.of());
    }

    public synchronized void stageCommandSecret(UUID playerId, String secret) {
        commandSecrets.put(playerId, secret);
    }

    public synchronized Optional<String> consumeCommandSecret(UUID playerId) {
        return Optional.ofNullable(commandSecrets.remove(playerId));
    }

    public synchronized void promptForPassword(UUID playerId) {
        passwordPrompts.add(playerId);
        secretActions.remove(playerId);
    }

    public synchronized boolean consumePasswordPrompt(UUID playerId) {
        return passwordPrompts.remove(playerId);
    }

    public synchronized void promptForActionSecret(UUID playerId, ProtectionAction action) {
        secretActions.put(playerId, action);
        passwordPrompts.remove(playerId);
    }

    public synchronized Optional<ProtectionAction> consumeSecretAction(UUID playerId) {
        return Optional.ofNullable(secretActions.remove(playerId));
    }

    public synchronized void clear(UUID playerId) {
        actions.remove(playerId);
        modes.remove(playerId);
        passwordHashes.remove(playerId);
        commandSecrets.remove(playerId);
        secretActions.remove(playerId);
        passwordPrompts.remove(playerId);
    }
}
