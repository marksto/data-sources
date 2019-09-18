package marksto.data.service.impl;

import com.google.common.base.Joiner;
import marksto.data.schemas.DataSource;
import io.vavr.Function2;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static marksto.data.schemas.DataSource.DataSourceDependency;
import static marksto.common.util.LogUtils.toLogForm;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

@SuppressWarnings("Convert2MethodRef") // otherwise, Manifold will fail with class cast errors
class DataSourceHelper {

    private DataSourceHelper() {}

    // -------------------------------------------------------------------------

    static String print(final DataSource dataSource) {
        return "{" + toLogForm(dataSource.getSpreadsheet().getId())
                + (dataSource.getName() == null ? "" : "|" + dataSource.getName()) + "}";
    }

    static String print(final Iterable<DataSource> dataSources) {
        return print(dataSources, false);
    }

    static String print(final Iterable<DataSource> dataSources, final boolean withSpreadsheetId) {
        return Joiner.on(", ").join(stream(dataSources.spliterator(), false)
                .map(dataSource -> withSpreadsheetId
                        ? print(dataSource) : dataSource.getName())
                .collect(toList()));
    }

    static String generateRandomName(final String prefix) {
        String randomName = UUID.randomUUID().toString().substring(0, 4);
        return Joiner.on('-').join(prefix, randomName);
    }

    // -------------------------------------------------------------------------

    /**
     * Returns a copy of the provided base {@link DataSource} instance filled-up with
     * metadata-related properties coming from the Metadata-containing one.
     */
    static DataSource copyWithMetadata(final DataSource baseDataSource, final DataSource metadata) {
        return DataSource.copier(baseDataSource)
                .withSpreadsheet(DataSource.spreadsheet.copier(baseDataSource.getSpreadsheet())
                        .withStatus(metadata.getSpreadsheet().getStatus().copy())
                        .copy())
                .withDependsOn(metadata.getDependsOn())
                .withName(metadata.getName())
                .copy();
    }

    // -------------------------------------------------------------------------

    /**
     * Returns the set of this {@link DataSource} dependencies as a non-{@code null} {@link Stream}.
     */
    static Stream<DataSourceDependency> getDependenciesStream(final DataSource dataSource) {
        return dataSource.getDependsOn() == null ? Stream.empty() : dataSource.getDependsOn().stream();
    }

    /**
     * Effectively cleans up dependent Data Sources' Named Ranges from the Data Structure
     * of the passed {@link DataSource} instance.
     */
    static void cleanFromDependentRanges(final DataSource dataSource) {
        getDependenciesStream(dataSource)
                .flatMap(dependency -> dependency.getRanges().stream())
                .forEach(depNamRang -> findAndRemoveNamedRange(dataSource, depNamRang));
    }

    /**
     * Removes a specific Named Range from the {@link DataSource}'s Data Structure.
     */
    static void findAndRemoveNamedRange(final DataSource dataSource, final String namedRange) {
        String groupKey = substringBefore(namedRange, "_");
        List group = (List) dataSource.getDataStructure().get(groupKey);
        group.remove(namedRange);
        if (group.isEmpty()) {
            removeDataRangesGroup(dataSource, groupKey);
        }
    }

    // -------------------------------------------------------------------------

    // NOTE: The key of the ranges group of the data structure that holds metadata properties.
    private static final String META_RANGE_GROUP_KEY = "spreadsheet";

    /**
     * Removes the Metadata-related Ranges Group from the {@link DataSource}'s Data Structure.
     */
    static void removeMetadata(final DataSource dataSource) {
        removeDataRangesGroup(dataSource, META_RANGE_GROUP_KEY);
    }

    /**
     * Removes a specific Ranges Group from the {@link DataSource}'s Data Structure.
     */
    static void removeDataRangesGroup(final DataSource dataSource, final String groupKey) {
        dataSource.getDataStructure().getBindings().remove(groupKey);
    }

    // -------------------------------------------------------------------------

    static Predicate<DataSource> isIndependent = dataSource ->
            getDependenciesStream(dataSource).allMatch(dsd -> dsd.getHistorical()); /* true, if empty */

    static BiFunction<DataSource, Map<String, DataSource>, Integer> weightFn
            = Function2.<DataSource, Map<String, DataSource>, Integer>of((ds, dsm) -> getWeight(ds, dsm))
            .memoized();

    private static int getWeight(final DataSource dataSource, final Map<String, DataSource> dataSourcesMap) {
        // IMPLEMENTATION NOTE: No need for trampolining/tail-call optimization — small depth.
        if (isIndependent.test(dataSource)) {
            return 1;
        }
        return getDependenciesStream(dataSource)
                .filter(dsDep -> dataSourcesMap.containsKey(dsDep.getSource()) && !dsDep.getHistorical())
                .mapToInt(dep -> weightFn.apply(dataSourcesMap.get(dep.getSource()), dataSourcesMap))
                .reduce(0, Integer::sum);
    }

}
