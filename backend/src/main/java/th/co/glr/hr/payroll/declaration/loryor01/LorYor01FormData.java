package th.co.glr.hr.payroll.declaration.loryor01;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything the official ล.ย.01 form can carry, in the order the form prints it.
 *
 * <p>This record is deliberately shaped like the paper, not like our database: one component per
 * printed slot, named after ข้อ N. That is what lets a reader hold the PDF next to this file and
 * check them off one by one, which is the whole point of the feature — see
 * {@link LorYor01Renderer} for the field-name mapping and the constraints the form itself imposes.
 *
 * <p>Nulls mean "left blank", which is a real answer on a paper form and is NOT the same as zero:
 * a blank amount box and a box containing ฿0 look different and mean different things to the
 * Revenue Department. The renderer prints nothing for null and "0" for BigDecimal.ZERO.
 */
public record LorYor01FormData(
        // ---- header block -------------------------------------------------------------------
        /** ชื่อหน่วยงานผู้มีหน้าที่หักภาษี ณ ที่จ่าย — the employer, not the employee. */
        String withholdingAgentName,
        /** วัน/เดือน/ปี ที่แจ้งรายการ. Printed top-right; the form has no AcroForm field for it. */
        LocalDate declaredOn,
        /** เลขประจำตัวผู้เสียภาษีอากร — 13 digits, laid out 1-4-5-2-1 across a 17-cell comb. */
        String taxpayerId,
        /** ผู้มีเงินได้ชื่อ. */
        String firstName,
        /** ชื่อสกุล. */
        String lastName,
        LorYor01Address address,

        // ---- ข้อ 1 สถานภาพ / สถานภาพการสมรส --------------------------------------------------
        MaritalState maritalState,
        SpousalStatus spousalStatus,

        // ---- ข้อ 2 สถานะการมีเงินได้ของคู่สมรส -------------------------------------------------
        /** TRUE = มีเงินได้, FALSE = ไม่มีเงินได้, null = neither box ticked. */
        Boolean spouseHasIncome,

        // ---- ข้อ 3 บุตร ----------------------------------------------------------------------
        /** จำนวนบุตรรวม. The form's own field caps this at a single digit. */
        Integer childrenTotal,
        /** บุตร คนละ 30,000 บาท — มีสิทธินำมาหักลดหย่อนจำนวน ___ คน. */
        Integer childStandardCount,
        BigDecimal childStandardAmount,
        /** บุตรตั้งแต่คนที่สองที่เกิดในหรือหลัง พ.ศ. 2561 คนละ 60,000 บาท. */
        Integer childExtraCount,
        BigDecimal childExtraAmount,

        // ---- ข้อ 4 ค่าอุปการะเลี้ยงดูบิดามารดา -------------------------------------------------
        /**
         * บิดา/มารดา ของผู้มีเงินได้. Independent, not exclusive: the form prints
         * "หักได้คนละ 30,000 บาท", so supporting both parents ticks both boxes and doubles the
         * amount. (The PDF miswires these two as one radio group — see {@link LorYor01Renderer}.)
         */
        boolean ownFatherSupported,
        boolean ownMotherSupported,
        BigDecimal ownParentCareAmount,
        /** บิดา/มารดา ของคู่สมรสที่ไม่มีเงินได้ — same independence as above. */
        boolean spouseFatherSupported,
        boolean spouseMotherSupported,
        BigDecimal spouseParentCareAmount,

        // ---- ข้อ 5 ค่าอุปการะเลี้ยงดูคนพิการหรือคนทุพพลภาพ --------------------------------------
        Integer disabledCareCount,
        BigDecimal disabledCareAmount,

        // ---- ข้อ 6 เบี้ยประกันสุขภาพบิดามารดา --------------------------------------------------
        /** Four independent ticks; the form prints "รวมกันแล้วไม่เกิน 15,000 บาท" over one amount box. */
        boolean ownFatherHealthInsured,
        boolean ownMotherHealthInsured,
        boolean spouseFatherHealthInsured,
        boolean spouseMotherHealthInsured,
        BigDecimal parentHealthInsuranceAmount,

        // ---- ข้อ 7–9 --------------------------------------------------------------------------
        /** ข้อ 7 เบี้ยประกันชีวิตที่จ่ายภายในปีภาษี. */
        BigDecimal lifeInsuranceAmount,
        /** ข้อ 8 เบี้ยประกันสุขภาพที่จ่ายภายในปีภาษี. */
        BigDecimal healthInsuranceAmount,
        /** ข้อ 9 กองทุนสำรองเลี้ยงชีพ / กอช. / กบข. / กองทุนสงเคราะห์ครูโรงเรียนเอกชน. */
        BigDecimal providentFundAmount,

        // ---- ข้อ 10–11 กองทุนรวม ---------------------------------------------------------------
        /** ข้อ 10 ค่าซื้อหน่วยลงทุนในกองทุนรวมเพื่อการเลี้ยงชีพ (RMF). */
        BigDecimal rmfAmount,
        String rmfSellerName,
        /**
         * ข้อ 11 ค่าซื้อหน่วยลงทุนในกองทุนรวมหุ้นระยะยาว (LTF). Present because the government's form
         * prints it; LTF purchases stopped being deductible after tax year 2019, so in practice this
         * stays null and the line prints blank.
         */
        BigDecimal ltfAmount,
        String ltfSellerName,

        // ---- ข้อ 12–15 ------------------------------------------------------------------------
        /** ข้อ 12 ดอกเบี้ยเงินกู้ยืมเพื่อซื้อ เช่าซื้อ หรือสร้างอาคารที่อยู่อาศัย. */
        BigDecimal homeLoanInterestAmount,
        /** ข้อ 13 เงินสมทบกองทุนประกันสังคมภายในปีภาษี. */
        BigDecimal socialSecurityAmount,
        /** ข้อ 14 เงินบริจาคสนับสนุนการศึกษา. */
        BigDecimal educationDonationAmount,
        /** ข้อ 15 เงินบริจาคอื่น ๆ — the (ระบุ) free-text and its amount. */
        String otherDonationNote,
        BigDecimal otherDonationAmount) {

    /** ที่อยู่ — the form's own 13 slots, in printed order. Any of them may be blank. */
    public record LorYor01Address(
            String building,      // อาคาร
            String roomNo,        // ห้องเลขที่
            String floor,         // ชั้นที่
            String village,       // หมู่บ้าน
            String houseNo,       // เลขที่
            String moo,           // หมู่ที่
            String soi,           // ตรอก/ซอย
            String junction,      // แยก
            String road,          // ถนน
            String subDistrict,   // ตำบล/แขวง
            String district,      // อำเภอ/เขต
            String province,      // จังหวัด
            String postalCode) {  // รหัสไปรษณีย์ — 5 digits into a 5-cell comb

        public static LorYor01Address empty() {
            return new LorYor01Address(null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }
    }

    /** ข้อ 1 สถานภาพ — the left-hand group. Mutually exclusive, as the form intends. */
    public enum MaritalState {
        SINGLE,            // โสด
        WIDOWED,           // หม้าย
        MARRIED,           // สมรส
        DIED_DURING_YEAR   // ตายระหว่างปีภาษี
    }

    /** ข้อ 1 สถานภาพการสมรส — the right-hand group. Mutually exclusive. */
    public enum SpousalStatus {
        MARRIED_WHOLE_YEAR,     // สมรสและอยู่ร่วมกันตลอดปีภาษี
        MARRIED_DURING_YEAR,    // สมรสระหว่างปีภาษี
        DIVORCED_DURING_YEAR,   // หย่าระหว่างปีภาษี
        DIED_DURING_YEAR        // ตายระหว่างปีภาษี
    }
}
