package dev.opencivitas.business;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BusinessRole {
    private static final String CUSTOM_PREFIX = "CUSTOM:";

    public static final BusinessRole PROPRIETOR = builtIn(
            "PROPRIETOR", "Proprietor", 5,
            BusinessPermission.ADMINISTRATOR,
            BusinessPermission.FINANCIAL,
            BusinessPermission.CHEST_SHOP,
            BusinessPermission.DEFAULT);
    public static final BusinessRole CO_PROPRIETOR = builtIn(
            "CO_PROPRIETOR", "Co Proprietor", 4,
            BusinessPermission.ADMINISTRATOR,
            BusinessPermission.FINANCIAL,
            BusinessPermission.CHEST_SHOP,
            BusinessPermission.DEFAULT);
    public static final BusinessRole MANAGER = builtIn(
            "MANAGER", "Manager", 3,
            BusinessPermission.FINANCIAL,
            BusinessPermission.DEFAULT);
    public static final BusinessRole SUPERVISOR = builtIn(
            "SUPERVISOR", "Supervisor", 2,
            BusinessPermission.CHEST_SHOP,
            BusinessPermission.DEFAULT);
    public static final BusinessRole EMPLOYEE = builtIn(
            "EMPLOYEE", "Employee", 1, BusinessPermission.DEFAULT);

    private final String storedName;
    private final String displayName;
    private final int authority;
    private final boolean custom;
    private final Set<BusinessPermission> permissions;

    private BusinessRole(
            String storedName,
            String displayName,
            int authority,
            boolean custom,
            Set<BusinessPermission> permissions
    ) {
        this.storedName = Objects.requireNonNull(storedName, "storedName");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.authority = authority;
        this.custom = custom;
        this.permissions = Set.copyOf(permissions);
    }

    private static BusinessRole builtIn(
            String storedName,
            String displayName,
            int authority,
            BusinessPermission... permissions
    ) {
        EnumSet<BusinessPermission> values = EnumSet.noneOf(BusinessPermission.class);
        values.addAll(Set.of(permissions));
        return new BusinessRole(storedName, displayName, authority, false, values);
    }

    public static BusinessRole custom(
            String key,
            String displayName,
            Set<BusinessPermission> permissions
    ) {
        String normalized = normalizeCustomKey(key);
        int authority = permissions.contains(BusinessPermission.ADMINISTRATOR) ? 3 : 1;
        return new BusinessRole(CUSTOM_PREFIX + normalized, displayName, authority, true, permissions);
    }

    public static BusinessRole valueOf(String name) {
        return builtIn(name).orElseThrow(() -> new IllegalArgumentException("Unknown built-in business role: " + name));
    }

    public static Optional<BusinessRole> builtIn(String name) {
        return switch (name.toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "PROPRIETOR" -> Optional.of(PROPRIETOR);
            case "CO_PROPRIETOR" -> Optional.of(CO_PROPRIETOR);
            case "MANAGER" -> Optional.of(MANAGER);
            case "SUPERVISOR" -> Optional.of(SUPERVISOR);
            case "EMPLOYEE" -> Optional.of(EMPLOYEE);
            default -> Optional.empty();
        };
    }

    public static String normalizeCustomKey(String input) {
        return input.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String name() {
        return storedName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isCustom() {
        return custom;
    }

    public String customKey() {
        if (!custom) {
            throw new IllegalStateException("Built-in roles do not have a custom key");
        }
        return storedName.substring(CUSTOM_PREFIX.length());
    }

    public Set<BusinessPermission> permissions() {
        return permissions;
    }

    public boolean canManageFunds() {
        return permissions.contains(BusinessPermission.FINANCIAL);
    }

    public boolean canManageStaff() {
        return permissions.contains(BusinessPermission.ADMINISTRATOR);
    }

    public boolean canManageShops() {
        return permissions.contains(BusinessPermission.CHEST_SHOP);
    }

    public boolean canManage(BusinessRole target) {
        return canManageStaff() && (this == PROPRIETOR
                ? target != PROPRIETOR
                : target.authority < authority);
    }

    public boolean canAssign(BusinessRole target) {
        return canManage(target)
                && (!target.canManageStaff() || canManageStaff())
                && (!target.canManageFunds() || canManageFunds())
                && (!target.canManageShops() || canManageShops());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BusinessRole role
                && storedName.equals(role.storedName)
                && displayName.equals(role.displayName)
                && authority == role.authority
                && custom == role.custom
                && permissions.equals(role.permissions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(storedName, displayName, authority, custom, permissions);
    }

    @Override
    public String toString() {
        return storedName;
    }
}
