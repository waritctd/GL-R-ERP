package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Wire shapes of /api/shipment-rounds. Deliberately carries no price field of any kind. */
public final class ShipmentRoundDtos {
    private ShipmentRoundDtos() {}

    /** The shipment facts. On a PUT every field is written as sent (transportMode keeps its value when null); on an
     * advance only the non-null ones are. {@code vessel} is the vessel, flight or truck plate by {@code transportMode}. */
    public record ShipmentFacts(String label, String transportMode, String vessel, String voyage, String forwarder,
                                String containerNos, String port, LocalDate goodsReadyOn, LocalDate etdPlannedOn,
                                LocalDate etdActualOn, LocalDate etaOn, LocalDate arrivedOn,
                                LocalDate customsReleasedOn, String grNumber, Boolean completePerInvoice,
                                String completeNote, String note) {}

    /** A document of a round: a number, a file, or both. */
    public record ShipmentDocumentDto(long id, String kind, String docNo, LocalDate docDate, String note,
                                      String fileName, Long fileSize, String mimeType, Instant createdAt) {}

    /** One quantity of one IR line on a round. {@code lotNo} of {@code lotCount} counts the line's lots, plus one for
     * quantity still on no round. */
    public record ShipmentLotDto(long lotId, long importRequestId, String docNumber, long ticketId, String ticketCode,
                         String customerName, String projectName, long itemId, String code, String brand,
                         String model, String size, String unit, BigDecimal qty, BigDecimal itemQty,
                         int lotNo, int lotCount, BigDecimal remainingQty) {}

    public record ShipmentRoundDto(long id, Long factoryId, String factoryName, String source, String importStep,
                           LocalDate contactedOn, LocalDate orderedOn, LocalDate pickedUpOn, LocalDate inTransitOn,
                           LocalDate awaitingCustomsOn, LocalDate receivedOn, String stepByName, String stepNote,
                           ShipmentFacts facts, List<ShipmentLotDto> lots, List<ShipmentDocumentDto> documents, Instant undoUntil,
                           Long undoById, String undoFromStep) {}

    /** An ISSUED IR with quantity on no round. */
    public record ShipmentWaitingLine(String code, String brand, String model, String size, String unit,
                              BigDecimal remainingQty) {}

    public record ShipmentWaitingDto(long importRequestId, String docNumber, Long factoryId, String factoryName,
                             long ticketId, String ticketCode, String customerName, String projectName,
                             List<ShipmentWaitingLine> lines) {}

    public record ShipmentRoundsResponse(List<ShipmentRoundDto> rounds, List<ShipmentWaitingDto> waiting) {}

    /** A document number typed on the advance sheet (kind + number). */
    public record DocumentInput(String kind, String docNo) {}

    public record AdvanceShipmentRoundRequest(String targetStep, LocalDate eventDate, String expectedStep, String note,
                                              ShipmentFacts facts, List<DocumentInput> documents) {}

    /** Facts as sent, plus corrections of the real date of steps the round has passed (step code -> date). */
    public record UpdateShipmentRoundRequest(ShipmentFacts facts, java.util.Map<String, LocalDate> stepDates) {}

    public record DocumentRequest(String kind, String docNo, LocalDate docDate, String note) {}

    public record UndoShipmentRoundRequest(String expectedStep) {}
}
