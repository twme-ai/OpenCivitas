package dev.opencivitas.claim;

public enum ClaimResult {
    SUCCESS,
    CLAIM_NOT_FOUND,
    NO_PERMISSION,
    OVERLAP,
    INSUFFICIENT_BLOCKS,
    MAX_BLOCKS,
    INSUFFICIENT_FUNDS,
    CITIZEN_NOT_FOUND,
    ALREADY_TRUSTED,
    NOT_TRUSTED,
    SELF
}
