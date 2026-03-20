package io.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.example.domain.BookingEvent;
import io.example.domain.Participant;
import io.example.domain.Timeslot;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "booking-slot")
public class BookingSlotEntity extends EventSourcedEntity<Timeslot, BookingEvent> {

    private final String entityId;
    private static final Logger logger = LoggerFactory.getLogger(BookingSlotEntity.class);

    public BookingSlotEntity(EventSourcedEntityContext context) {
        this.entityId = context.entityId();
    }

    public Effect<Done> markSlotAvailable(Command.MarkSlotAvailable cmd) {
        var participant = cmd.participant();
        if (currentState().isWaiting(participant.id(), participant.participantType())){
            return effects().error("[BSE] Participant is already marked as available");
        }
        return effects()
                .persist(new BookingEvent.ParticipantMarkedAvailable(entityId, participant.id(), participant.participantType()))
                .thenReply(__ -> Done.done());
    }

    public Effect<Done> unmarkSlotAvailable(Command.UnmarkSlotAvailable cmd) {
        var participant = cmd.participant();
        if (!currentState().isWaiting(participant.id(), participant.participantType())){
            return effects().error("[BSE] Participant is not marked as available");
        }
        return effects()
                .persist(new BookingEvent.ParticipantUnmarkedAvailable(entityId, participant.id(), participant.participantType()))
                .thenReply(__ -> Done.done());
    }

    // NOTE: booking a slot should produce 3
    // `ParticipantBooked` events
    public Effect<Done> bookSlot(Command.BookReservation cmd) {

        if (!isFutureSlot(entityId)){
            return effects().error("[BSE] Cannot book a slot in the past");
        }

        if (!currentState().isBookable(cmd.studentId(), cmd.aircraftId(), cmd.instructorId())){
            return effects().error("[BSE] Not all participants are available for booking");
        }

        var events = List.of(
                new BookingEvent.ParticipantBooked(entityId, cmd.studentId(), Participant.ParticipantType.STUDENT, cmd.bookingId()),
                new BookingEvent.ParticipantBooked(entityId, cmd.aircraftId(), Participant.ParticipantType.AIRCRAFT, cmd.bookingId()),
                new BookingEvent.ParticipantBooked(entityId, cmd.instructorId(), Participant.ParticipantType.INSTRUCTOR, cmd.bookingId())
        );
        return effects().persistAll(events).thenReply(__ -> Done.done());
    }

    // NOTE: canceling a booking should produce 3
    // `ParticipantCanceled` events
    public Effect<Done> cancelBooking(String bookingId) {
        var bookingEntries = currentState().findBooking(bookingId);
        if (bookingEntries.isEmpty()){
            return effects().error("[BSE] No booking found with id " + bookingId);
        }

        var events = bookingEntries.stream()
                .map(b -> new BookingEvent.ParticipantCanceled(entityId, b.participant().id(), b.participant().participantType(), bookingId))
                .toList();

        return effects().persistAll(events).thenReply(__ -> Done.done());

    }

    public ReadOnlyEffect<Timeslot> getSlot() {
        return effects().reply(currentState());
    }

    @Override
    public Timeslot emptyState() {
        return new Timeslot(
                // NOTE: these are just estimates for capacity based on it being a sample
                HashSet.newHashSet(10), HashSet.newHashSet(10));
    }

    @Override
    public Timeslot applyEvent(BookingEvent event) {
        // Supply your own implementation to update state based
        // on the event
        logger.info("[BSE] Applying event {}", event);

        return switch (event) {
            case BookingEvent.ParticipantMarkedAvailable e -> currentState().reserve(e);
            case BookingEvent.ParticipantUnmarkedAvailable e -> currentState().unreserve(e);
            case BookingEvent.ParticipantBooked e -> currentState().book(e);
            case BookingEvent.ParticipantCanceled e -> currentState().cancelBooking(e.bookingId());
        };
    }

    public sealed interface Command {
        record MarkSlotAvailable(Participant participant) implements Command {
        }

        record UnmarkSlotAvailable(Participant participant) implements Command {
        }

        record BookReservation(
                String studentId, String aircraftId, String instructorId, String bookingId)
                implements Command {
        }
    }

    private static boolean isFutureSlot(String slotID){
        try{
            var parts = slotID.split("-");
            var slotTime = LocalDateTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])
                    Integer.parseInt(parts[3]), 0);
            return slotTime.isAfter(LocalDateTime.now());
        } catch (Exception e) {
            return false; // in the case of a malformed slot ID
        }
    }
}
