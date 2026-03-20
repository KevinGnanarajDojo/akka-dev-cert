package io.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.example.domain.BookingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// This class is responsible for consuming events from the booking
// slot entity and turning those into command calls on the
// participant slot entity
@Component(id = "booking-slot-consumer")
@Consume.FromEventSourcedEntity(BookingSlotEntity.class)
public class SlotToParticipantConsumer extends Consumer {

    private final ComponentClient client;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public SlotToParticipantConsumer(ComponentClient client) {
        this.client = client;
    }

    public Effect onEvent(BookingEvent event) {

        logger.info("[SPC] Revieved BookingEvent: {}", event);
        String key = participantSlotId(event);

        switch (event){
            case BookingEvent.ParticipantMarkedAvailable e -> {
                logger.info("[SPC] Marking participant {} as available for slot {}", e.participantId(), e.slotId());
                client.forEventSourcedEntity(key)
                        .method(ParticipantSlotEntity::markAvailable)
                        .invoke(new ParticipantSlotEntity.Commands.MarkAvailable(e.slotId(), e.participantId(), e.participantType()));
            }
            case BookingEvent.ParticipantUnmarkedAvailable e -> {
                logger.info("[SPC] Unmarking participant {} as available for slot {}", e.participantId(), e.slotId());
                client.forEventSourcedEntity(key)
                        .method(ParticipantSlotEntity::unmarkAvailable)
                        .invoke(new ParticipantSlotEntity.Commands.UnmarkAvailable(e.slotId(), e.participantId(), e.participantType()));
            }
            case BookingEvent.ParticipantBooked e -> {
                logger.info("[SPC] Booking participant {} for slot {}", e.participantId(), e.slotId());
                client.forEventSourcedEntity(key)
                        .method(ParticipantSlotEntity::book)
                        .invoke(new ParticipantSlotEntity.Commands.Book(e.slotId(), e.participantId(), e.participantType(), e.bookingId()));
            }
            case BookingEvent.ParticipantCanceled e -> {
                logger.info("[SPC] Cancelling booking of participant {} for slot {}", e.participantId(), e.slotId());
                client.forEventSourcedEntity(key)
                        .method(ParticipantSlotEntity::cancel)
                        .invoke(new ParticipantSlotEntity.Commands.Cancel(e.slotId(), e.participantId(), e.participantType(), e.bookingId()));
            }
        }

        return effects().done();
    }

    // Participant slots are keyed by a derived key made up of
    // {slotId}-{participantId}
    // We don't need the participant type here because the participant IDs
    // should always be unique/UUIDs
    private String participantSlotId(BookingEvent event) {
        return switch (event) {
            case BookingEvent.ParticipantBooked evt -> evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantUnmarkedAvailable evt ->
                evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantMarkedAvailable evt -> evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantCanceled evt -> evt.slotId() + "-" + evt.participantId();
        };
    }
}
