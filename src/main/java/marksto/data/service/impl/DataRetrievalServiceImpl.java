package marksto.data.service.impl;

import marksto.data.config.properties.DataProvidersProperties;
import marksto.data.schemas.DataSource;
import marksto.data.integration.service.GoogleSheetsService;
import marksto.data.mapping.dto.TypedObjects;
import marksto.data.mapping.ValueRangesMapper;
import marksto.data.model.DomainType;
import marksto.data.service.DataMappingProvider;
import marksto.data.service.DataRetrievalService;
import marksto.data.service.DataSourcesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Arrays;

import static marksto.data.schemas.DataMapping.TypeEntry;
import static marksto.data.service.impl.DataSourceHelper.print;
import static marksto.common.util.LogUtils.lazyDebug;
import static marksto.common.util.LogUtils.lazyWarn;

@Service
public class DataRetrievalServiceImpl extends AbstractDataRetrievalService implements DataRetrievalService {

    private static final Logger LOG = LoggerFactory.getLogger(DataRetrievalServiceImpl.class);

    private static final String REMOTE_ERROR_MSG = "Failed to retrieve remote data from '{}'";

    // -------------------------------------------------------------------------

    private final DataSourcesService dataSourcesService;

    private final ValueRangesMapper valueRangesMapper;
    private final Scheduler mappingScheduler;

    public DataRetrievalServiceImpl(GoogleSheetsService sheetsService,
                                    DataMappingProvider dataMappingProvider,
                                    DataProvidersProperties dataProvidersProperties,
                                    DataSourcesService dataSourcesService,
                                    ValueRangesMapper valueRangesMapper,
                                    @Qualifier("mappingScheduler") Scheduler mappingScheduler) {
        super(dataProvidersProperties, dataMappingProvider, sheetsService);
        this.dataSourcesService = dataSourcesService;
        this.valueRangesMapper = valueRangesMapper;
        this.mappingScheduler = mappingScheduler;
    }

    // -------------------------------------------------------------------------

    @Override
    public Flux<TypedObjects> getDataFrom(DomainType<?>... domainTypes) {
        lazyDebug(LOG, "Retrieving data for types '{}'", () -> Arrays.toString(domainTypes));

        final var retrievalRetriesNum = dataProvidersProperties.getRetrieveDataRetriesNum();
        final var retrieval1stBackoff = dataProvidersProperties.getRetrieveData1stBackoff();

        return Flux.fromArray(domainTypes)
                .flatMap(domainType -> Mono.zip(
                        Mono.just(domainType),
                        getDataSourceFor(domainType)
                ))
                .map(tuple -> createDomainTypeContext(tuple.getT1(), (DataSource) tuple.getT2()))
                .flatMap(ctx -> prepareMapperContext(ctx)
                        // NOTE: First do all retries w/ backoff, only then process error.
                        .retryBackoff(retrievalRetriesNum, retrieval1stBackoff)
                        .doOnError(e -> lazyWarn(LOG, e, REMOTE_ERROR_MSG, () -> print(ctx.getDataSource())))
                )
                .publishOn(mappingScheduler)
                .map(valueRangesMapper::map);
    }

    private Mono<DataSource> getDataSourceFor(DomainType<?> domainType) {
        TypeEntry typeEntry = (TypeEntry) getDataMapping().get(domainType.getName());
        return dataSourcesService.retrieveDataSource(typeEntry.getDataSource(), false);
    }

}
