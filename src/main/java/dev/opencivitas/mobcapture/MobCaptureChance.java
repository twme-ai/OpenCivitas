package dev.opencivitas.mobcapture;

public record MobCaptureChance(int numerator, int denominator) {
    public MobCaptureChance {
        if (denominator < 1 || numerator < 1 || numerator > denominator) {
            throw new IllegalArgumentException("Capture chance must satisfy 1 <= numerator <= denominator");
        }
    }

    public boolean succeeds(int roll) {
        if (roll < 0 || roll >= denominator) {
            throw new IllegalArgumentException("Capture roll is outside the configured denominator");
        }
        return roll < numerator;
    }
}
