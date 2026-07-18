package dev.opencivitas.shop;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InventoryReservation {
    private final List<ItemStack> items;

    private InventoryReservation(List<ItemStack> items) {
        this.items = items;
    }

    public static Optional<InventoryReservation> take(Inventory inventory, Material material, int amount) {
        ItemStack[] contents = inventory.getStorageContents();
        int available = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() == material) {
                available += item.getAmount();
                if (available >= amount) {
                    break;
                }
            }
        }
        if (available < amount) {
            return Optional.empty();
        }

        int remaining = amount;
        List<ItemStack> reserved = new ArrayList<>();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != material) {
                continue;
            }
            int taken = Math.min(item.getAmount(), remaining);
            ItemStack transfer = item.clone();
            transfer.setAmount(taken);
            reserved.add(transfer);

            if (taken == item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                ItemStack remainder = item.clone();
                remainder.setAmount(item.getAmount() - taken);
                inventory.setItem(slot, remainder);
            }
            remaining -= taken;
        }
        return Optional.of(new InventoryReservation(List.copyOf(reserved)));
    }

    public boolean fits(Inventory inventory) {
        ItemStack[] simulated = inventory.getStorageContents();
        for (int slot = 0; slot < simulated.length; slot++) {
            if (simulated[slot] != null) {
                simulated[slot] = simulated[slot].clone();
            }
        }
        for (ItemStack transfer : items) {
            int remaining = transfer.getAmount();
            for (ItemStack existing : simulated) {
                if (existing == null || !existing.isSimilar(transfer)) {
                    continue;
                }
                int maximum = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize());
                int moved = Math.min(remaining, maximum - existing.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                remaining -= moved;
                if (remaining == 0) {
                    break;
                }
            }
            for (int slot = 0; slot < simulated.length && remaining > 0; slot++) {
                if (simulated[slot] != null && !simulated[slot].getType().isAir()) {
                    continue;
                }
                ItemStack added = transfer.clone();
                int maximum = Math.min(added.getMaxStackSize(), inventory.getMaxStackSize());
                added.setAmount(Math.min(remaining, maximum));
                simulated[slot] = added;
                remaining -= added.getAmount();
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    public List<ItemStack> deliver(Inventory inventory) {
        Map<Integer, ItemStack> leftovers = inventory.addItem(
                items.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        return List.copyOf(leftovers.values());
    }
}
