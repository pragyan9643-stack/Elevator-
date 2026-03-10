import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.TreeSet;

// ============================================================
//  ENUMS
// ============================================================

/**
 * Represents the direction of elevator movement.
 */
enum Direction {
    UP, DOWN, IDLE
}

/**
 * Represents the current operational state of the elevator.
 */
enum ElevatorState {
    MOVING, STOPPED, DOORS_OPEN, DOORS_CLOSED
}

// ============================================================
//  REQUEST
// ============================================================

/**
 * Represents a request made either from inside the elevator (cabin request)
 * or from a floor panel (floor request).
 */
class Request implements Comparable<Request> {

    public enum RequestType {
        FLOOR_REQUEST,  // pressed from outside the elevator on a floor
        CABIN_REQUEST   // pressed from inside the elevator cabin
    }

    private final int         floor;
    private final Direction   direction;
    private final RequestType type;

    public Request(int floor, Direction direction, RequestType type) {
        this.floor     = floor;
        this.direction = direction;
        this.type      = type;
    }

    public int         getFloor()     { return floor; }
    public Direction   getDirection() { return direction; }
    public RequestType getType()      { return type; }

    @Override
    public int compareTo(Request other) {
        return Integer.compare(this.floor, other.floor);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Request)) return false;
        Request other = (Request) obj;
        return this.floor == other.floor
            && this.direction == other.direction
            && this.type == other.type;
    }

    @Override
    public int hashCode() {
        int result = floor;
        result = 31 * result + (direction != null ? direction.hashCode() : 0);
        result = 31 * result + (type      != null ? type.hashCode()      : 0);
        return result;
    }

    @Override
    public String toString() {
        return String.format("Request[floor=%d, direction=%s, type=%s]", floor, direction, type);
    }
}

// ============================================================
//  INTERFACE
// ============================================================

/**
 * Interface defining the core contract of an Elevator.
 */
interface IElevator {
    /** Add a cabin (in-car) request. */
    void addCabinRequest(int floor);

    /** Add a floor (external panel) request. */
    void addFloorRequest(int floor, Direction direction);

    /** Process all pending requests until the elevator is idle. */
    void processRequests();

    int           getCurrentFloor();
    Direction     getCurrentDirection();
    ElevatorState getCurrentState();
    void          displayStatus();
}

// ============================================================
//  ELEVATOR  (SCAN / Elevator Algorithm)
// ============================================================

/**
 * Single elevator car using the SCAN algorithm.
 *
 * <p>The elevator sweeps UP servicing every requested floor in order,
 * then reverses and sweeps DOWN – minimising total travel and avoiding
 * starvation.</p>
 *
 * <p>Collections used:
 * <ul>
 *   <li>{@link TreeSet} – O(log n) ordered floor lookup; first()/last()
 *       always returns the nearest floor in the current direction.</li>
 *   <li>{@link LinkedList} as a {@link Queue} – FIFO staging for incoming
 *       requests before they are classified into the directional sets.</li>
 * </ul>
 * </p>
 */
class Elevator implements IElevator {

    // ── Configuration ──────────────────────────────────────
    private static final int DOOR_OPEN_DELAY_MS = 500;

    private final String elevatorId;
    private final int    minFloor;
    private final int    maxFloor;

    // ── State ───────────────────────────────────────────────
    private int           currentFloor;
    private Direction     currentDirection;
    private ElevatorState currentState;

    // ── Queues ──────────────────────────────────────────────
    /** Floors to visit while going UP (ascending order). */
    private final TreeSet<Integer> upQueue;

    /** Floors to visit while going DOWN (descending – we poll last()). */
    private final TreeSet<Integer> downQueue;

    /** Incoming, unclassified requests. */
    private final Queue<Request> pendingRequests;

    // ── Constructor ─────────────────────────────────────────
    public Elevator(String elevatorId, int minFloor, int maxFloor, int startFloor) {
        validateFloor(startFloor, minFloor, maxFloor);

        this.elevatorId       = elevatorId;
        this.minFloor         = minFloor;
        this.maxFloor         = maxFloor;
        this.currentFloor     = startFloor;
        this.currentDirection = Direction.IDLE;
        this.currentState     = ElevatorState.STOPPED;

        this.upQueue          = new TreeSet<>();
        this.downQueue        = new TreeSet<>();
        this.pendingRequests  = new LinkedList<>();
    }

    // ── IElevator ───────────────────────────────────────────

    @Override
    public void addCabinRequest(int floor) {
        validateFloor(floor, minFloor, maxFloor);
        pendingRequests.offer(new Request(floor, Direction.IDLE, Request.RequestType.CABIN_REQUEST));
        System.out.printf("[%s] Cabin request added for floor %d%n", elevatorId, floor);
    }

    @Override
    public void addFloorRequest(int floor, Direction direction) {
        validateFloor(floor, minFloor, maxFloor);
        if (direction == Direction.IDLE) {
            throw new IllegalArgumentException("Floor request direction cannot be IDLE.");
        }
        pendingRequests.offer(new Request(floor, direction, Request.RequestType.FLOOR_REQUEST));
        System.out.printf("[%s] Floor request added – floor %d going %s%n", elevatorId, floor, direction);
    }

