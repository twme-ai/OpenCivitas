package dev.opencivitas.police;

import java.time.Instant;
import java.util.UUID;

public record CombatIncident(
        long id,
        UUID attackerId,
        String attackerName,
        UUID victimId,
        String victimName,
        String world,
        double x,
        double y,
        double z,
        int damageMillihearts,
        String damageCause,
        byte[] weaponData,
        LegalBasis legalBasis,
        Instant attackedAt,
        boolean fatal,
        Instant deathAt
) {
    public CombatIncident {
        weaponData = weaponData == null ? null : weaponData.clone();
    }

    @Override
    public byte[] weaponData() {
        return weaponData == null ? null : weaponData.clone();
    }
}
