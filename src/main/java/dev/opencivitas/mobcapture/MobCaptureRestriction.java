package dev.opencivitas.mobcapture;

public enum MobCaptureRestriction {
    ALLOWED,
    NAMED,
    BABY,
    TAMED,
    SHEARED;

    public static MobCaptureRestriction evaluate(
            boolean named,
            boolean baby,
            boolean tamed,
            boolean sheared
    ) {
        if (named) return NAMED;
        if (baby) return BABY;
        if (tamed) return TAMED;
        if (sheared) return SHEARED;
        return ALLOWED;
    }
}
