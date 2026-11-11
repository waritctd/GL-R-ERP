package th.co.glr.hr.pricingcosting;

import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/** The freight table has no band for a (origin, thickness, quantity). Still a plain 422 on the wire; the
 * type lets LandedCostCalculator tell a fillable gap from a corrupt table without matching message text. */
public class FreightBandNotFoundException extends ApiException {
    public FreightBandNotFoundException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
