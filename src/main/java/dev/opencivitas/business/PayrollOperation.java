package dev.opencivitas.business;

public record PayrollOperation(
        BusinessResult result,
        int recipients,
        long totalPaidCents,
        long businessBalanceCents
) {
}
