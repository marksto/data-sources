package marksto.data.service.impl;

import com.google.common.base.Preconditions;
import marksto.data.config.properties.DataProvidersProperties;
import marksto.data.schemas.DataSource;
import marksto.data.integration.service.GoogleSheetsService;
import marksto.data.mapping.dto.TypedObjects;
import marksto.data.mapping.ValueRangesMapper;
import marksto.data.model.StaticType;
import marksto.data.service.DataMappingProvider;
import marksto.data.service.DataSourceMetadataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import static marksto.data.service.impl.DataSourceHelper.print;
import static marksto.common.util.LogUtils.*;

@Service
public class DataSourceMetadataProviderImpl extends AbstractDataRetrievalService implements DataSourceMetadataProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceMetadataProviderImpl.class);

    private static final String REMOTE_ERROR_MSG = "Failed to provide remote Metadata for '{}'";

    // -------------------------------------------------------------------------

    // NOTE: The domain type for the Data Source itself that holds metadata after the mapping.
    private static final StaticType<DataSource> DATA_SOURCE_TYPE_KEY = new StaticType<>("dataSources", DataSource.class);

    private final ValueRangesMapper valueRangesMapper;
    private final Scheduler mappingScheduler;

    public DataSourceMetadataProviderImpl(GoogleSheetsService sheetsService,
                                          DataMappingProvider dataMappingProvider,
                                          DataProvidersProperties dataProvidersProperties,
                                          ValueRangesMapper valueRangesMapper,
                                          @Qualifier("mappingScheduler") Scheduler mappingScheduler) {
        super(dataProvidersProperties, dataMappingProvider, sheetsService);
        this.valueRangesMapper = valueRangesMapper;
        this.mappingScheduler = mappingScheduler;
    }

    // -------------------------------------------------------------------------

    @Override
    public Mono<TypedObjects<DataSource>> provideMetadataFor(DataSource dataSource) {
        Preconditions.checkArgument(dataSource.getDataStructure() != null, "Invalid DataSource");
        lazyDebug(LOG, "Retrieving metadata for '{}'", () -> print(dataSource));

        final var retrievalRetriesNum = dataProvidersProperties.getRetrieveMetadataRetriesNum();
        final var retrieval1stBackoff = dataProvidersProperties.getRetrieveMetadata1stBackoff();

        return Mono.fromSupplier(() -> createDomainTypeContext(DATA_SOURCE_TYPE_KEY, dataSource))
                .flatMap(this::prepareMapperContext)
                // NOTE: First do all retries w/ backoff, only then process error.
                .retryBackoff(retrievalRetriesNum, retrieval1stBackoff)
                .doOnError(e -> lazyWarn(LOG, e, REMOTE_ERROR_MSG, () -> print(dataSource)))
                .publishOn(mappingScheduler)
                .map(valueRangesMapper::map);
    }

}
