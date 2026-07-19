package dev.opencivitas.job;

public record JobEarningAccrual(
        long amountCents,
        int actionCount,
        long payableAt,
        boolean blockedPlacedBlock
) {
    public static JobEarningAccrual blocked(long payableAt) {
        return new JobEarningAccrual(0, 0, payableAt, true);
    }
}
