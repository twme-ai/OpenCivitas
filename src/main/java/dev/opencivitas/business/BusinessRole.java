package dev.opencivitas.business;

public enum BusinessRole {
    PROPRIETOR(true, true),
    CO_PROPRIETOR(true, true),
    MANAGER(true, false),
    SUPERVISOR(false, false),
    EMPLOYEE(false, false);

    private final boolean financial;
    private final boolean staffManagement;

    BusinessRole(boolean financial, boolean staffManagement) {
        this.financial = financial;
        this.staffManagement = staffManagement;
    }

    public boolean canManageFunds() {
        return financial;
    }

    public boolean canManageStaff() {
        return staffManagement;
    }
}
