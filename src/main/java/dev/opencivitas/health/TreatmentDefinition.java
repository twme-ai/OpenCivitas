package dev.opencivitas.health;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record TreatmentDefinition(
        String id,
        Map<String, String> names,
        Material material,
        List<Material> ingredients,
        CareSetting careSetting,
        long medicareBenefitCents,
        long pharmacyCopayCents
) {
    public TreatmentDefinition {
        names = Map.copyOf(names);
        ingredients = List.copyOf(ingredients);
    }
}
