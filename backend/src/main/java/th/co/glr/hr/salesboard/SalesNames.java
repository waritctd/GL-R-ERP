package th.co.glr.hr.salesboard;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The two folds the sales board compares names and document numbers with.
 *
 * <p>⚠️ CONVERGE AT MERGE: {@code salesboard/receipts/ReceiptMatcher#normaliseCustomer} / {@code #normaliseDoc} on the
 * sibling cash branch (slice 3) implement exactly these rules. This branch does not contain ReceiptMatcher, so the
 * rules are restated here verbatim; when the stack merges, ReceiptMatcher must delegate to this class (or the
 * reverse) so the pipeline import and the receipt matcher can never fold a customer or a QT number differently.
 */
public final class SalesNames {

    // A revision suffix is short ("-2"); a 3+ digit tail belongs to the number itself ("QT-2026-0201") and is never
    // stripped — stripping it would make every QT-2026-* number look alike.
    private static final Pattern VERSION_SUFFIX = Pattern.compile("-\\d{1,2}$");
    private static final Pattern NON_DOC = Pattern.compile("[\\s]");
    // Company-form words dropped before comparing names. Thai ones as substrings (Thai has no spaces between words),
    // longest first so "(มหาชน)" goes before "มหาชน"; Latin ones only as whole words, so "inc" never eats the middle
    // of "princess".
    private static final List<String> THAI_COMPANY_WORDS = List.of(
        "(มหาชน)", "มหาชน", "ห้างหุ้นส่วนจำกัด", "ห้างหุ้นส่วน", "บริษัท", "บจก.", "บจก", "บมจ.", "บมจ", "จำกัด",
        "หจก.", "หจก");
    private static final Pattern LATIN_COMPANY_WORDS = Pattern.compile(
        "(?<![a-z0-9])(co|ltd|limited|company|public|plc|inc|corp|corporation)(?![a-z0-9])");

    /** Trim, lower-case, NFC, drop company-form words (บริษัท / จำกัด / (มหาชน) / บจก. / Co.,Ltd. …), drop punctuation
     * left behind, collapse spaces. "" for a blank name. */
    public static String normaliseCustomer(String name) {
        if (name == null) {
            return "";
        }
        String s = " " + Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ").trim() + " ";
        for (String word : THAI_COMPANY_WORDS) {
            s = s.replace(word, " ");
        }
        s = s.replaceAll("[.,()&]", " ");
        s = LATIN_COMPANY_WORDS.matcher(s).replaceAll(" ");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Upper-case, no spaces, no trailing "-n" revision. Null for a blank number. */
    public static String normaliseDoc(String value) {
        if (value == null) {
            return null;
        }
        String s = NON_DOC.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        s = VERSION_SUFFIX.matcher(s).replaceAll("");
        return s.isEmpty() ? null : s;
    }

    private SalesNames() {}
}
