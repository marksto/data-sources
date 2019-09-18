package marksto.data.service.impl;

import com.google.common.base.Preconditions;
import marksto.data.config.properties.DataProvidersProperties;
import marksto.data.schemas.DataSource;
import marksto.data.schemas.DataStructure;
import marksto.data.integration.service.GoogleSheetsService;
import marksto.data.mapping.NamedRangesMapper;
import marksto.data.service.DataStructureProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static marksto.common.util.LogUtils.*;

@Service
public class RemoteDataStructureProvider implements DataStructureProvider {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteDataStructureProvider.class);

    private static final String REMOTE_ERROR_MSG = "Failed to upload remote Data Structure for '{}'";

    // -------------------------------------------------------------------------

    private final DataProvidersProperties dataProvidersProperties;

    private final GoogleSheetsService sheetsService;

    private final NamedRangesMapper namedRangesMapper;

    public RemoteDataStructureProvider(DataProvidersProperties dataProvidersProperties,
                                       GoogleSheetsService sheetsService,
                                       NamedRangesMapper namedRangesMapper) {
        this.dataProvidersProperties = dataProvidersProperties;
        this.sheetsService = sheetsService;
        this.namedRangesMapper = namedRangesMapper;
    }

    // -------------------------------------------------------------------------

    @Override
    @SuppressWarnings("RedundantCast")
    public Mono<DataStructure> retrieveDataStructure(DataSource dataSource) {
        Preconditions.checkArgument(dataSource != null, "DataSource must not be null");
        Preconditions.checkArgument(dataSource.getSpreadsheet() != null, "Invalid DataSource");

        final String spreadsheetId = dataSource.getSpreadsheet().getId();
        lazyDebug(LOG, "Uploading remote Data Structure for '{}'", () -> toLogForm(spreadsheetId));

        final var retrievalRetriesNum = dataProvidersProperties.getRetrieveDataStructureRetriesNum();
        final var retrieval1stBackoff = dataProvidersProperties.getRetrieveDataStructure1stBackoff();

        // NOTE: No caching required on this level — the Google Sheets service should cache the results.
        return sheetsService.getNamedRangesOf(spreadsheetId)
                // NOTE: First do all retries w/ backoff, only then process error.
                .retryBackoff(retrievalRetriesNum, retrieval1stBackoff)
                .doOnError(e -> lazyWarn(LOG, e, REMOTE_ERROR_MSG, () -> toLogForm(spreadsheetId)))
                .collectList()
                .map(response -> (DataStructure) namedRangesMapper.map(response));
    }

}
