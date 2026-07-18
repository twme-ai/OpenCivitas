package dev.opencivitas.property;

import java.util.Optional;

public record PropertyOperation(
        PropertyResult result,
        Optional<Property> property,
        long amountCents,
        long balanceCents
) {
    public static PropertyOperation failed(PropertyResult result) {
        return new PropertyOperation(result, Optional.empty(), 0, 0);
    }

    public static PropertyOperation succeeded(Property property, long amountCents, long balanceCents) {
        return new PropertyOperation(PropertyResult.SUCCESS, Optional.ofNullable(property), amountCents, balanceCents);
    }
}
