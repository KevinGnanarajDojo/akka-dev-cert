package io.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.example.domain.Participant.ParticipantType;

@Component(id = "participant-slot")
public class ParticipantSlotEntity
                extends EventSourcedEntity<ParticipantSlotEntity.State, ParticipantSlotEntity.Event> {

        public Effect<Done> unmarkAvailable(ParticipantSlotEntity.Commands.UnmarkAvailable unmark) {
                // Supply your own implementation
                return effects()
                        .persist(new Event.UnmarkedAvailable(unmark.slotId(), unmark.participantId(), unmark.participantType()))
                        .thenReply(__ -> Done.done());
        }

        public Effect<Done> markAvailable(ParticipantSlotEntity.Commands.MarkAvailable mark) {
                // Supply your own implementation
                return effects()
                        .persist(new Event.MarkedAvailable(mark.slotId(), mark.participantId(), mark.participantType()))
                        .thenReply(__ -> Done.done());
        }

        public Effect<Done> book(ParticipantSlotEntity.Commands.Book book) {
                // Supply your own implementation
                return effects()
                        .persist(new Event.Booked(book.slotId(), book.participantId(), book.participantType(), book.bookingId()))
                        .thenReply(__ -> Done.done());
        }

        public Effect<Done> cancel(ParticipantSlotEntity.Commands.Cancel cancel) {
                // Supply your own implementation
                return effects()
                        .persist(new Event.Canceled(cancel.slotId(), cancel.participantId(), cancel.participantType(), cancel.bookingId()))
                        .thenReply(__ -> Done.done());
        }

        record State(
                        String slotId, String participantId, ParticipantType participantType, String status) {
        }

        public sealed interface Commands {
                record MarkAvailable(String slotId, String participantId, ParticipantType participantType)
                                implements Commands {
                }

                record UnmarkAvailable(String slotId, String participantId, ParticipantType participantType)
                                implements Commands {
                }

                record Book(
                                String slotId, String participantId, ParticipantType participantType, String bookingId)
                                implements Commands {
                }

                record Cancel(
                                String slotId, String participantId, ParticipantType participantType, String bookingId)
                                implements Commands {
                }
        }

        public sealed interface Event {
                @TypeName("marked-available")
                record MarkedAvailable(String slotId, String participantId, ParticipantType participantType)
                                implements Event {
                }

                @TypeName("unmarked-available")
                record UnmarkedAvailable(String slotId, String participantId, ParticipantType participantType)
                                implements Event {
                }

                @TypeName("participant-booked")
                record Booked(
                                String slotId, String participantId, ParticipantType participantType, String bookingId)
                                implements Event {
                }

                @TypeName("participant-canceled")
                record Canceled(
                                String slotId, String participantId, ParticipantType participantType, String bookingId)
                                implements Event {
                }
        }

        @Override
        public State applyEvent(ParticipantSlotEntity.Event event) {
                // Supply your own implementation
                return switch(event){
                        case Event.MarkedAvailable e -> new State(e.slotId(), e.participantId(), e.participantType(), "available");
                        case Event.UnmarkedAvailable e -> new State(e.slotId(), e.participantId(), e.participantType(), "unavailable");
                        case Event.Booked e -> new State(e.slotId(), e.participantId(), e.participantType(), "booked");
                        case Event.Canceled e -> new State(e.slotId(), e.participantId(), e.participantType(), "canceled");
                };
        }
}
