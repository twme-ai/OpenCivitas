package dev.opencivitas.election;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RankedChoiceCounter {
    private static final MathContext MATH = new MathContext(24, RoundingMode.HALF_EVEN);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private RankedChoiceCounter() {
    }

    public static ElectionCount count(
            ElectionMethod method,
            Collection<String> rawChoices,
            List<RankedBallot> ballots,
            int seats
    ) {
        List<String> choices = rawChoices.stream().distinct().sorted().toList();
        if (choices.isEmpty()) {
            return new ElectionCount(List.of(), List.of(), List.of(), Map.of());
        }
        return switch (method) {
            case IRV -> irv(choices, ballots);
            case STV -> stv(choices, ballots, Math.min(seats, choices.size()));
            case REFERENDUM -> referendum(choices, ballots);
        };
    }

    private static ElectionCount irv(List<String> choices, List<RankedBallot> ballots) {
        Set<String> continuing = new LinkedHashSet<>(choices);
        List<String> eliminated = new ArrayList<>();
        List<ElectionRound> rounds = new ArrayList<>();
        Map<String, BigDecimal> finalTallies = zeroTallies(choices);
        while (!continuing.isEmpty()) {
            Map<String, BigDecimal> tallies = tally(choices, continuing, states(ballots, choices));
            finalTallies = tallies;
            String winner = highest(continuing, tallies);
            BigDecimal activeVotes = continuing.stream()
                    .map(tallies::get).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (continuing.size() == 1
                    || tallies.get(winner).compareTo(activeVotes.divide(TWO, MATH)) > 0) {
                rounds.add(new ElectionRound(rounds.size() + 1, tallies, winner, null));
                List<String> ranking = new ArrayList<>();
                ranking.add(winner);
                for (int index = eliminated.size() - 1; index >= 0; index--) {
                    ranking.add(eliminated.get(index));
                }
                return new ElectionCount(List.of(winner), ranking, rounds, finalTallies);
            }
            String loser = lowest(continuing, tallies);
            continuing.remove(loser);
            eliminated.add(loser);
            rounds.add(new ElectionRound(rounds.size() + 1, tallies, null, loser));
        }
        return new ElectionCount(List.of(), List.copyOf(eliminated), rounds, finalTallies);
    }

    private static ElectionCount stv(List<String> choices, List<RankedBallot> ballots, int seats) {
        Set<String> valid = Set.copyOf(choices);
        List<BallotState> states = states(ballots, choices);
        long validBallots = states.stream().filter(ballot -> ballot.first(valid) != null).count();
        BigDecimal quota = BigDecimal.valueOf(validBallots / (seats + 1L) + 1L);
        Set<String> continuing = new LinkedHashSet<>(choices);
        List<String> elected = new ArrayList<>();
        List<String> eliminated = new ArrayList<>();
        List<ElectionRound> rounds = new ArrayList<>();
        Map<String, BigDecimal> finalTallies = zeroTallies(choices);

        while (elected.size() < seats && !continuing.isEmpty()) {
            Map<BallotState, String> allocations = allocations(states, continuing);
            Map<String, BigDecimal> tallies = tallyAllocations(choices, allocations);
            finalTallies = tallies;
            int seatsLeft = seats - elected.size();
            if (continuing.size() <= seatsLeft) {
                String winner = highest(continuing, tallies);
                continuing.remove(winner);
                elected.add(winner);
                rounds.add(new ElectionRound(rounds.size() + 1, tallies, winner, null));
                continue;
            }

            List<String> reachedQuota = continuing.stream()
                    .filter(choice -> tallies.get(choice).compareTo(quota) >= 0)
                    .sorted(highestComparator(tallies))
                    .toList();
            if (!reachedQuota.isEmpty()) {
                String winner = reachedQuota.getFirst();
                BigDecimal tally = tallies.get(winner);
                BigDecimal surplus = tally.subtract(quota);
                BigDecimal transferFactor = tally.signum() == 0
                        ? BigDecimal.ZERO : surplus.divide(tally, MATH);
                allocations.forEach((ballot, allocation) -> {
                    if (winner.equals(allocation)) {
                        ballot.weight = ballot.weight.multiply(transferFactor, MATH);
                    }
                });
                continuing.remove(winner);
                elected.add(winner);
                rounds.add(new ElectionRound(rounds.size() + 1, tallies, winner, null));
            } else {
                String loser = lowest(continuing, tallies);
                continuing.remove(loser);
                eliminated.add(loser);
                rounds.add(new ElectionRound(rounds.size() + 1, tallies, null, loser));
            }
        }

        List<String> ranking = new ArrayList<>(elected);
        List<String> unelectedContinuing = continuing.stream()
                .sorted(highestComparator(finalTallies)).toList();
        ranking.addAll(unelectedContinuing);
        for (int index = eliminated.size() - 1; index >= 0; index--) {
            ranking.add(eliminated.get(index));
        }
        return new ElectionCount(elected, ranking, rounds, finalTallies);
    }

    private static ElectionCount referendum(List<String> choices, List<RankedBallot> ballots) {
        List<BallotState> states = states(ballots, choices);
        Set<String> continuing = Set.copyOf(choices);
        Map<String, BigDecimal> tallies = tally(choices, continuing, states);
        String affirmative = choices.contains("yes") ? "yes" : choices.getFirst();
        String negative = choices.contains("no") ? "no" : choices.getLast();
        String winner = tallies.get(affirmative).compareTo(tallies.get(negative)) > 0
                ? affirmative : negative;
        List<String> ranking = choices.stream().sorted(highestComparator(tallies)).toList();
        return new ElectionCount(
                List.of(winner), ranking,
                List.of(new ElectionRound(1, tallies, winner, null)), tallies);
    }

    private static List<BallotState> states(List<RankedBallot> ballots, List<String> choices) {
        Set<String> valid = Set.copyOf(choices);
        return ballots.stream()
                .map(ballot -> new BallotState(ballot.preferences().stream()
                        .filter(valid::contains).distinct().toList()))
                .toList();
    }

    private static Map<BallotState, String> allocations(List<BallotState> ballots, Set<String> continuing) {
        Map<BallotState, String> allocations = new HashMap<>();
        for (BallotState ballot : ballots) {
            String choice = ballot.first(continuing);
            if (choice != null && ballot.weight.signum() > 0) allocations.put(ballot, choice);
        }
        return allocations;
    }

    private static Map<String, BigDecimal> tally(
            List<String> choices, Set<String> continuing, List<BallotState> ballots) {
        return tallyAllocations(choices, allocations(ballots, continuing));
    }

    private static Map<String, BigDecimal> tallyAllocations(
            List<String> choices, Map<BallotState, String> allocations) {
        Map<String, BigDecimal> tallies = zeroTallies(choices);
        allocations.forEach((ballot, choice) -> tallies.compute(
                choice, (ignored, value) -> value.add(ballot.weight, MATH)));
        return Map.copyOf(tallies);
    }

    private static Map<String, BigDecimal> zeroTallies(List<String> choices) {
        Map<String, BigDecimal> tallies = new LinkedHashMap<>();
        choices.forEach(choice -> tallies.put(choice, BigDecimal.ZERO));
        return tallies;
    }

    private static String highest(Set<String> choices, Map<String, BigDecimal> tallies) {
        return choices.stream().min(highestComparator(tallies)).orElseThrow();
    }

    private static String lowest(Set<String> choices, Map<String, BigDecimal> tallies) {
        return choices.stream().min(Comparator
                .comparing((String choice) -> tallies.get(choice))
                .thenComparing(Comparator.reverseOrder())).orElseThrow();
    }

    private static Comparator<String> highestComparator(Map<String, BigDecimal> tallies) {
        return Comparator.<String, BigDecimal>comparing(tallies::get).reversed()
                .thenComparing(Comparator.naturalOrder());
    }

    private static final class BallotState {
        private final List<String> preferences;
        private BigDecimal weight = BigDecimal.ONE;

        private BallotState(List<String> preferences) {
            this.preferences = preferences;
        }

        private String first(Set<String> continuing) {
            return preferences.stream().filter(continuing::contains).findFirst().orElse(null);
        }
    }
}
