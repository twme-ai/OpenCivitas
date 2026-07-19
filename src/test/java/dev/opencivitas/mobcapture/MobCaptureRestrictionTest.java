package dev.opencivitas.mobcapture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MobCaptureRestrictionTest {
    @Test
    void documentedRestrictionsUseAStablePriority() {
        assertEquals(MobCaptureRestriction.ALLOWED,
                MobCaptureRestriction.evaluate(false, false, false, false));
        assertEquals(MobCaptureRestriction.NAMED,
                MobCaptureRestriction.evaluate(true, true, true, true));
        assertEquals(MobCaptureRestriction.BABY,
                MobCaptureRestriction.evaluate(false, true, true, true));
        assertEquals(MobCaptureRestriction.TAMED,
                MobCaptureRestriction.evaluate(false, false, true, true));
        assertEquals(MobCaptureRestriction.SHEARED,
                MobCaptureRestriction.evaluate(false, false, false, true));
    }
}
