package io.example.api;

import java.util.Collections;

import io.example.application.BookingSlotEntity;
import io.example.application.FlightConditionsAgent;
import io.example.application.ParticipantSlotsView;
import io.example.domain.Participant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import io.example.application.ParticipantSlotsView.SlotList;
import io.example.domain.Participant.ParticipantType;
import io.example.domain.Timeslot;

import static java.util.UUID.randomUUID;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/flight")
public class FlightEndpoint extends AbstractHttpEndpoint {
    private final Logger log = LoggerFactory.getLogger(FlightEndpoint.class);

    private final ComponentClient componentClient;

    public FlightEndpoint(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    // Creates a new booking. All three identified participants will
    // be considered booked for the given timeslot, if they are all
    // "available" at the time of booking.
    @Post("/bookings/{slotId}")
    public HttpResponse createBooking(String slotId, BookingRequest request) {
        log.info("Creating booking for slot {}: {}", slotId, request);

        var sessionID = randomUUID().toString();

        var report = componentClient.
                forAgent()
                .inSession(sessionID)
                .method(FlightConditionsAgent::query)
                .invoke(slotId);

        if (!report.meetsRequirements()){
            throw HttpException.badRequest("Flight conditions do not meet safety requirements for lesson slot: " + slotId);
        }

        // Else, Book the slot

        componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::bookSlot)
                .invoke(new BookingSlotEntity.Command.BookReservation(request.studentId, request.aircraftId, request.instructorId, request.bookingId));

        return HttpResponses.created();
    }

    // Cancels an existing booking. Note that both the slot
    // ID and the booking ID are required.
    @Delete("/bookings/{slotId}/{bookingId}")
    public HttpResponse cancelBooking(String slotId, String bookingId) {
        log.info("Canceling booking id {}", bookingId);

        componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::cancelBooking)
                .invoke(bookingId);

        return HttpResponses.ok();
    }

    // Retrieves all slots in which a given participant has the supplied status.
    // Used to retrieve bookings and slots in which the participant is available
    @Get("/slots/{participantId}/{status}")
    public SlotList slotsByStatus(String participantId, String status) {

        return componentClient.forView()
                .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                .invoke(new ParticipantSlotsView.ParticipantStatusInput(participantId, status));
    }

    // Returns the internal availability state for a given slot
    @Get("/availability/{slotId}")
    public Timeslot getSlot(String slotId) {

        return componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::getSlot)
                .invoke();
    }

    // Indicates that the supplied participant is available for booking
    // within the indicated time slot
    @Post("/availability/{slotId}")
    public HttpResponse markAvailable(String slotId, AvailabilityRequest request) {
        ParticipantType participantType;

        try {
            participantType = ParticipantType.valueOf(request.participantType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Bad participant type {}", request.participantType());
            throw HttpException.badRequest("invalid participant type");
        }

        log.info("Marking timeslot available for entity {}", slotId);

        componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::markSlotAvailable)
                .invoke(new BookingSlotEntity.Command.MarkSlotAvailable(
                        new Participant(request.participantId, participantType)));

        return HttpResponses.ok();
    }

    // Unmarks a slot as available for the given participant.
    @Delete("/availability/{slotId}")
    public HttpResponse unmarkAvailable(String slotId, AvailabilityRequest request) {
        ParticipantType participantType;
        try {
            participantType = ParticipantType.valueOf(request.participantType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Bad participant type {}", request.participantType());
            throw HttpException.badRequest("invalid participant type");
        }

        log.info("Marking timeslot unavailable for entity {}", slotId);

        componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::unmarkSlotAvailable)
                .invoke(new BookingSlotEntity.Command.UnmarkSlotAvailable(
                        new Participant(request.participantId, participantType)
                ));


        return HttpResponses.ok();
    }

    // Public API representation of a booking request
    public record BookingRequest(
            String studentId, String aircraftId, String instructorId, String bookingId) {
    }

    // Public API representation of an availability mark/unmark request
    public record AvailabilityRequest(String participantId, String participantType) {
    }
}
