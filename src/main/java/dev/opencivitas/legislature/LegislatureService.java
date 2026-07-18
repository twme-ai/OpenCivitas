package dev.opencivitas.legislature;

import dev.opencivitas.election.Election;
import dev.opencivitas.election.ElectionActionResult;
import dev.opencivitas.election.ElectionDetails;
import dev.opencivitas.election.ElectionOperation;
import dev.opencivitas.election.ElectionRepository;
import dev.opencivitas.election.ElectionResultEntry;
import dev.opencivitas.election.ElectionStatus;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

public final class LegislatureService {
    private final LegislatureRepository legislature;
    private final ElectionRepository elections;
    private final Duration referendumDuration;

    public LegislatureService(
            LegislatureRepository legislature,
            ElectionRepository elections,
            Duration referendumDuration
    ) {
        this.legislature = legislature;
        this.elections = elections;
        this.referendumDuration = referendumDuration;
    }

    public void settle(long now) throws SQLException {
        elections.closeDue(now);
        legislature.autoAssent(now);
        openReferendums(now);
        settleReferendums(now);
    }

    private void openReferendums(long now) throws SQLException {
        for (LegislativeBill bill : legislature.pendingReferendums()) {
            String slug = "constitutional-bill-" + bill.id();
            Optional<Election> existing = elections.findBySlug(slug);
            Election referendum;
            if (existing.isPresent()) {
                referendum = existing.get();
            } else {
                long closesAt = Math.addExact(now, referendumDuration.toMillis());
                ElectionOperation created = elections.createReferendum(
                        null, slug, "Constitutional referendum: " + bill.title(), now, closesAt);
                if (created.result() != ElectionActionResult.SUCCESS) continue;
                referendum = created.election().orElseThrow();
            }
            legislature.linkReferendum(bill.id(), referendum.id(), now);
        }
    }

    private void settleReferendums(long now) throws SQLException {
        for (LegislativeBill bill : legislature.activeReferendums()) {
            if (bill.referendumElectionId() == null) continue;
            Optional<ElectionDetails> details = elections.details(bill.referendumElectionId());
            if (details.isEmpty() || details.get().election().status() != ElectionStatus.CLOSED) continue;
            int yes = tally(details.get(), "yes");
            int no = tally(details.get(), "no");
            legislature.settleReferendum(bill.id(), bill.referendumElectionId(), yes, no, now);
        }
    }

    private static int tally(ElectionDetails details, String choice) {
        return details.results().stream()
                .filter(result -> result.choiceId().equals(choice))
                .findFirst()
                .map(ElectionResultEntry::finalTally)
                .map(value -> value.intValueExact())
                .orElse(0);
    }
}
