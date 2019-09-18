package marksto.data.service.impl;

import com.google.common.base.Preconditions;
import marksto.data.schemas.DataSource;
import marksto.data.integration.service.GoogleSheetsService;
import marksto.data.mapping.dto.TypedObjects;
import marksto.data.service.DataSourceInitializer;
import marksto.data.service.DataSourceMetadataProvider;
import marksto.data.service.DataStructureProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static marksto.common.util.LogUtils.*;
import static marksto.common.util.ReactivePreconditions.checkArgument;
import static marksto.common.util.ReactivePreconditions.checkArgumentNotNull;
import static marksto.data.service.impl.DataSourceHelper.*;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Service
@SuppressWarnings("Convert2MethodRef") // otherwise, Manifold will fail with class cast errors
public class RemoteDataSourceInitializer implements DataSourceInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteDataSourceInitializer.class);

    // Error Messages
    private static final String METADATA_PROVISION_ERROR = "Data Source metadata provision error";
    private static final String NO_SINGLE_METADATA_ERROR = "There must be a single Data Source metadata object returned";
    private static final String NO_DATA_STRUCTURE_ERROR  = "Data Source lacks its Data Structure";

    // -------------------------------------------------------------------------

    private final GoogleSheetsService sheetsService;
    private final DataStructureProvider dataStructureProvider;
    private final DataSourceMetadataProvider dataSourceMetadataProvider;

    public RemoteDataSourceInitializer(GoogleSheetsService sheetsService,
                                       DataStructureProvider dataStructureProvider,
                                       DataSourceMetadataProvider dataSourceMetadataProvider) {
        this.sheetsService = sheetsService;
        this.dataStructureProvider = dataStructureProvider;
        this.dataSourceMetadataProvider = dataSourceMetadataProvider;
    }

    // -------------------------------------------------------------------------

    @Override
    public Mono<DataSource> initDataSourceFor(String spreadsheetId) {
        Preconditions.checkArgument(isNotBlank(spreadsheetId), "Invalid ID provided");
        lazyInfo(LOG, "Initializing a Data Source for '{}'", () -> toLogForm(spreadsheetId));

        return createEmptyDataSource(spreadsheetId)
                .flatMap(dataSource -> provideDataSourceWithDataStructure(dataSource))
                .flatMap(dataSource -> provideDataSourceWithMetadata(dataSource))
                .flatMap(dataSource -> finalizeDataSourceInit(dataSource));
    }

    private Mono<DataSource> createEmptyDataSource(final String spreadsheetId) {
        return Mono.fromSupplier(() -> DataSource.builder()
                .withSpreadsheet(DataSource.spreadsheet.builder().withId(spreadsheetId).build())
                .withState(DataSource.state.empty)
                .build());
    }

    // -------------------------------------------------------------------------

    private Mono<DataSource> provideDataSourceWithDataStructure(DataSource consumer) {
        return Mono.just(consumer)
                .zipWhen(dataSource -> dataStructureProvider.retrieveDataStructure(dataSource),
                        (dataSource, dataStructure) -> {
                            dataSource.setDataStructure(dataStructure);
                            return dataSource;
                        })
                .onErrorReturn(consumer);
    }

    // -------------------------------------------------------------------------

    private Mono<DataSource> provideDataSourceWithMetadata(DataSource consumer) {
        lazyDebug(LOG, "Data Source Metadata requested for '{}'", () -> print(consumer));

        return dataSourceMetadataProvider.provideMetadataFor(consumer)
                .flatMap(mapped -> checkAndCopyWithMetadata(consumer, mapped)
                        .doOnError(t -> LOG.error(METADATA_PROVISION_ERROR, t)))
                //.doOnSuccess(dataSource -> dataSource.setState(DataSource.state.init))
                .onErrorReturn(consumer);
    }

    private Mono<DataSource> checkAndCopyWithMetadata(DataSource dataSource, TypedObjects<DataSource> mapped) {
        return checkArgument(mapped.getObjectsOfType(), dsl -> dsl.size() == 1, NO_SINGLE_METADATA_ERROR)
                .map(dsl -> copyWithMetadata(dataSource, dsl.get(0)));
    }

    // -------------------------------------------------------------------------

    private Mono<DataSource> finalizeDataSourceInit(DataSource dataSource) {
        lazyDebug(LOG, "Finalizing the initialization of '{}'", () -> print(dataSource));

        return Mono.when(
                postProcessDataStructureOf(dataSource),
                provideWithSpreadsheetTitle(dataSource))
                .then(Mono.fromSupplier(() -> {
                    DataSource finalVersion = dataSource.copy();
                    finalVersion.setState(DataSource.state.ready);
                    return finalVersion;
                }))
                .onErrorReturn(dataSource); // may not have been fully initialized at this point
    }

    private Mono<Void> postProcessDataStructureOf(DataSource dataSource) {
        lazyTrace(LOG, "Post-processing the DataStructure of '{}'", () -> print(dataSource));

        return checkArgumentNotNull(dataSource.getDataStructure(), NO_DATA_STRUCTURE_ERROR)
                .flatMap(ds -> {
                    Mono<?> cleanUpDependentRanges = Mono.fromRunnable(() -> cleanFromDependentRanges(dataSource));
                    Mono<?> removeMetadata = Mono.fromRunnable(() -> removeMetadata(dataSource));
                    return cleanUpDependentRanges.and(removeMetadata);
                })
                .doOnError(e -> lazyError(LOG, e,
                        "Error while post-processing Data Structure of '{}'", () -> print(dataSource)));
    }

    private Mono<String> provideWithSpreadsheetTitle(DataSource dataSource) {
        lazyTrace(LOG, "Providing the spreadsheet title for '{}'", () -> print(dataSource));

        return sheetsService.getTitleOf(dataSource.getSpreadsheet().getId())
                .doOnError(e -> LOG.warn("Failed to provide Data Source with Sheet's title", e))
                .doOnSuccess(spreadsheetTitle -> dataSource.getSpreadsheet().setTitle(spreadsheetTitle));
    }

}
