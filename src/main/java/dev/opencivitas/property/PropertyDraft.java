package dev.opencivitas.property;

public record PropertyDraft(
        String plotId,
        String worldName,
        int firstX,
        int firstY,
        int firstZ,
        int secondX,
        int secondY,
        int secondZ,
        Long salePriceCents,
        Long rentPriceCents,
        long rentDurationMillis
) {
}