    @Override
    public void processRequests() {
        classifyPendingRequests();

        while (!upQueue.isEmpty() || !downQueue.isEmpty()) {
            classifyPendingRequests();

            if (currentDirection == Direction.IDLE) {
                decideInitialDirection();
            }

            if      (currentDirection == Direction.UP)   processUpDirection();
            else if (currentDirection == Direction.DOWN) processDownDirection();
        }

        currentDirection = Direction.IDLE;
        currentState     = ElevatorState.STOPPED;
        System.out.printf("[%s] All requests serviced. Elevator idle at floor %d.%n%n",
                          elevatorId, currentFloor);
    }

    @Override public int           getCurrentFloor()     { return currentFloor; }
    @Override public Direction     getCurrentDirection() { return currentDirection; }
    @Override public ElevatorState getCurrentState()     { return currentState; }

    @Override
    public void displayStatus() {
        System.out.println("======================================");
        System.out.printf ("  Elevator   : %s%n", elevatorId);
        System.out.printf ("  Floor      : %d%n", currentFloor);
        System.out.printf ("  Direction  : %s%n", currentDirection);
        System.out.printf ("  State      : %s%n", currentState);
        System.out.printf ("  Up queue   : %s%n", upQueue);
        System.out.printf ("  Down queue : %s%n", downQueue);
        System.out.println("======================================");
    }

    // ── Private helpers ─────────────────────────────────────

    private void classifyPendingRequests() {
        while (!pendingRequests.isEmpty()) {
            Request req         = pendingRequests.poll();
            int     targetFloor = req.getFloor();

            if (req.getType() == Request.RequestType.CABIN_REQUEST) {
                if      (targetFloor > currentFloor) upQueue.add(targetFloor);
                else if (targetFloor < currentFloor) downQueue.add(targetFloor);
                else                                 openAndCloseDoors(targetFloor); // already here
            } else {
                if (req.getDirection() == Direction.UP) upQueue.add(targetFloor);
                else                                    downQueue.add(targetFloor);
            }
        }
    }

    private void decideInitialDirection() {
        if (!upQueue.isEmpty() && !downQueue.isEmpty()) {
            int nearestUp   = upQueue.first();
            int nearestDown = downQueue.last();
            currentDirection = (Math.abs(nearestUp   - currentFloor) <=
                                Math.abs(nearestDown - currentFloor))
                               ? Direction.UP : Direction.DOWN;
        } else if (!upQueue.isEmpty())   currentDirection = Direction.UP;
        else if   (!downQueue.isEmpty()) currentDirection = Direction.DOWN;
    }

    private void processUpDirection() {
        while (!upQueue.isEmpty()) {
            int nextFloor = upQueue.first();
            moveToFloor(nextFloor);
            upQueue.remove(nextFloor);
            openAndCloseDoors(nextFloor);
            classifyPendingRequests();
        }
        if (!downQueue.isEmpty()) {
            System.out.printf("[%s] Switching direction: UP → DOWN%n", elevatorId);
            currentDirection = Direction.DOWN;
        }
    }

    private void processDownDirection() {
        while (!downQueue.isEmpty()) {
            int nextFloor = downQueue.last();
            moveToFloor(nextFloor);
            downQueue.remove(nextFloor);
            openAndCloseDoors(nextFloor);
            classifyPendingRequests();
        }
        if (!upQueue.isEmpty()) {
            System.out.printf("[%s] Switching direction: DOWN → UP%n", elevatorId);
            currentDirection = Direction.UP;
        }
    }

    private void moveToFloor(int targetFloor) {
        if (targetFloor == currentFloor) return;

        currentState = ElevatorState.MOVING;
        Direction travelDir = (targetFloor > currentFloor) ? Direction.UP : Direction.DOWN;
        int step = (travelDir == Direction.UP) ? 1 : -1;

        System.out.printf("[%s] Moving %s from floor %d to floor %d…%n",
                          elevatorId, travelDir, currentFloor, targetFloor);
        while (currentFloor != targetFloor) {
            currentFloor += step;
            System.out.printf("      → Floor %d%n", currentFloor);
        }
        currentState = ElevatorState.STOPPED;
    }

