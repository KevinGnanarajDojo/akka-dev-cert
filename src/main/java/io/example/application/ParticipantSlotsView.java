package io.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.annotations.Table;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.example.application.ParticipantSlotEntity.Event.Booked;
import io.example.application.ParticipantSlotEntity.Event.Canceled;
import io.example.application.ParticipantSlotEntity.Event.MarkedAvailable;
import io.example.application.ParticipantSlotEntity.Event.UnmarkedAvailable;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "view-participant-slots")
public class ParticipantSlotsView extends View {

    private static final Logger logger = LoggerFactory.getLogger(ParticipantSlotsView.class);

    @Table("participant_slot_view_updater")
    @Consume.FromEventSourcedEntity(ParticipantSlotEntity.class)
    public static class ParticipantSlotsViewUpdater extends TableUpdater<SlotRow> {

        public Effect<SlotRow> onEvent(ParticipantSlotEntity.Event event) {
           return switch (event){
               case MarkedAvailable e -> effects()
                       .updateRow(new SlotRow(e.slotId(), e.participantId(), e.participantType().name(), "", "AVAILABLE"));
               case UnmarkedAvailable e -> effects().deleteRow();
               case Booked e -> effects()
                       .updateRow(new SlotRow(e.slotId(), e.participantId(), e.participantType().name(), e.bookingId(), "BOOKED"));
               case Canceled e -> effects().deleteRow();
           };
        }
    }

    public record SlotRow(
            String slotId,
            String participantId,
            String participantType,
            String bookingId,
            String status) {
    }

    public record ParticipantStatusInput(String participantId, String status) {
    }

    public record SlotList(List<SlotRow> slots) {
    }

    @Query("SELECT * AS slots FROM participant_slot_view_updater WHERE participantId = :participantId")
    public QueryEffect<SlotList> getSlotsByParticipant(String participantId) {
        return queryResult();
    }

    @Query("SELECT * AS slots FROM participant_slot_view_updater WHERE participantId = :participantId AND status = :status")
    public QueryEffect<SlotList> getSlotsByParticipantAndStatus(ParticipantStatusInput input) {
        return queryResult();
    }
}
