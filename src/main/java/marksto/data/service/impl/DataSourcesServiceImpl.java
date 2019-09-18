package marksto.data.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import marksto.data.schemas.DataSource;
import marksto.common.util.LogUtils;
import marksto.data.config.properties.DataSourcesProperties;
import marksto.data.integration.service.GoogleSheetsService;
import marksto.data.service.DataSourceInitializer;
import marksto.data.service.DataSourcesService;
import marksto.data.events.service.OperationStatusPublisher;
import marksto.web.mapping.JsonResourcesMapper;
import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static java.lang.String.format;
import static marksto.data.schemas.DataSource.DataSourceDependency;
import static marksto.common.util.LogUtils.lazyWarn;
import static marksto.common.util.ReactivePreconditions.*;
import static marksto.data.config.ExitCode.*;
import static marksto.data.service.impl.DataSourceHelper.*;
import static marksto.events.OperationStatusEvent.Status.*;
import static marksto.web.config.ServiceConfiguration.DevServices;
import static org.apache.commons.collections4.CollectionUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

/**
 * The {@link DataSourcesService} implementation.
 *
 * @author marksto
 * @since 03.03.2018
 */
@Service
@SuppressWarnings("Convert2MethodRef") // otherwise, Manifold will fail with class cast errors
public class DataSourcesServiceImpl implements DataSourcesService {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourcesServiceImpl.class);

    // Error Messages
    private static final String INIT_ERROR_MSG = "Error while initializing the Data Sources";
    private static final String ALREADY_EXISTS_MSG = "An attempt to register an already existing DataSource entity";
    private static final String RETRIEVAL_FAILURE_MSG = "Failure while retrieving the Data Source or it is not ready";

    private static final String NON_EXISTENT_DATA_SOURCE_MSG = "Non-existent Data Sources synchronization attempt ('{}')";
    private static final String NON_DEPENDENT_DATA_SOURCES_MSG = "Non-dependent Data Sources synchronization attempt ('{}' and '{}')";

    // Status Messages
    private static final String INITIALIZATION_STARTED = "Initialization started...";
    private static final String NEWLY_ADDED = "Initialized (newly added)";
    private static final String CONSTRUCTING_GRAPH = "Constructing graph...";
    private static final String PERSISTING = "Persisting results...";
    private static final String INITIALIZATION_FINISHED = "Initialization finished";

    private static final String RELOADING_STARTED = "Cache reloading started...";
    private static final String RELOADING_FAILURE = "Cache reloading failed!";
    private static final String REINITIALIZED = "Re-initialized";
    private static final String RELOADING_FINISHED = "Reloading finished";

    private static final String SYNCHRONIZATION_STARTED = "Data sync started...";
    private static final String SYNCHRONIZING_FROM = "Synchronizing from: %s";
    private static final String SYNC_SUCCESS = "Data was synced with '%s'";
    private static final String SYNC_FAILURE = "Sync with '%s' failed!";
    private static final String SYNCHRONIZATION_FINISHED = "Data sync finished";

    // UI Messages Codes
    private static final String NAME_PREFIX_CODE = "data.sources.name-prefix";

    // -------------------------------------------------------------------------

    private final DataSourcesProperties dataSourcesProperties;

    private final GoogleSheetsService sheetsService;

    private final DataSourceInitializer dataSourceInitializer;

    private final JsonResourcesMapper jsonResourcesMapper;

    private final OperationStatusPublisher statusPublisher;

    private final MessageSource messageSource;

    public DataSourcesServiceImpl(DataSourcesProperties dataSourcesProperties,
                                  GoogleSheetsService sheetsService,
                                  DataSourceInitializer dataSourceInitializer,
                                  @Qualifier("dataSourceMapper") JsonResourcesMapper jsonResourcesMapper,
                                  OperationStatusPublisher statusPublisher,
                                  MessageSource messageSource) {
        this.dataSourcesProperties = dataSourcesProperties;
        this.sheetsService = sheetsService;
        this.dataSourceInitializer = dataSourceInitializer;
        this.jsonResourcesMapper = jsonResourcesMapper;
        this.statusPublisher = statusPublisher;
        this.messageSource = messageSource;
    }

    // -------------------------------------------------------------------------

    private final CopyOnWriteArrayList<String> dataSourcesKeys = new CopyOnWriteArrayList<>();

    private final Cache<String, DataSource> dataSourcesCache = Caffeine.newBuilder().build();

    private BiConsumer<String, DataSource> cacheOne = (key, dataSource) -> {
        dataSourcesCache.put(key, dataSource);
        if (dataSourcesKeys.addIfAbsent(key)) {
            dataSourcesKeys.sort(Comparator.naturalOrder());
        }
    };

    private Consumer<String> invalidateOne = key -> dataSourcesCache.invalidate(key);

    private Map<String, DataSource> getDataSourcesMap() {
        // NOTE: No need to worry about the outdated keys since {@code getAllPresent} is used.
        return dataSourcesCache.getAllPresent(dataSourcesKeys);
    }

    // -------------------------------------------------------------------------

    /**
     * IMPORTANT NOTICE: This implementation is fail-fast. App will exit abnormally without any
     *                   attempts to repeat the failing data sources retrieval. The idea behind
     *                   this is to leverage the container management software capabilities to
     *                   revive the failed instances.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartUp() {
        initializeDataSourcesCache(false)
                .doOnSubscribe(s -> statusPublisher
                        .publishStatusForDataSources(START, INITIALIZATION_STARTED))
                .doOnSuccess(res -> statusPublisher
                        .publishStatusForDataSources(TERMINATION_SUCCESS, INITIALIZATION_FINISHED))
                .doOnError(e -> {
                    LOG.error(INIT_ERROR_MSG, e);
                    System.exit(DataSourcesUninitialized.getStatus());
                })
                .subscribe();
    }

    private Mono<Void> initializeDataSourcesCache(boolean reInitialize) {
        return Flux.fromIterable(dataSourcesProperties.getSheetsIds())
                .flatMap(this::loadRemoteDataSource)
                .flatMap(dataSource -> registerDataSource(dataSource, reInitialize))
                .doOnNext(dataSource -> statusPublisher
                        .publishStatusFor(dataSource, reInitialize ? REINITIALIZED : NEWLY_ADDED))
                .then(runAfterRegistrationRoutines()); // called once, after all are registered
    }

    // -------------------------------------------------------------------------

    private Mono<DataSource> loadRemoteDataSource(String spreadsheetId) {
        return dataSourceInitializer.initDataSourceFor(spreadsheetId)
                .flatMap(dataSource -> attemptToReInit(dataSource))
                .doOnError(e -> LogUtils.lazyWarn(LOG, e, RETRIEVAL_FAILURE_MSG));
    }

    private Mono<DataSource> attemptToReInit(DataSource dataSource) {
        return checkState(dataSource, dataSource.getState() == DataSource.state.ready,
                () -> format("Invalid DataSource: state='%s', but not \"ready\"", dataSource.getState()))
                .onErrorResume(t -> Mono.delay(dataSourcesProperties.getAutoReInitIn())
                        .then(dataSourceInitializer.initDataSourceFor(dataSource.getSpreadsheet().getId())
                                .retry(dataSourcesProperties.getReInitRetriesNum()))); // no need in exp. backoff
    }

    // -------------------------------------------------------------------------

    @Override
    public Mono<DataSource> registerNewDataSource(String spreadsheetId) {
        return checkArgumentNotNull(spreadsheetId, "Spreadsheet ID must not be null")
                .flatMap(this::checkNonExistingDataSource)
                .flatMap(this::loadRemoteDataSource)
                .flatMap(dataSource -> registerNewDataSource(dataSource));
    }

    private Mono<String> checkNonExistingDataSource(String dataSourceName) {
        return checkArgument(dataSourceName,
                getDataSourcesMap().values().stream()
                        .filter(dataSource -> dataSourceName.equals(dataSource.getSpreadsheet().getId()))
                        .findFirst()
                        .isEmpty(),
                ALREADY_EXISTS_MSG);
    }

    @Override
    public Mono<DataSource> registerNewDataSource(DataSource newOne) {
        return checkArgumentNotNull(newOne, "DataSource must not be null")
                .flatMap(dataSource -> registerDataSource(dataSource, false))
                .doOnSuccess(dataSource -> statusPublisher.publishStatusFor(dataSource, START, NEWLY_ADDED))
                .then(runAfterRegistrationRoutines()) // called on each newly added 'dataSource'
                .thenReturn(newOne);
    }

    private Mono<DataSource> registerDataSource(final DataSource dataSource, boolean doInvalidateCache) {
        return checkArgumentNotNull(dataSource.getName())
                .onErrorResume(t -> {
                    var name = provideUniqueName();
                    dataSource.setName(name);
                    return Mono.just(name);
                })
                .flatMap(key -> {
                    if (doInvalidateCache) {
                        invalidateOne.accept(key);
                        return Mono.just(key);
                    } else {
                        return checkNonExistingDataSource(dataSource.getName());
                    }
                })
                .doOnError(t -> statusPublisher.publishStatusFor(dataSource, t.getMessage()))
                .flatMap(key -> {
                    LOG.info("Registering new DataSource: name='{}'", key);
                    cacheOne.accept(key, dataSource);
                    return Mono.just(dataSource);
                });
    }

    private String provideUniqueName() {
        String randomName;
        do {
            Locale locale = LocaleContextHolder.getLocale();
            String prefix = messageSource.getMessage(NAME_PREFIX_CODE, null, locale);
            randomName = generateRandomName(prefix);
        } while (dataSourcesCache.getIfPresent(randomName) != null);
        return randomName;
    }

    // -------------------------------------------------------------------------

    private Mono<Void> runAfterRegistrationRoutines() {
        return Mono.fromRunnable(this::constructDataSourcesGraph)
                .and(Mono.defer(() -> Mono.fromRunnable(this::persistDataSources))
                        .subscribeOn(Schedulers.elastic()));
    }

    private final DataSourcesGraphHandler graphHandler = new DataSourcesGraphHandler();

    private void constructDataSourcesGraph() {
        LOG.debug("The Data Sources Graph construction — Starting...");
        statusPublisher.publishStatusForDataSources(CONSTRUCTING_GRAPH);

        graphHandler.constructGraph(getDataSourcesMap());

        LOG.debug("The Data Sources Graph construction — Finished!");
    }

    private void persistDataSources() {
        LOG.debug("Persisting the Data Sources as JSON file — Starting...");
        statusPublisher.publishStatusForDataSources(PERSISTING);

        if (DevServices.isInDevelopment()) {
            // NOTE: Persists the 'dataSourcesCache' into the filesystem under the 'target' directory.
            jsonResourcesMapper.mapToFile(getDataSourcesMap(), dataSourcesProperties.getPath());
        } else {
            throw new NotImplementedException("Google Drive persistence is not implemented yet");
        }

        LOG.debug("Persisting the Data Sources as JSON file — Finished!");
    }

    // -------------------------------------------------------------------------

    @Override
    public Mono<DataSource> retrieveDataSource(String name, boolean forceRemote) {
        return checkArgumentNotNull(name, "DataSource name must not be null")
                .flatMap(key -> checkStateNotNull(dataSourcesCache.getIfPresent(key),
                        "Invalid DataSource name"))
                .flatMap(cached -> forceRemote ? reloadRemoteFor(cached) : Mono.just(cached));
    }

    private Mono<DataSource> reloadRemoteFor(DataSource cached) {
        return loadRemoteDataSource(cached.getSpreadsheet().getId())
                .doOnSubscribe(s -> statusPublisher
                        .publishStatusFor(cached, START, RELOADING_STARTED))
                .flatMap(reloaded -> doAfterSuccessfulReload(reloaded))
                .doOnError(e -> statusPublisher
                        .publishStatusFor(cached, TERMINATION_FAILURE, RELOADING_FAILURE))
                .doOnSuccess(dataSource -> statusPublisher
                        .publishStatusFor(dataSource, TERMINATION_SUCCESS, RELOADING_FINISHED));
    }

    public Mono<DataSource> doAfterSuccessfulReload(DataSource reloaded) {
        return checkStateNotNull(reloaded.getName(), "DataSource name must not be null")
                .flatMap(key -> Mono.fromRunnable(() -> cacheOne.accept(key, reloaded)))
                .then(runAfterRegistrationRoutines()) // have to be called (new object)
                .thenReturn(reloaded);
    }

    @Override
    public Flux<DataSource> retrieveDataSources(boolean forceRemote) {
        Mono<Void> prepareOp = Mono.empty();
        if (forceRemote) {
            prepareOp = initializeDataSourcesCache(true)
                    .doOnSubscribe(s -> statusPublisher
                            .publishStatusForDataSources(START, RELOADING_STARTED))
                    .doOnError(e -> statusPublisher
                            .publishStatusForDataSources(TERMINATION_FAILURE, RELOADING_FAILURE))
                    .doOnSuccess(res -> statusPublisher
                            .publishStatusForDataSources(TERMINATION_SUCCESS, RELOADING_FINISHED));
        }
        return prepareOp.thenMany(Flux.fromIterable(getDataSourcesMap().values()));
    }

    // -------------------------------------------------------------------------

    @Override
    public Mono<Void> synchronizeDataBetween(String dependencyName, String dependentName, boolean andSubGraph) {
        return checkArgument(dependencyName, isNotEmpty(dependencyName), "Empty dependency 'name'")
                .flatMap(dependencyKey -> checkArgumentNotNull(dataSourcesCache.getIfPresent(dependencyKey))
                        .doOnError(e -> lazyWarn(LOG, NON_EXISTENT_DATA_SOURCE_MSG, () -> dependencyKey)))
                .flatMap(dependency -> synchronizeDataBetween_Impl(dependency, dependentName, andSubGraph))
                .doOnSuccess(res -> statusPublisher
                        .publishStatusForDataSources(TERMINATION_SUCCESS, SYNCHRONIZATION_FINISHED));
    }

    private Mono<Void> synchronizeDataBetween_Impl(final DataSource dependency,
                                                   final String dependentKey,
                                                   final boolean andSubGraph) {
        if (dependentKey == null) {
            statusPublisher.publishStatusForDataSources(START, SYNCHRONIZATION_STARTED);
            return graphHandler.traverse(List.of(dependency), pairSynchronization);
        } else {
            statusPublisher.publishStatusFor(dependentKey, START, SYNCHRONIZATION_STARTED);
            return checkArgumentNotNull(dataSourcesCache.getIfPresent(dependentKey))
                    .doOnError(e -> lazyWarn(LOG, NON_EXISTENT_DATA_SOURCE_MSG, () -> dependentKey))
                    .flatMap(dependent -> synchronizePairAndSubGraph(dependency, dependent, andSubGraph));
        }
    }

    @Override
    public Mono<Void> synchronizeData() {
        statusPublisher.publishStatusForDataSources(START, SYNCHRONIZATION_STARTED);

        Consumer<Iterable<DataSource>> startNodesReporter = (var startNodes) ->
                statusPublisher.publishStatusForDataSources(format(SYNCHRONIZING_FROM, print(startNodes)));

        return graphHandler.traverse(pairSynchronization, startNodesReporter)
                .doOnSuccess(res -> statusPublisher
                        .publishStatusForDataSources(TERMINATION_SUCCESS, SYNCHRONIZATION_FINISHED));
    }

    private BiFunction<DataSource, DataSource, Mono<Void>> pairSynchronization
            = (dependent, dependency) -> synchronizePair(dependent, dependency, isEmpty(dependent.getDependants()));

    private Mono<Void> synchronizePair(final DataSource dependent,
                                       final DataSource dependency,
                                       final boolean awaitTillReady) {
        final String dependencyName = dependency.getName();
        final DataSourceDependency dependencyInfo = getDependenciesStream(dependent)
                .filter(dsDep -> dsDep.getSource().equals(dependencyName))
                .findFirst().orElse(null);
        if (dependencyInfo == null) {
            LOG.warn(NON_DEPENDENT_DATA_SOURCES_MSG, dependent.getName(), dependencyName);
            return Mono.error(IllegalArgumentException::new);
        }

        return sheetsService.copyRangesBetweenSpreadsheets(
                dependency.getSpreadsheet().getId(),
                dependent.getSpreadsheet().getId(),
                dependencyInfo.getRanges(),
                awaitTillReady
        )
                .doOnError(e -> statusPublisher
                        .publishStatusFor(dependent, TERMINATION_FAILURE, format(SYNC_FAILURE, dependencyName)))
                .doOnSuccess(res -> statusPublisher
                        .publishStatusFor(dependent, format(SYNC_SUCCESS, dependencyName)));
    }

    private Mono<Void> synchronizePairAndSubGraph(final DataSource dependency,
                                                  final DataSource dependent,
                                                  final boolean andSubGraph) {
        final boolean awaitTillReady = !andSubGraph || isEmpty(dependent.getDependants());
        Mono<Void> syncOperation = synchronizePair(dependent, dependency, awaitTillReady);
        if (andSubGraph) {
            syncOperation = syncOperation
                    .then(graphHandler.traverse(List.of(dependent), pairSynchronization));
        }
        return syncOperation;
    }

}
