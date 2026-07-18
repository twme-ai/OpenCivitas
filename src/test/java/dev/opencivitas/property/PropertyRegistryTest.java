package dev.opencivitas.property;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyRegistryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000080");

    @Test
    void indexesPropertiesByChunkIdAndRelationship() {
        Property property = new Property(
                1, "r-101", "city", -20, 20, 0, 30, -20, 20,
                10_000L, 5_000L, 1_000, OWNER, "Owner", null, null,
                null, null, 0, Set.of(), Instant.EPOCH);
        PropertyRegistry registry = new PropertyRegistry();

        registry.replaceAll(List.of(property));

        assertEquals(property, registry.find("R-101").orElseThrow());
        assertEquals(property, registry.at("city", -17, 10, 18).orElseThrow());
        assertTrue(registry.at("city", -17, 31, 18).isEmpty());
        assertEquals(1, registry.relatedTo(OWNER).size());
        registry.remove(property.id());
        assertTrue(registry.all().isEmpty());
    }
}
