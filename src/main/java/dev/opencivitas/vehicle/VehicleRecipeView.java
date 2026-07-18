package dev.opencivitas.vehicle;

import org.bukkit.inventory.ItemStack;

import java.util.List;

public record VehicleRecipeView(String id, ItemStack result, List<ItemStack> grid) {
    public VehicleRecipeView {
        result = result.clone();
        grid = grid.stream().map(item -> item == null ? null : item.clone()).toList();
    }
}
