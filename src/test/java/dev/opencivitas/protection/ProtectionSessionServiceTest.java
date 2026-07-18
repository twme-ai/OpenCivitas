package dev.opencivitas.protection;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionSessionServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void actionsAreSingleUseUnlessPersistModeIsEnabled() {
        ProtectionSessionService sessions = new ProtectionSessionService();
        ProtectionAction action = ProtectionAction.lock(ProtectionType.PRIVATE);
        sessions.setAction(PLAYER, action);
        assertEquals(action, sessions.consumeAction(PLAYER).orElseThrow());
        assertTrue(sessions.consumeAction(PLAYER).isEmpty());

        assertTrue(sessions.toggleMode(PLAYER, ProtectionMode.PERSIST));
        sessions.setAction(PLAYER, action);
        assertEquals(action, sessions.consumeAction(PLAYER).orElseThrow());
        assertEquals(action, sessions.consumeAction(PLAYER).orElseThrow());
        assertFalse(sessions.toggleMode(PLAYER, ProtectionMode.PERSIST));
        assertEquals(action, sessions.consumeAction(PLAYER).orElseThrow());
        assertTrue(sessions.consumeAction(PLAYER).isEmpty());
    }

    @Test
    void quitCleanupRemovesModesActionsAndPasswordAuthorization() {
        ProtectionSessionService sessions = new ProtectionSessionService();
        sessions.setAction(PLAYER, ProtectionAction.simple(ProtectionAction.Kind.INFO));
        sessions.toggleMode(PLAYER, ProtectionMode.NOLOCK);
        sessions.addPasswordAuthorizations(PLAYER, Set.of("hash"));
        sessions.stageCommandSecret(PLAYER, "secret");
        sessions.promptForActionSecret(PLAYER, ProtectionAction.modify(
                true, ProtectionAccess.NORMAL, ProtectionSourceType.PASSWORD, ""));

        sessions.clear(PLAYER);
        assertTrue(sessions.consumeAction(PLAYER).isEmpty());
        assertFalse(sessions.hasMode(PLAYER, ProtectionMode.NOLOCK));
        assertTrue(sessions.passwordHashes(PLAYER).isEmpty());
        assertTrue(sessions.consumeCommandSecret(PLAYER).isEmpty());
        assertTrue(sessions.consumeSecretAction(PLAYER).isEmpty());
        assertFalse(sessions.consumePasswordPrompt(PLAYER));
    }

    @Test
    void cancelClearsActionsAndBothSecretPromptTypes() {
        ProtectionSessionService sessions = new ProtectionSessionService();
        sessions.setAction(PLAYER, ProtectionAction.simple(ProtectionAction.Kind.INFO));
        sessions.stageCommandSecret(PLAYER, "secret");
        sessions.promptForPassword(PLAYER);

        sessions.cancelPending(PLAYER);
        assertTrue(sessions.consumeAction(PLAYER).isEmpty());
        assertTrue(sessions.consumeCommandSecret(PLAYER).isEmpty());
        assertFalse(sessions.consumePasswordPrompt(PLAYER));

        sessions.promptForActionSecret(PLAYER, ProtectionAction.modify(
                true, ProtectionAccess.NORMAL, ProtectionSourceType.PASSWORD, ""));
        sessions.cancelPending(PLAYER);
        assertTrue(sessions.consumeSecretAction(PLAYER).isEmpty());
    }

    @Test
    void passwordAuthorizationsAccumulateUntilQuit() {
        ProtectionSessionService sessions = new ProtectionSessionService();
        sessions.addPasswordAuthorizations(PLAYER, Set.of("first"));
        sessions.addPasswordAuthorizations(PLAYER, Set.of("second"));
        sessions.addPasswordAuthorizations(PLAYER, Set.of());

        assertEquals(Set.of("first", "second"), sessions.passwordHashes(PLAYER));
    }
}
