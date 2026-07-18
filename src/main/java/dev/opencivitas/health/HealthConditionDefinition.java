package dev.opencivitas.health;

import java.util.List;
import java.util.Map;

public record HealthConditionDefinition(
        String id,
        Map<String, String> names,
        List<String> symptoms,
        String treatmentId,
        CareSetting careSetting,
        double transmissionRadius,
        double transmissionChance,
        Map<String, Double> exposureChances
) {
    public HealthConditionDefinition {
        names = Map.copyOf(names);
        symptoms = List.copyOf(symptoms);
        exposureChances = Map.copyOf(exposureChances);
    }

    public boolean contagious() {
        return transmissionRadius > 0 && transmissionChance > 0;
    }
}
