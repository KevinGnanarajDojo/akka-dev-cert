package io.example.application;

import static org.junit.jupiter.api.Assertions.*;

import akka.Done;
import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.application.BookingSlotEntity.Command;
import io.example.domain.BookingEvent;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

public class BookingSlotEntityTest {

  // Use a future-dated entity ID so booking validation passes.
  // The entity ID serves as the slotId in YYYY-MM-DD-HH format.
  private static final String FUTURE_SLOT = "2099-12-10-10";
  private static final String PAST_SLOT = "2020-01-01-10";

  private final Participant alice = new Participant("alice", ParticipantType.STUDENT);
  private final Participant superplane = new Participant("superplane", ParticipantType.AIRCRAFT);
  private final Participant superteacher =
      new Participant("superteacher", ParticipantType.INSTRUCTOR);

  @Test
  public void markSlotAvailable() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);
    var cmd = new Command.MarkSlotAvailable(alice);

    var result = testKit.method(BookingSlotEntity::markSlotAvailable).invoke(cmd);

    assertEquals(Done.done(), result.getReply());
    var event = result.getNextEventOfType(BookingEvent.ParticipantMarkedAvailable.class);
    assertEquals("alice", event.participantId());
    assertEquals(ParticipantType.STUDENT, event.participantType());
    assertTrue(testKit.getState().isWaiting("alice", ParticipantType.STUDENT));
  }

  @Test
  public void markSlotAvailableDuplicate() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);
    var cmd = new Command.MarkSlotAvailable(alice);

    // First mark succeeds
    testKit.method(BookingSlotEntity::markSlotAvailable).invoke(cmd);

    // Second mark should fail
    var result = testKit.method(BookingSlotEntity::markSlotAvailable).invoke(cmd);
    assertTrue(result.isError());
  }

  @Test
  public void unmarkSlotAvailable() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);

    // Mark first
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(alice));

    // Unmark
    var result =
        testKit
            .method(BookingSlotEntity::unmarkSlotAvailable)
            .invoke(new Command.UnmarkSlotAvailable(alice));

    assertEquals(Done.done(), result.getReply());
    result.getNextEventOfType(BookingEvent.ParticipantUnmarkedAvailable.class);
    assertFalse(testKit.getState().isWaiting("alice", ParticipantType.STUDENT));
  }

  @Test
  public void unmarkNotAvailable() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);

    var result =
        testKit
            .method(BookingSlotEntity::unmarkSlotAvailable)
            .invoke(new Command.UnmarkSlotAvailable(alice));

    assertTrue(result.isError());
  }

  @Test
  public void bookSlotSuccess() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);

    // Mark all 3 participants available
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(alice));
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(superplane));
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(superteacher));

    // Book
    var bookCmd =
        new Command.BookReservation("alice", "superplane", "superteacher", "booking1");
    var result = testKit.method(BookingSlotEntity::bookSlot).invoke(bookCmd);

    assertEquals(Done.done(), result.getReply());

    // Should have 3 ParticipantBooked events
    result.getNextEventOfType(BookingEvent.ParticipantBooked.class);
    result.getNextEventOfType(BookingEvent.ParticipantBooked.class);
    result.getNextEventOfType(BookingEvent.ParticipantBooked.class);

    // Available set should be empty, bookings should have 3 entries
    var state = testKit.getState();
    assertTrue(state.available().isEmpty());
    assertEquals(3, state.bookings().size());
  }

  @Test
  public void bookSlotMissingParticipant() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);

    // Only mark 2 of 3 participants
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(alice));
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(superplane));

    var bookCmd =
        new Command.BookReservation("alice", "superplane", "superteacher", "booking1");
    var result = testKit.method(BookingSlotEntity::bookSlot).invoke(bookCmd);

    assertTrue(result.isError());
  }

  @Test
  public void bookSlotPastSlot() {
    var testKit = EventSourcedTestKit.of(PAST_SLOT, BookingSlotEntity::new);

    // Mark all available
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(alice));
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(superplane));
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(superteacher));

    var bookCmd =
        new Command.BookReservation("alice", "superplane", "superteacher", "booking1");
    var result = testKit.method(BookingSlotEntity::bookSlot).invoke(bookCmd);

    assertTrue(result.isError());
  }

  @Test
  public void cancelBooking() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);

    // Mark and book
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(alice));
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(superplane));
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(superteacher));
    testKit
        .method(BookingSlotEntity::bookSlot)
        .invoke(
            new Command.BookReservation(
                "alice", "superplane", "superteacher", "booking1"));

    // Cancel
    var result = testKit.method(BookingSlotEntity::cancelBooking).invoke("booking1");

    assertEquals(Done.done(), result.getReply());

    // Should have 3 ParticipantCanceled events
    result.getNextEventOfType(BookingEvent.ParticipantCanceled.class);
    result.getNextEventOfType(BookingEvent.ParticipantCanceled.class);
    result.getNextEventOfType(BookingEvent.ParticipantCanceled.class);

    // State should be empty
    var state = testKit.getState();
    assertTrue(state.bookings().isEmpty());
    assertTrue(state.available().isEmpty());
  }

  @Test
  public void cancelNonexistentBooking() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);

    var result = testKit.method(BookingSlotEntity::cancelBooking).invoke("nonexistent");

    assertTrue(result.isError());
  }

  @Test
  public void getSlot() {
    var testKit = EventSourcedTestKit.of(FUTURE_SLOT, BookingSlotEntity::new);

    // Mark one participant
    testKit
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new Command.MarkSlotAvailable(alice));

    var result = testKit.method(BookingSlotEntity::getSlot).invoke();
    var timeslot = result.getReply();

    assertEquals(1, timeslot.available().size());
    assertTrue(timeslot.isWaiting("alice", ParticipantType.STUDENT));
    assertTrue(timeslot.bookings().isEmpty());
  }
}
