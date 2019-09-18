package marksto.data.integration.service.impl;

import com.google.api.services.sheets.v4.model.ValueRange;
import marksto.data.config.properties.SheetsProperties;
import marksto.data.integration.service.GoogleSheetsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static marksto.common.util.LogUtils.*;
import static marksto.common.util.LogUtils.toLogForm;
import static marksto.common.util.ReactivePreconditions.checkState;
import static org.apache.commons.lang3.StringUtils.equalsIgnoreCase;

/**
 * An utility that checks the <em>Google Spreadsheet</em> readiness status,
 * i.e. if the one is ready for incoming API requests or it is still in an
 * inconsistent state (after the previous data operations).
 *
 * @author marksto
 * @since 08.05.2019
 */
class ApiStatusChecker {

    private static final Logger LOG = LoggerFactory.getLogger(ApiStatusChecker.class);

    private static final String FAILED_STATUS_CHECK_MSG = "Failed to check ready status of '{}'";
    private static final String READY_STATUS_MSG = "Spreadsheet '{}' ready status is '{}'";

    // -------------------------------------------------------------------------

    private static final String API_READY_STATUS_RANGE = "spreadsheet_status_api_ready";

    enum ApiReadinessStatus {

        READY("TRUE"),
        NOT_READY("FALSE");

        private final String strVal;

        ApiReadinessStatus(String strVal) {
            this.strVal = strVal;
        }

        public String getStrVal() {
            return strVal;
        }

        @Override
        public String toString() {
            return strVal;
        }

        static Optional<ApiReadinessStatus> from(String strVal) {
            return Stream.of(ApiReadinessStatus.values())
                    .filter(status -> equalsIgnoreCase(status.strVal, strVal))
                    .findFirst();
        }
    }

    // -------------------------------------------------------------------------

    private final GoogleSheetsService sheetsService;

    private final UnaryOperator<Mono<ApiReadinessStatus>> addRetryWithBackoffTransformer;

    ApiStatusChecker(SheetsProperties sheetsProperties,
                     GoogleSheetsService sheetsService) {
        this.sheetsService = sheetsService;

        var apiCheckNumOfBackoffs = sheetsProperties.getApiCheckRetriesNum();
        var apiCheck1stBackoff = sheetsProperties.getApiCheck1stBackoff();
        var apiCheckMaxBackoff = sheetsProperties.getApiCheckMaxBackoff();
        addRetryWithBackoffTransformer = mono ->
                mono.retryBackoff(apiCheckNumOfBackoffs, apiCheck1stBackoff, apiCheckMaxBackoff);
    }

    // -------------------------------------------------------------------------

    Mono<Void> awaitSpreadsheetReadyStatus(String spreadsheetId) {
        lazyTrace(LOG, "Checking the API ready status of '{}'", () -> toLogForm(spreadsheetId));

        return retrieveStatusOf(spreadsheetId)
                .flatMap(this::checkForReadiness) // error here forces retry
                .transform(addRetryWithBackoffTransformer)
                .doOnError(e -> lazyWarn(LOG, e, FAILED_STATUS_CHECK_MSG, () -> toLogForm(spreadsheetId)))
                .then();
    }

    // -------------------------------------------------------------------------

    private Mono<ApiReadinessStatus> retrieveStatusOf(String spreadsheetId) {
        return sheetsService.getRangeByName(spreadsheetId, API_READY_STATUS_RANGE)
                .map(this::mapToStatusString)
                .doOnNext(status -> lazyTrace(LOG, READY_STATUS_MSG,
                        () -> toLogForm(spreadsheetId), status::getStrVal));
    }

    private ApiReadinessStatus mapToStatusString(ValueRange valueRange) {
        return valueRange.getValues().stream()
                .filter(rangeValues -> !rangeValues.isEmpty())
                .map(rangeValues -> rangeValues.get(0).toString())
                .map(strVal -> ApiReadinessStatus.from(strVal)
                        .orElse(ApiReadinessStatus.NOT_READY))
                .findFirst()
                .orElse(ApiReadinessStatus.NOT_READY);
    }

    private Mono<ApiReadinessStatus> checkForReadiness(ApiReadinessStatus status) {
        return checkState(status,
                Predicate.isEqual(ApiReadinessStatus.READY),
                "API respond with a status \"NOT READY\"");
    }

}
