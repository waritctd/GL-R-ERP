package th.co.glr.hr.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** What a rendered workbook says, for a test that searches a document for text it must, or must
 * not, print. */
public final class WorkbookTexts {
    private WorkbookTexts() {}

    /** The text of every text cell of {@code workbook} that is not blank, sheet by sheet and row by
     * row, exactly as stored. The workbook is closed before this returns. */
    public static List<String> of(byte[] workbook) {
        List<String> texts = new ArrayList<>();
        try (Workbook opened = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            for (Sheet sheet : opened) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isBlank()) {
                            texts.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return texts;
    }
}
