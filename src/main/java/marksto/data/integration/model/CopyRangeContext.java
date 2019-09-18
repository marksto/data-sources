package marksto.data.integration.model;

import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.ValueRange;
import marksto.data.integration.service.GoogleSheetsService;

/**
 * A context-holding DTO for the <em>Google Spreadsheets</em> data transfer operation.
 *
 * @see GoogleSheetsService#copyRangesBetweenSpreadsheets
 *
 * @author marksto
 */
public class CopyRangeContext {

    /**
     * Defines <i>when</i> to fire this particular {@link Request},
     * before the main copy-paste operation request ({@code PRE})
     * or after the one ({@code POST}). The {@code NONE} stand for
     * a "no-op" request and gives more control for conditional
     * auxiliary requests execution.
     */
    public enum UpdateType { PRE, POST, NONE }

    private final ValueRange valueRange;
    private final Request updateRequest;
    private final UpdateType updateType;

    public CopyRangeContext(ValueRange valueRange, Request updateRequest, UpdateType updateType) {
        this.valueRange = valueRange;
        this.updateRequest = updateRequest;
        this.updateType = updateType;
    }

    public ValueRange getValueRange() {
        return valueRange;
    }

    public Request getUpdateRequest() {
        return updateRequest;
    }

    public UpdateType getUpdateType() {
        return updateType;
    }

}
