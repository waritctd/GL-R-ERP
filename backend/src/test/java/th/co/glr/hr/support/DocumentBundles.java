package th.co.glr.hr.support;

import static org.mockito.Mockito.mock;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DocumentBundle;

/** A {@link DocumentBundle} for hand-wired tests that never download a PDF: real repositories, a mock quotation service. */
public final class DocumentBundles {
    private DocumentBundles() {}

    public static DocumentBundle noDownload(NamedParameterJdbcTemplate jdbc) {
        return new DocumentBundle(new AttachmentRepository(jdbc), mock(DealQuotationService.class),
            new DepositNoticeRepository(jdbc));
    }
}
