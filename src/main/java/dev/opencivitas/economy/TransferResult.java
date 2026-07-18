package dev.opencivitas.economy;

public record TransferResult(Status status, long senderBalanceCents) {
    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        ACCOUNT_NOT_FOUND
    }
}
