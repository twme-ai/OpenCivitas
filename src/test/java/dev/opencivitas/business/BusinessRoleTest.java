package dev.opencivitas.business;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessRoleTest {
    @Test
    void builtInRolesRetainDocumentedCapabilities() {
        assertTrue(BusinessRole.CO_PROPRIETOR.canManageFunds());
        assertTrue(BusinessRole.CO_PROPRIETOR.canManageStaff());
        assertTrue(BusinessRole.CO_PROPRIETOR.canManageShops());
        assertTrue(BusinessRole.MANAGER.canManageFunds());
        assertFalse(BusinessRole.MANAGER.canManageShops());
        assertTrue(BusinessRole.SUPERVISOR.canManageShops());
        assertFalse(BusinessRole.EMPLOYEE.canManageFunds());
        assertEquals(BusinessRole.CO_PROPRIETOR, BusinessRole.valueOf("CO_PROPRIETOR"));
    }

    @Test
    void customPermissionsAreIndependentAndLeastPrivilege() {
        BusinessRole administrator = BusinessRole.custom(
                "people-lead", "People Lead",
                Set.of(BusinessPermission.ADMINISTRATOR, BusinessPermission.DEFAULT));
        BusinessRole financial = BusinessRole.custom(
                "treasurer", "Treasurer",
                Set.of(BusinessPermission.FINANCIAL, BusinessPermission.DEFAULT));
        BusinessRole ordinary = BusinessRole.custom(
                "associate", "Associate", Set.of(BusinessPermission.DEFAULT));

        assertTrue(administrator.canManage(ordinary));
        assertTrue(administrator.canAssign(ordinary));
        assertFalse(administrator.canAssign(financial));
        assertFalse(financial.canManage(ordinary));
        assertTrue(financial.canManageFunds());
    }
}
