package marksto.data.integration.service;

import com.google.api.services.sheets.v4.model.NamedRange;
import com.google.api.services.sheets.v4.model.ValueRange;
import marksto.data.config.properties.SheetsProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Provides a set of convenient reactive operations over the standard
 * <em>Google Sheets API</em> and some handy authorization means.
 * <p>
 * All remote service responses are cached. Both cache configuration
 * and retry strategies might be tuned via the dedicated application
 * properties. The defaults are reasonable.
 * <p>
 * <b>IMPORTANT NOTICE:</b>
 * The service will require your to provide an OAuth 2.0 credentials
 * to be used with <em>Google Sheets API</em>, as well as a <em>test
 * spreadsheet ID</em> to check the established connection.
 *
 * @see SheetsProperties
 *
 * @author marksto
 * @since 14.02.2019
 */
public interface GoogleSheetsService {

    /**
     * Retrieves a <em>Google Spreadsheet</em> title.
     *
     * @param spreadsheetId the target spreadsheet ID
     */
    Mono<String> getTitleOf(String spreadsheetId);

    // -------------------------------------------------------------------------

    /**
     * Retrieves a list of named ranges defined in a <em>Google Spreadsheet</em>.
     *
     * @param spreadsheetId the target spreadsheet ID
     */
    Flux<NamedRange> getNamedRangesOf(String spreadsheetId);

    /**
     * Retrieves values of a single named range from a <em>Google Spreadsheet</em>.
     *
     * @param spreadsheetId the target spreadsheet ID
     */
    Mono<ValueRange> getRangeByName(String spreadsheetId, String range);

    /**
     * Retrieves values of a set of named range from a <em>Google Spreadsheet</em>.
     *
     * @param spreadsheetId the target spreadsheet ID
     */
    Flux<ValueRange> getRangesByName(String spreadsheetId, List<String> ranges);

    // -------------------------------------------------------------------------

    /**
     * Synchronizes the data (values of a set of named range) between the two
     * <em>Google Spreadsheets</em> entities.
     * <p>
     * <b>IMPLEMENTATION NOTE:</b>
     * This method always waits until both source and target spreadsheets are
     * ready for API calls (formulas are recalculated) and clears data ranges
     * from the old values before copying new ones — all to ensure consistency
     * of the transferred data and pass on on any data error promptly.
     *
     * @param fromSsId the source spreadsheet ID
     * @param toSsId the target spreadsheet ID
     * @param ranges a list of {@link NamedRange} names to transfer (copy-paste)
     *               from {@code fromSsId} to {@code toSsId}
     * @param awaitTillReady whether to await for the {@code toSsId} ready status
     */
    Mono<Void> copyRangesBetweenSpreadsheets(String fromSsId, String toSsId,
                                             List<String> ranges,
                                             boolean awaitTillReady);

}
