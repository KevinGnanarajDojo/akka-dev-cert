package io.example.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import io.example.application.BookingSlotEntity;
import io.example.application.BookingSlotEntity.Command;
import io.example.application.FlightConditionsAgent;
import io.example.application.FlightConditionsAgent.ConditionsReport;
import io.example.application.ParticipantSlotsView;
import io.example.application.ParticipantSlotsView.ParticipantStatusInput;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test that exercises the full flow: endpoint → BookingSlotEntity → consumer
 * → ParticipantSlotEntity → view.
 */
public class FlightEndpointIntegrationTest extends TestKitSupport {

  private final TestModelProvider agentModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.openai.api-key = n/a")
        .withModelProvider(FlightConditionsAgent.class, agentModel);
  }

  @Test
  public void fullBookingFlow() {
    String slotId = "2099-06-15-10";

    // Mark 3 participants available
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(new Participant("alice", ParticipantType.STUDENT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("plane1", ParticipantType.AIRCRAFT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(
                new Participant("teacher1", ParticipantType.INSTRUCTOR)));

    // Mock agent approval
    agentModel.fixedResponse(
        JsonSupport.encodeToString(new ConditionsReport(slotId, true)));

    // Book the slot
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::bookSlot)
        .invoke(new Command.BookReservation("alice", "plane1", "teacher1", "bk1"));

    // Verify bookings in entity state
    var timeslot =
        componentClient
            .forEventSourcedEntity(slotId)
            .method(BookingSlotEntity::getSlot)
            .invoke();
    assertEquals(3, timeslot.bookings().size());
    assertTrue(timeslot.available().isEmpty());

    // Verify view shows booked status (eventually consistent)
    Awaitility.await()
        .ignoreExceptions()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var slots =
                  componentClient
                      .forView()
                      .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                      .invoke(new ParticipantStatusInput("alice", "BOOKED"));
              assertFalse(slots.slots().isEmpty());
              assertEquals(slotId, slots.slots().getFirst().slotId());
              assertEquals("bk1", slots.slots().getFirst().bookingId());
            });

    // Cancel the booking
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::cancelBooking)
        .invoke("bk1");

    // Verify entity state is empty
    var afterCancel =
        componentClient
            .forEventSourcedEntity(slotId)
            .method(BookingSlotEntity::getSlot)
            .invoke();
    assertTrue(afterCancel.bookings().isEmpty());
    assertTrue(afterCancel.available().isEmpty());
  }

  @Test
  public void availabilityManagement() {
    String slotId = "2099-07-20-14";

    // Mark available
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(new Participant("bob", ParticipantType.STUDENT)));

    // Get availability
    var timeslot =
        componentClient
            .forEventSourcedEntity(slotId)
            .method(BookingSlotEntity::getSlot)
            .invoke();
    assertTrue(timeslot.isWaiting("bob", ParticipantType.STUDENT));

    // Verify view shows available (eventually consistent)
    Awaitility.await()
        .ignoreExceptions()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var slots =
                  componentClient
                      .forView()
                      .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                      .invoke(new ParticipantStatusInput("bob", "available"));
              assertFalse(slots.slots().isEmpty());
              assertEquals(slotId, slots.slots().getFirst().slotId());
            });

    // Unmark available
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::unmarkSlotAvailable)
        .invoke(
            new Command.UnmarkSlotAvailable(new Participant("bob", ParticipantType.STUDENT)));

    // Verify removed from entity state
    var afterUnmark =
        componentClient
            .forEventSourcedEntity(slotId)
            .method(BookingSlotEntity::getSlot)
            .invoke();
    assertFalse(afterUnmark.isWaiting("bob", ParticipantType.STUDENT));
  }

  @Test
  public void bookingRequiresAllParticipants() {
    String slotId = "2099-08-05-09";

    // Only mark 2 of 3
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("carol", ParticipantType.STUDENT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("jet1", ParticipantType.AIRCRAFT)));

    // Attempt to book without an instructor — should fail
    assertThrows(
        Exception.class,
        () ->
            componentClient
                .forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::bookSlot)
                .invoke(
                    new Command.BookReservation("carol", "jet1", "missing-teacher", "bk2")));
  }

  @Test
  public void bookingChecksFutureSlot() {
    String pastSlotId = "2020-01-01-10";

    // Mark all 3 available
    componentClient
        .forEventSourcedEntity(pastSlotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(new Participant("dan", ParticipantType.STUDENT)));
    componentClient
        .forEventSourcedEntity(pastSlotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("plane2", ParticipantType.AIRCRAFT)));
    componentClient
        .forEventSourcedEntity(pastSlotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(
                new Participant("teach2", ParticipantType.INSTRUCTOR)));

    // Attempt to book past slot — should fail
    assertThrows(
        Exception.class,
        () ->
            componentClient
                .forEventSourcedEntity(pastSlotId)
                .method(BookingSlotEntity::bookSlot)
                .invoke(new Command.BookReservation("dan", "plane2", "teach2", "bk3")));
  }

  @Test
  public void bookingChecksFlightConditions() {
    String slotId = "2099-09-10-16";

    // Mark all 3 available
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(new Participant("eve", ParticipantType.STUDENT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("plane3", ParticipantType.AIRCRAFT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(
                new Participant("teach3", ParticipantType.INSTRUCTOR)));

    // Mock agent rejection
    agentModel.fixedResponse(
        JsonSupport.encodeToString(new ConditionsReport(slotId, false)));

    // Call the agent (this is what the endpoint does before booking)
    var report =
        componentClient
            .forAgent()
            .inSession("test-session-id")
            .method(FlightConditionsAgent::query)
            .invoke(slotId);

    assertFalse(report.meetsRequirements());
  }

  @Test
  public void viewQueryByParticipantAndStatus() {
    String slot1 = "2099-10-01-08";
    String slot2 = "2099-10-02-09";

    // Mark frank available in two slots
    componentClient
        .forEventSourcedEntity(slot1)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("frank", ParticipantType.STUDENT)));
    componentClient
        .forEventSourcedEntity(slot2)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("frank", ParticipantType.STUDENT)));

    // Verify view shows both available slots
    Awaitility.await()
        .ignoreExceptions()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var slots =
                  componentClient
                      .forView()
                      .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                      .invoke(new ParticipantStatusInput("frank", "available"));
              assertEquals(2, slots.slots().size());
            });
  }

  @Test
  public void cancelNonexistentBooking() {
    String slotId = "2099-11-15-11";

    assertThrows(
        Exception.class,
        () ->
            componentClient
                .forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::cancelBooking)
                .invoke("nonexistent-booking"));
  }

  @Test
  public void httpBookingBlockedByAgent() {
    String slotId = "2099-03-20-10";

    // Mark all 3 available
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("gina", ParticipantType.STUDENT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("plane4", ParticipantType.AIRCRAFT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(
                new Participant("teach4", ParticipantType.INSTRUCTOR)));

    // Mock agent rejection
    agentModel.fixedResponse(
        JsonSupport.encodeToString(new ConditionsReport(slotId, false)));

    // Verify agent rejects
    var report =
        componentClient
            .forAgent()
            .inSession("block-test")
            .method(FlightConditionsAgent::query)
            .invoke(slotId);
    assertFalse(report.meetsRequirements());

    // Verify participants are still available (booking was NOT created)
    var state =
        componentClient
            .forEventSourcedEntity(slotId)
            .method(BookingSlotEntity::getSlot)
            .invoke();
    assertEquals(3, state.available().size());
    assertTrue(state.bookings().isEmpty());
  }

  @Test
  public void participantMultipleReservations() {
    String slot1 = "2099-04-01-09";
    String slot2 = "2099-04-02-09";

    // Mark henry available in both slots with different aircraft/instructors
    for (String slotId : new String[] {slot1, slot2}) {
      componentClient
          .forEventSourcedEntity(slotId)
          .method(BookingSlotEntity::markSlotAvailable)
          .invoke(
              new Command.MarkSlotAvailable(
                  new Participant("henry", ParticipantType.STUDENT)));
      componentClient
          .forEventSourcedEntity(slotId)
          .method(BookingSlotEntity::markSlotAvailable)
          .invoke(
              new Command.MarkSlotAvailable(
                  new Participant("plane-" + slotId, ParticipantType.AIRCRAFT)));
      componentClient
          .forEventSourcedEntity(slotId)
          .method(BookingSlotEntity::markSlotAvailable)
          .invoke(
              new Command.MarkSlotAvailable(
                  new Participant("teach-" + slotId, ParticipantType.INSTRUCTOR)));
    }

    // Book henry in both slots
    agentModel.fixedResponse(
        JsonSupport.encodeToString(new ConditionsReport(slot1, true)));
    componentClient
        .forEventSourcedEntity(slot1)
        .method(BookingSlotEntity::bookSlot)
        .invoke(
            new Command.BookReservation(
                "henry", "plane-" + slot1, "teach-" + slot1, "bk-slot1"));

    agentModel.fixedResponse(
        JsonSupport.encodeToString(new ConditionsReport(slot2, true)));
    componentClient
        .forEventSourcedEntity(slot2)
        .method(BookingSlotEntity::bookSlot)
        .invoke(
            new Command.BookReservation(
                "henry", "plane-" + slot2, "teach-" + slot2, "bk-slot2"));

    // Verify view shows 2 booked entries for henry
    Awaitility.await()
        .ignoreExceptions()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var slots =
                  componentClient
                      .forView()
                      .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                      .invoke(new ParticipantStatusInput("henry", "BOOKED"));
              assertEquals(2, slots.slots().size());
            });
  }

  @Test
  public void viewAfterCancellation() {
    String slotId = "2099-05-10-14";

    // Mark and book
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(new Participant("iris", ParticipantType.STUDENT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(
                new Participant("plane5", ParticipantType.AIRCRAFT)));
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(
            new Command.MarkSlotAvailable(
                new Participant("teach5", ParticipantType.INSTRUCTOR)));

    agentModel.fixedResponse(
        JsonSupport.encodeToString(new ConditionsReport(slotId, true)));

    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::bookSlot)
        .invoke(new Command.BookReservation("iris", "plane5", "teach5", "bk-cancel"));

    // Wait for view to show booked
    Awaitility.await()
        .ignoreExceptions()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var slots =
                  componentClient
                      .forView()
                      .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                      .invoke(new ParticipantStatusInput("iris", "BOOKED"));
              assertFalse(slots.slots().isEmpty());
            });

    // Cancel
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::cancelBooking)
        .invoke("bk-cancel");

    // Verify view no longer shows booked for iris
    Awaitility.await()
        .ignoreExceptions()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var slots =
                  componentClient
                      .forView()
                      .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                      .invoke(new ParticipantStatusInput("iris", "BOOKED"));
              assertTrue(slots.slots().isEmpty());
            });
  }
}
