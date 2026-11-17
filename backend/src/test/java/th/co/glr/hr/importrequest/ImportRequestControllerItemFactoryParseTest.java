package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/** Slice 6c fix round (review L2/B12): the coverage route's `itemFactory` pair cap. No DB. */
class ImportRequestControllerItemFactoryParseTest {

    private static List<String> pairs(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> i + ":" + (i + 1)).toList();
    }

    @Test
    void fiveHundredPairsAreRead() {
        assertThat(ImportRequestController.parseItemFactories(pairs(500))).hasSize(500);
    }

    @Test
    void fiveHundredAndOnePairsAreA400() {
        assertThatThrownBy(() -> ImportRequestController.parseItemFactories(pairs(501)))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
