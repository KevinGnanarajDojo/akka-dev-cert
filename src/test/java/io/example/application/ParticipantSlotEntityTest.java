package io.example.application;

import static org.junit.jupiter.api.Assertions.*;

import akka.Done;
import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.application.ParticipantSlotEntity.Commands;
import io.example.application.ParticipantSlotEntity.Event;
import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

public class ParticipantSlotEntityTest {

  private static final String SLOT_ID = "2099-12-10-10";
  private static final String PARTICIPANT_ID = "alice";
  private static final ParticipantType PARTICIPANT_TYPE = ParticipantType.STUDENT;

  @Test
  public void markAvailable() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);
    var cmd = new Commands.MarkAvailable(SLOT_ID, PARTICIPANT_ID, PARTICIPANT_TYPE);

    var result = testKit.method(ParticipantSlotEntity::markAvailable).invoke(cmd);

    assertEquals(Done.done(), result.getReply());
    var event = result.getNextEventOfType(Event.MarkedAvailable.class);
    assertEquals(SLOT_ID, event.slotId());
    assertEquals(PARTICIPANT_ID, event.participantId());
    assertEquals(PARTICIPANT_TYPE, event.participantType());

    var state = testKit.getState();
    assertEquals("available", state.status());
    assertEquals(SLOT_ID, state.slotId());
    assertEquals(PARTICIPANT_ID, state.participantId());
  }

  @Test
  public void unmarkAvailable() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);

    // Mark first, then unmark
    testKit
        .method(ParticipantSlotEntity::markAvailable)
        .invoke(new Commands.MarkAvailable(SLOT_ID, PARTICIPANT_ID, PARTICIPANT_TYPE));

    var result =
        testKit
            .method(ParticipantSlotEntity::unmarkAvailable)
            .invoke(new Commands.UnmarkAvailable(SLOT_ID, PARTICIPANT_ID, PARTICIPANT_TYPE));

    assertEquals(Done.done(), result.getReply());
    result.getNextEventOfType(Event.UnmarkedAvailable.class);

    var state = testKit.getState();
    assertEquals("unavailable", state.status());
  }

  @Test
  public void book() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);
    var cmd = new Commands.Book(SLOT_ID, PARTICIPANT_ID, PARTICIPANT_TYPE, "booking1");

    var result = testKit.method(ParticipantSlotEntity::book).invoke(cmd);

    assertEquals(Done.done(), result.getReply());
    var event = result.getNextEventOfType(Event.Booked.class);
    assertEquals("booking1", event.bookingId());

    var state = testKit.getState();
    assertEquals("booked", state.status());
  }

  @Test
  public void cancel() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);

    // Book first, then cancel
    testKit
        .method(ParticipantSlotEntity::book)
        .invoke(new Commands.Book(SLOT_ID, PARTICIPANT_ID, PARTICIPANT_TYPE, "booking1"));

    var result =
        testKit
            .method(ParticipantSlotEntity::cancel)
            .invoke(new Commands.Cancel(SLOT_ID, PARTICIPANT_ID, PARTICIPANT_TYPE, "booking1"));

    assertEquals(Done.done(), result.getReply());
    result.getNextEventOfType(Event.Canceled.class);

    var state = testKit.getState();
    assertEquals("canceled", state.status());
  }
}
