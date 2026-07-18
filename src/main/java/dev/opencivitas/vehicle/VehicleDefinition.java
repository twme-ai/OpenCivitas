package dev.opencivitas.vehicle;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record VehicleDefinition(
        String id,
        Map<String, String> names,
        VehicleCategory category,
        Material itemMaterial,
        Material displayMaterial,
        String licenseId,
        long maximumFuel,
        long fuelPerBlock,
        int maximumHealth,
        int storageSlots,
        double maximumSpeed,
        double acceleration,
        List<String> recipeShape,
        Map<Character, String> recipeIngredients
) {
    public VehicleDefinition {
        names = Map.copyOf(names);
        recipeShape = List.copyOf(recipeShape);
        recipeIngredients = Map.copyOf(recipeIngredients);
    }
}
