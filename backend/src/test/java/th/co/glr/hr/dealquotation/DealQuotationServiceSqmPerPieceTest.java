package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.CatalogRepository.CatalogSqmBasis;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Owner ruling 2026-09-12, "2) ไม่มีค่อยคำนวนเอง": {@code DealQuotationService#resolveSqmPerPiece}
 * must resolve ตร.ม./แผ่น from the CATALOGUE, never by guessing the unit of a free-text size
 * string. Pins the resolution order end to end, plus the regression the deleted heuristic used to
 * get wrong ("200x300" silently read as centimetres).
 *
 * <p>NO DATABASE: every dependency the resolution path can reach is a Mockito mock or a bare
 * no-op value. This works ONLY because {@link DealQuotationService#calculateLine} short-circuits
 * its own authz check for an EDIT_ROLES actor ({@code "sales"} here) before it would otherwise
 * touch {@code EmployeeAuthRepository} — see that method's own body — so this test never performs
 * a real JDBC call. {@code CatalogRepository} is mocked per test via {@link #resolve}, exactly the
 * pattern {@code CatalogControllerTest} already uses for the same class.
 */
class DealQuotationServiceSqmPerPieceTest {
    private static final UserPrincipal SALES = new UserPrincipal(
        1L, "sales@glr.co.th", "Sales", "sales", 1L, true, LocalDate.of(2026, 1, 1), false, 1L, false);

    private DealQuotationItemDto resolve(ItemInput input, CatalogRepository catalog) {
        DealQuotationService service = new DealQuotationService(
            mock(DealQuotationRepository.class), mock(TicketRepository.class),
            mock(CustomerRepository.class), mock(ContactRepository.class),
            mock(NotificationRepository.class), mock(NotificationEmailService.class),
            mock(QuotationRenderer.class), mock(EmployeeAuthRepository.class),
            mock(EmployeeSignatureRepository.class), catalog,
            "https://portal.test", "", "", "");
        return service.calculateLine(input, SALES);
    }

    /** PIECES mode, no wastage/box rounding -- the minimal shape that exercises
     * {@code resolveSqmPerPiece} without needing {@code sqmPerPiece} for anything else (unlike
     * AREA mode, PIECES mode tolerates a null piecesPerSqm). */
    private ItemInput pieceItem(Long catalogPriceId, BigDecimal ownSqmPerPiece, String sizeText) {
        return new ItemInput(null, catalogPriceId, null, null, null, null, null, sizeText,
            null, ownSqmPerPiece, WastageCalculator.QUANTITY_MODE_PIECES, null, 1,
            WastageCalculator.WASTAGE_MODE_NONE, null, null,
            new BigDecimal("100"), null, null, null, null, null);
    }

    /** AREA mode requires a resolved sqmPerPiece -- used only to prove the no-basis case FAILS
     * rather than silently pricing on nothing. */
    private ItemInput areaItem(Long catalogPriceId, BigDecimal ownSqmPerPiece, String sizeText) {
        return new ItemInput(null, catalogPriceId, null, null, null, null, null, sizeText,
            null, ownSqmPerPiece, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null,
            new BigDecimal("100"), null, null, null, null, null);
    }

    @Test
    void sqmPerPiece_itemsOwnValueWins_evenWhenTheCatalogWouldDisagree() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findSqmBasis(1L)).thenReturn(Optional.of(
            new CatalogSqmBasis(new BigDecimal("0.36"), "per_sqm", new BigDecimal("600"), new BigDecimal("600"))));

        DealQuotationItemDto item = resolve(pieceItem(1L, new BigDecimal("0.50"), null), catalog);

        assertThat(item.sqmPerPiece()).isEqualByComparingTo("0.50");
    }

    @Test
    void sqmPerPiece_catalogValuePreferredOverGeometry_whenTheyDisagree() {
        // A real catalog example: sqm_per_piece 0.135 vs geometry (300mm x 600mm = 0.18) --
        // geometry disagrees with the catalogue's own figure on 8.6% of the real catalog (up to
        // 14x on trims), so the catalogue's own sqm_per_piece must always win when present.
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findSqmBasis(2L)).thenReturn(Optional.of(
            new CatalogSqmBasis(new BigDecimal("0.135"), "per_sqm", new BigDecimal("300"), new BigDecimal("600"))));

        DealQuotationItemDto item = resolve(pieceItem(2L, null, null), catalog);

        assertThat(item.sqmPerPiece()).isEqualByComparingTo("0.135");
        assertThat(item.sqmPerPiece()).isNotEqualByComparingTo("0.180");
    }

    @Test
    void sqmPerPiece_geometryUsedOnlyWhenCatalogSqmPerPieceIsAbsent() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findSqmBasis(3L)).thenReturn(Optional.of(
            new CatalogSqmBasis(null, "per_sqm", new BigDecimal("200"), new BigDecimal("300"))));

        DealQuotationItemDto item = resolve(pieceItem(3L, null, null), catalog);

        // 200mm x 300mm / 1,000,000 = 0.06 sqm/piece.
        assertThat(item.sqmPerPiece()).isEqualByComparingTo("0.060000");
    }

    @Test
    void sqmPerPiece_neverGeometricOrCatalogForPerLinearMRows() {
        // V153: sqm_per_piece on a per_linear_m row is LINEAR METRES per piece, not area -- using
        // it (or computing geometry) as an area would be 14x off. Both a populated sqm_per_piece
        // AND populated width/height are present here to prove NEITHER is used.
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findSqmBasis(4L)).thenReturn(Optional.of(
            new CatalogSqmBasis(new BigDecimal("0.36"), "per_linear_m", new BigDecimal("100"), new BigDecimal("600"))));

        DealQuotationItemDto item = resolve(pieceItem(4L, null, null), catalog);

        assertThat(item.sqmPerPiece()).isNull();
    }

    @Test
    void sqmPerPiece_noBasisAtAll_failsRatherThanGuessing() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findSqmBasis(5L)).thenReturn(Optional.empty());

        // AREA mode requires a resolved sqmPerPiece to derive piecesBeforeWastage -- with no
        // basis at all, WastageCalculator itself refuses to price the row rather than the service
        // ever inventing a number.
        assertThatThrownBy(() -> resolve(areaItem(5L, null, null), catalog))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("sqmPerPiece is required");
    }

    /**
     * THE regression: the deleted heuristic read "200x300" as CENTIMETRES (neither dimension
     * &gt; 300), resolving 6.0 sqm/piece against the owner's own document for that real product
     * (0.061 sqm/piece, 16.39 pcs/sqm) -- wrong by ~98x. With no catalog link and no item-supplied
     * sqmPerPiece, the free-text size must now resolve to NOTHING -- never silently 6.0.
     *
     * <p>Mutation-checked: reintroducing a magnitude-based guess in {@code resolveSqmPerPiece}
     * (falling back to parsing {@code input.sizeText()} when the catalog has no basis) turns this
     * test red (it starts asserting {@code isNull()} against a resolved {@code 6.000000}); reverting
     * that reintroduction turns it green again. See the PR body for the before/after run.
     */
    @Test
    void sqmPerPiece_200x300_isNeverSilentlyReadAsCentimetres() {
        CatalogRepository catalog = mock(CatalogRepository.class);

        DealQuotationItemDto item = resolve(pieceItem(null, null, "200x300"), catalog);

        // Not resolved at all -- and specifically not the deleted heuristic's wrong answer, 6.0
        // (200cm x 300cm), which is what a magnitude-based cm/mm guess would have produced here.
        assertThat(item.sqmPerPiece()).isNull();
    }
}