    private void openAndCloseDoors(int floor) {
        currentState = ElevatorState.DOORS_OPEN;
        System.out.printf("[%s] Doors OPEN  at floor %d%n", elevatorId, floor);
        try { Thread.sleep(DOOR_OPEN_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        currentState = ElevatorState.DOORS_CLOSED;
        System.out.printf("[%s] Doors CLOSED at floor %d%n", elevatorId, floor);
    }

    private void validateFloor(int floor, int min, int max) {
        if (floor < min || floor > max) {
            throw new IllegalArgumentException(
                String.format("Floor %d is out of range [%d, %d].", floor, min, max));
        }
    }
}

// ============================================================
//  ELEVATOR CONTROLLER  (multi-car dispatcher)
// ============================================================

/**
 * Manages a bank of elevators and routes incoming floor calls
 * to the most suitable car using a nearest-car heuristic.
 */
class ElevatorController {

    private final List<Elevator> elevators;
    private final int            minFloor;
    private final int            maxFloor;

    public ElevatorController(int numberOfElevators, int minFloor, int maxFloor) {
        if (numberOfElevators <= 0) throw new IllegalArgumentException("At least one elevator required.");
        this.minFloor  = minFloor;
        this.maxFloor  = maxFloor;
        this.elevators = new ArrayList<>(numberOfElevators);
        for (int i = 0; i < numberOfElevators; i++) {
            elevators.add(new Elevator("E" + (i + 1), minFloor, maxFloor, minFloor));
        }
        System.out.printf("ElevatorController: %d elevator(s), floors %d–%d.%n%n",
                          numberOfElevators, minFloor, maxFloor);
    }

    public void requestElevator(int floor, Direction direction) {
        Elevator best = findBestElevator(floor, direction);
        System.out.printf("[Controller] Floor %d (%s) → dispatched to elevator at floor %d%n",
                          floor, direction, best.getCurrentFloor());
        best.addFloorRequest(floor, direction);
    }

    public void cabinRequest(int elevatorIndex, int floor) {
        if (elevatorIndex < 0 || elevatorIndex >= elevators.size())
            throw new IndexOutOfBoundsException("Invalid elevator index: " + elevatorIndex);
        elevators.get(elevatorIndex).addCabinRequest(floor);
    }

    public void runAll() {
        for (Elevator e : elevators) e.processRequests();
    }

    public void displayAllStatus() {
        for (Elevator e : elevators) e.displayStatus();
    }

    private Elevator findBestElevator(int floor, Direction direction) {
        Elevator best      = null;
        int      bestScore = Integer.MAX_VALUE;
        for (Elevator e : elevators) {
            int distance = Math.abs(e.getCurrentFloor() - floor);
            int score;
            if (e.getCurrentDirection() == Direction.IDLE) {
                score = distance;
            } else if (e.getCurrentDirection() == direction) {
                boolean canPickUp = (direction == Direction.UP   && e.getCurrentFloor() <= floor)
                                 || (direction == Direction.DOWN && e.getCurrentFloor() >= floor);
                score = canPickUp ? distance : distance + (maxFloor - minFloor);
            } else {
                score = distance + 2 * (maxFloor - minFloor);
            }
            if (score < bestScore) { bestScore = score; best = e; }
        }
        return best;
    }
}

// ============================================================
//  MAIN  –  Simulation Driver
// ============================================================

/**
 * Entry point – three simulation scenarios.
 *
 * Compile & run:
 *   javac ElevatorSystem.java
 *   java  ElevatorSystem
 */
public class ElevatorSystem {

    public static void main(String[] args) {

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║       ELEVATOR SIMULATION – CORE JAVA    ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();

        // ── Scenario 1: Single elevator ─────────────────────
        System.out.println("━━━━ SCENARIO 1: Single Elevator (floors 0-10, starts at 0) ━━━━");
        Elevator lift = new Elevator("SingleLift", 0, 10, 0);
        lift.displayStatus();

        lift.addFloorRequest(3, Direction.UP);
        lift.addFloorRequest(7, Direction.UP);
        lift.addFloorRequest(1, Direction.UP);
        lift.addCabinRequest(5);
        lift.addCabinRequest(9);
        lift.addFloorRequest(8, Direction.DOWN);
        lift.addCabinRequest(2);

        System.out.println("\n▶ Processing all requests…\n");
        lift.processRequests();

        // ── Scenario 2: Controller with 2 elevators ─────────
        System.out.println("━━━━ SCENARIO 2: Controller with 2 Elevators (floors 1-15) ━━━━");
        ElevatorController controller = new ElevatorController(2, 1, 15);

        controller.requestElevator(5,  Direction.UP);
        controller.requestElevator(12, Direction.DOWN);
        controller.requestElevator(3,  Direction.UP);
        controller.requestElevator(9,  Direction.DOWN);
        controller.cabinRequest(0, 10);
        controller.cabinRequest(0, 14);
        controller.cabinRequest(1, 6);
        controller.cabinRequest(1, 2);

        System.out.println();
        controller.displayAllStatus();
        System.out.println("\n▶ Running all elevators…\n");
        controller.runAll();

        // ── Scenario 3: Edge cases ───────────────────────────
        System.out.println("━━━━ SCENARIO 3: Edge Cases ━━━━");
        Elevator edgeLift = new Elevator("EdgeLift", 1, 5, 3);
        edgeLift.addCabinRequest(3);       // already on this floor
        edgeLift.addFloorRequest(5, Direction.DOWN);
        edgeLift.addFloorRequest(1, Direction.UP);
        edgeLift.addCabinRequest(4);
        System.out.println();
        edgeLift.processRequests();

        System.out.println("Simulation complete.");
    }
}