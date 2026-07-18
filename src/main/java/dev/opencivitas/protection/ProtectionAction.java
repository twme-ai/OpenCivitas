package dev.opencivitas.protection;

import java.util.UUID;

public record ProtectionAction(
        Kind kind,
        ProtectionType protectionType,
        ProtectionAccess access,
        ProtectionSourceType sourceType,
        String sourceIdentifier,
        UUID targetOwnerId,
        boolean adding,
        boolean enabled
) {
    public static ProtectionAction lock(ProtectionType type) {
        return new ProtectionAction(Kind.LOCK, type, null, null, null, null, false, false);
    }

    public static ProtectionAction simple(Kind kind) {
        return new ProtectionAction(kind, null, null, null, null, null, false, false);
    }

    public static ProtectionAction modify(
            boolean adding,
            ProtectionAccess access,
            ProtectionSourceType sourceType,
            String identifier
    ) {
        return new ProtectionAction(
                Kind.MODIFY_ACCESS, null, access, sourceType, identifier, null, adding, false);
    }

    public static ProtectionAction transfer(UUID targetOwnerId) {
        return new ProtectionAction(
                Kind.TRANSFER, null, null, null, null, targetOwnerId, false, false);
    }

    public static ProtectionAction autoClose(boolean enabled) {
        return new ProtectionAction(
                Kind.SET_AUTO_CLOSE, null, null, null, null, null, false, enabled);
    }

    public enum Kind {
        LOCK,
        UNLOCK,
        INFO,
        MODIFY_ACCESS,
        TRANSFER,
        SET_AUTO_CLOSE
    }
}
