package dev.opencivitas.chat;

import java.util.Optional;

public record AdvertisementAttempt(
        ChatResult result,
        Optional<Advertisement> advertisement,
        long remainingCooldownMillis
) {
    public static AdvertisementAttempt success(Advertisement advertisement) {
        return new AdvertisementAttempt(ChatResult.SUCCESS, Optional.of(advertisement), 0);
    }

    public static AdvertisementAttempt result(ChatResult result) {
        return new AdvertisementAttempt(result, Optional.empty(), 0);
    }

    public static AdvertisementAttempt cooldown(long remainingMillis) {
        return new AdvertisementAttempt(ChatResult.COOLDOWN, Optional.empty(), Math.max(1, remainingMillis));
    }
}
