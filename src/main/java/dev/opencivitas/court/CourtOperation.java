package dev.opencivitas.court;

import java.util.Optional;

public record CourtOperation(CourtActionResult result, Optional<CourtCase> courtCase) {
    public static CourtOperation result(CourtActionResult result) {
        return new CourtOperation(result, Optional.empty());
    }

    public static CourtOperation courtCase(CourtCase courtCase) {
        return new CourtOperation(CourtActionResult.SUCCESS, Optional.of(courtCase));
    }
}
