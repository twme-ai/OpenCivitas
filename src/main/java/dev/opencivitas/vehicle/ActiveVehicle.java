package dev.opencivitas.vehicle;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Interaction;

public final class ActiveVehicle {
    private VehicleState state;
    private final VehicleDefinition definition;
    private final ArmorStand seat;
    private final ItemDisplay display;
    private final Interaction interaction;
    private double speed;
    private double fuelRemainder;
    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sprint;
    private boolean fuelWarning;

    ActiveVehicle(
            VehicleState state,
            VehicleDefinition definition,
            ArmorStand seat,
            ItemDisplay display,
            Interaction interaction
    ) {
        this.state = state;
        this.definition = definition;
        this.seat = seat;
        this.display = display;
        this.interaction = interaction;
    }

    public VehicleState state() {
        return state;
    }

    public VehicleDefinition definition() {
        return definition;
    }

    public ArmorStand seat() {
        return seat;
    }

    public ItemDisplay display() {
        return display;
    }

    public Interaction interaction() {
        return interaction;
    }

    void state(VehicleState state) {
        this.state = state;
        if (state.fuel() > 0) fuelWarning = false;
    }

    double speed() {
        return speed;
    }

    void speed(double speed) {
        this.speed = speed;
    }

    double fuelRemainder() {
        return fuelRemainder;
    }

    void fuelRemainder(double fuelRemainder) {
        this.fuelRemainder = fuelRemainder;
    }

    boolean forward() {
        return forward;
    }

    boolean backward() {
        return backward;
    }

    boolean left() {
        return left;
    }

    boolean right() {
        return right;
    }

    boolean jump() {
        return jump;
    }

    boolean sprint() {
        return sprint;
    }

    void input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sprint) {
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.sprint = sprint;
    }

    void clearInput() {
        input(false, false, false, false, false, false);
    }

    boolean fuelWarning() {
        return fuelWarning;
    }

    void fuelWarning(boolean fuelWarning) {
        this.fuelWarning = fuelWarning;
    }
}
