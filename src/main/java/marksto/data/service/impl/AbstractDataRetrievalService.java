package marksto.data.service.impl;

import marksto.data.config.properties.DataProvidersProperties;
import marksto.data.schemas.DataMapping;
import marksto.data.schemas.DataSource;
import marksto.data.integration.service.GoogleSheetsService;
import marksto.data.mapping.ValueRangesMapper;
import marksto.data.model.DomainType;
import marksto.data.service.DataMappingProvider;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static marksto.data.schemas.DataStructure.DataRangesGroup;
import static marksto.data.mapping.ValueRangesMapper.Context;

class AbstractDataRetrievalService {

    static final class DomainTypeContext<T> {

        private final DomainType<T> domainType;
        private final DataSource dataSource;
        private final List<String> ranges;

        private DomainTypeContext(DomainType<T> domainType,
                          DataSource dataSource,
                          List<String> ranges) {
            this.domainType = domainType;
            this.dataSource = dataSource;
            this.ranges = ranges;
        }

        DomainType<T> getDomainType() {
            return domainType;
        }

        DataSource getDataSource() {
            return dataSource;
        }

        List<String> getRanges() {
            return ranges;
        }
    }

    // -------------------------------------------------------------------------

    protected final DataProvidersProperties dataProvidersProperties;
    private final DataMappingProvider dataMappingProvider;
    private final GoogleSheetsService sheetsService;

    AbstractDataRetrievalService(DataProvidersProperties dataProvidersProperties,
                                 DataMappingProvider dataMappingProvider,
                                 GoogleSheetsService sheetsService) {
        this.dataProvidersProperties = dataProvidersProperties;
        this.dataMappingProvider = dataMappingProvider;
        this.sheetsService = sheetsService;
    }

    DataMapping getDataMapping() {
        return dataMappingProvider.retrieveDataMapping();
    }

    <T> DomainTypeContext<T> createDomainTypeContext(DomainType<T> domainType, DataSource dataSource) {
        return new DomainTypeContext<>(domainType, dataSource, getRanges(domainType, dataSource));
    }

    private List<String> getRanges(DomainType<?> domainType, DataSource dataSource) {
        return toRangeGroupsNamesStream(domainType.getName())
                .map(groupName -> (DataRangesGroup) dataSource.getDataStructure().get(groupName))
                .flatMap(dataRangesGroup -> dataRangesGroup.getList().stream())
                .collect(toList());
    }

    private Stream<String> toRangeGroupsNamesStream(String domainTypeName) {
        return Stream.of((DataMapping.TypeEntry) getDataMapping().get(domainTypeName))
            .flatMap(typeEntry -> typeEntry.getGroups().stream())
            .map(groupsItem -> groupsItem.getKey());
    }

    <T> Mono<ValueRangesMapper.Context<T>> prepareMapperContext(DomainTypeContext<T> context) {
        final DataMapping dataMapping = getDataMapping();
        return sheetsService.getRangesByName(context.getDataSource().getSpreadsheet().getId(), context.getRanges())
                .collectList()
                .map(response -> new Context<>(context.getDomainType(), context.getRanges(), response, dataMapping));
    }

}
