package dev.opencivitas.business;

public enum BusinessRole {
    PROPRIETOR(true, true, 5),
    CO_PROPRIETOR(true, true, 4),
    MANAGER(true, false, 3),
    SUPERVISOR(false, false, 2),
    EMPLOYEE(false, false, 1);

    private final boolean financial;
    private final boolean staffManagement;
    private final int authority;

    BusinessRole(boolean financial, boolean staffManagement, int authority) {
        this.financial = financial;
        this.staffManagement = staffManagement;
        this.authority = authority;
    }

    public boolean canManageFunds() {
        return financial;
    }

    public boolean canManageStaff() {
        return staffManagement;
    }

    public boolean canManage(BusinessRole target) {
        return staffManagement && (this == PROPRIETOR
                ? target != PROPRIETOR
                : target.authority < authority);
    }

    public boolean canAssign(BusinessRole target) {
        return canManage(target);
    }
}
