package th.co.glr.hr.dealquotation;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;

/**
 * The {@code hr.file_attachment} rows that hold a quotation's เอกสารแนบ ({@code domain =
 * 'deal_quotation'}, {@code owner_id} = the quotation). Bytes go through {@link
 * FileAttachmentBlobRepository}, as they do for leave, tax-allowance and factory-quote files.
 *
 * <p>No authorization here. {@link DealQuotationAttachmentService} decides before it calls.
 *
 * <p>Phase 1 placeholder. It has no statement yet.
 */
@Repository
public class DealQuotationAttachmentRepository {
    /** {@code hr.file_attachment.domain} for these rows. Every statement here states it, so an id
     * that belongs to another domain's file is never found through this class. */
    static final String DOMAIN = "deal_quotation";

    private final NamedParameterJdbcTemplate jdbc;
    private final FileAttachmentBlobRepository blobs;

    public DealQuotationAttachmentRepository(NamedParameterJdbcTemplate jdbc, FileAttachmentBlobRepository blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }
}
