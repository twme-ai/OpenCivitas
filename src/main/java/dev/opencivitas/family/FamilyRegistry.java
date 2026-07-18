package dev.opencivitas.family;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class FamilyRegistry {
    private final AtomicReference<Map<UUID, PartnerState>> partners = new AtomicReference<>(Map.of());

    public void replaceAll(List<Marriage> marriages) {
        Map<UUID, PartnerState> loaded = new HashMap<>();
        for (Marriage marriage : marriages) if (marriage.active()) add(loaded, marriage);
        partners.set(Map.copyOf(loaded));
    }

    public void upsert(Marriage marriage) {
        Map<UUID, PartnerState> updated = new HashMap<>(partners.get());
        updated.remove(marriage.spouseAId());
        updated.remove(marriage.spouseBId());
        if (marriage.active()) add(updated, marriage);
        partners.set(Map.copyOf(updated));
    }

    public void remove(Marriage marriage) {
        Map<UUID, PartnerState> updated = new HashMap<>(partners.get());
        updated.remove(marriage.spouseAId());
        updated.remove(marriage.spouseBId());
        partners.set(Map.copyOf(updated));
    }

    public Optional<PartnerState> partner(UUID playerId) {
        return Optional.ofNullable(partners.get().get(playerId));
    }

    public boolean blocksPvp(UUID attacker, UUID victim) {
        PartnerState relationship = partners.get().get(attacker);
        return relationship != null && relationship.partnerId().equals(victim) && !relationship.pvpAllowed();
    }

    private static void add(Map<UUID, PartnerState> destination, Marriage marriage) {
        boolean pvp = marriage.partnerPvpAllowed();
        destination.put(marriage.spouseAId(), new PartnerState(
                marriage.id(), marriage.spouseBId(), marriage.spouseBName(), pvp, marriage.home()));
        destination.put(marriage.spouseBId(), new PartnerState(
                marriage.id(), marriage.spouseAId(), marriage.spouseAName(), pvp, marriage.home()));
    }
}
