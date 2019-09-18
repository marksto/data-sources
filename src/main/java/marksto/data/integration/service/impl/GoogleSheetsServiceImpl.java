package marksto.data.integration.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.vavr.CheckedFunction1;
import io.vavr.control.Try;
import marksto.common.executors.TimeoutThreadPoolExecutor;
import marksto.data.integration.service.GoogleSheetsService;
import marksto.data.integration.model.CopyRangeContext;
import marksto.data.config.properties.SheetsProperties;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.cache.CacheMono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Scheduler;
import reactor.function.TupleUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static com.google.api.client.http.HttpStatusCodes.STATUS_CODE_OK;
import static com.google.auth.oauth2.ServiceAccountCredentials.fromStream;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static marksto.common.util.FunctionalUtils.isEmptyOrContainsOnlyNulls;
import static marksto.common.util.LogUtils.*;
import static marksto.data.config.properties.SheetsProperties.Client.ClientType.SERVICE_ACCOUNT;
import static marksto.data.integration.model.CopyRangeContext.UpdateType.*;
import static marksto.data.config.ExitCode.*;
import static org.apache.commons.io.IOUtils.toInputStream;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * @author marksto
 * @since 14.02.2019
 */
@Service
public class GoogleSheetsServiceImpl implements GoogleSheetsService {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleSheetsServiceImpl.class);

    // Error Messages
    private static final String GOOGLE_SHEETS_RESPONSE = "Google Sheets HTTP response: Code='%s', Status Message='%s'";
    private static final String REMOTE_SERVICE_CREATION_ERROR = "Remote Service was not properly created";

    private static final String REMOTE_SERVICE_CALL_ATTEMPT = "Remote service call '{}' attempt";
    private static final String REMOTE_SERVICE_CALL_TIME_MS = "Remote service call '{}' time={}ms";
    private static final String REMOTE_SERVICE_CALL_ERROR = "Remote Service call '{}' ended up with error: '{}'";

    private static final String ENLARGE_RANGE_ERROR_MSG = "Failed to enlarge a named range in '{}'";
    private static final String CLEAR_VALUES_SUCCESS_MSG = "Successfully cleared cells in '{}'";
    private static final String CLEAR_VALUES_FAILURE_MSG = "Failed to clear cells in '{}', reason: '{}'";
    private static final String UPDATE_VALUES_SUCCESS_MSG = "Successfully updated '{}' cells in '{}'";
    private static final String UPDATE_VALUES_FAILURE_MSG = "Failed to update cells in '{}', reason: '{}'";
    private static final String COPY_RANGES_ERROR_MSG = "Failed to copy ranges between spreadsheets '{}' and '{}'";

    // -------------------------------------------------------------------------

    /**
     * Authorize using one of the following scopes (either should do the job):
     * - "https://www.googleapis.com/auth/drive"
     * - "https://www.googleapis.com/auth/spreadsheets" (the better, restricted option)
     *
     * NOTE: If modifying these scopes, delete your previously saved '/tokens' directory.
     */
    private static final List<String> SCOPES = singletonList(SheetsScopes.SPREADSHEETS);

    private static HttpTransport httpTransport;
    private static JsonFactory jsonFactory;

    static {
        try {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            jsonFactory = JacksonFactory.getDefaultInstance();
        } catch (IOException | GeneralSecurityException e) {
            LOG.error("Unable to initialize Google API dependencies", e);
        }
    }

    // -------------------------------------------------------------------------

    private final SheetsProperties sheetsProperties;

    private final Scheduler remoteCallsScheduler;

    private final ApiStatusChecker apiStatusChecker;

    public GoogleSheetsServiceImpl(SheetsProperties sheetsProperties,
                                   @Qualifier("remoteCallsScheduler") Scheduler remoteCallsScheduler) {
        this.sheetsProperties = sheetsProperties;
        this.remoteCallsScheduler = remoteCallsScheduler;

        this.apiStatusChecker = new ApiStatusChecker(sheetsProperties, this);
    }

    @PostConstruct
    public void setUp() {
        initSheetsFuture();
        initRequestsLimiter();
        initSpreadsheetsCache();
        initCopyRangesRetryStrategy();
    }

    // -------------------------------------------------------------------------

    private static class OneShotTimeoutTPE extends TimeoutThreadPoolExecutor {

        private static final String THREAD_NAME = "one-shot-timeouter-%d";

        private OneShotTimeoutTPE(long timeout, TimeUnit timeoutUnit) {
            super(1, 1, 0, timeoutUnit,
                    new SynchronousQueue<>(),
                    new ThreadFactoryBuilder().setDaemon(true).setNameFormat(THREAD_NAME).build(),
                    timeout, timeoutUnit);
        }
    }

    private Supplier<Future<Sheets>> remoteServiceFutureSupplier;

    private void initSheetsFuture() {
        // IMPLEMENTATION NOTE: The returned supplier is thread-safe. The delegate's get()
        //                      method will be invoked at most once unless the underlying
        //                      get() throws an exception.
        remoteServiceFutureSupplier = Suppliers.memoize(this::connectToRemoteService);
    }

    /**
     * IMPORTANT NOTICE: This implementation is fail-fast. App will exit abnormally without any
     *                   attempts to reconnect to the unavailable service. The idea behind this
     *                   is to leverage the container management software capabilities.
     */
    private Future<Sheets> connectToRemoteService() {
        final var client = sheetsProperties.getClient();
        Preconditions.checkState(client.getType() == SERVICE_ACCOUNT,
                "OAuth 2.0 credentials must be of a service account Client Type, not regular");

        Duration firstConnectionTimeout = sheetsProperties.getFirstConnectionTimeout();
        var connectionBootstrapExecutor = new OneShotTimeoutTPE(firstConnectionTimeout.getSeconds(), SECONDS);

        FutureTask<Sheets> future = new FutureTask<>(() -> Try.of(() -> fromStream(getSecretAsStream(client)))
                        .onFailure(t -> {
                            LOG.error("Failed to find or parse Google API credentials", t);
                            doFailFast();
                        })
                        .map(dataBotCreds -> createSheetsServiceFor(client.getName(), dataBotCreds))
                        .flatMap(this::tryToEstablishConnection)
                        .onFailure(t -> {
                            LOG.error("Failed to establish connection with Google API", t);
                            doFailFast();
                        })
                        .onSuccess(ignored -> client.eraseCredentials())
                        .get() // it can't fail here due to its fail-fast nature
        );

        connectionBootstrapExecutor.execute(future);
        return future;
    }

    private InputStream getSecretAsStream(SheetsProperties.Client client) {
        return toInputStream(client.getSecret().replaceAll("\\\\=", "="), UTF_8);
    }

    private void doFailFast() {
        System.exit(GoogleSheetsUnavailable.getStatus());
    }

    // -------------------------------------------------------------------------

    /**
     * Build a new authorized Google Sheets API client service for an {@code applicationName}
     * (UserAgent header value) with a particular {@link GoogleCredentials} object.
     */
    private Sheets createSheetsServiceFor(String applicationName, GoogleCredentials credentials) {
        final var requestInitializer = new HttpCredentialsAdapter(credentials.createScoped(SCOPES));
        return new Sheets.Builder(httpTransport, jsonFactory, requestInitializer)
                .setApplicationName(applicationName)
                .build();
    }

    private Try<Sheets> tryToEstablishConnection(Sheets remoteService) {
        LOG.info("Establishing connection to the remote service...");

        // NOTE: Just to test a connection. Any lightweight request to the API will do.
        return Try.of(() ->
                remoteService.spreadsheets()
                        .get(sheetsProperties.getTestSheetId())
                        .setIncludeGridData(false).executeUnparsed())
                .onFailure(t -> LOG.error("Connection failure", t))
                .map(httpResponse -> {
                    int responseStatusCode = httpResponse.getStatusCode();
                    if (responseStatusCode != STATUS_CODE_OK) {
                        LOG.warn("Connection mishit (statusCode='{}')", responseStatusCode);
                        throw new IllegalStateException(format(GOOGLE_SHEETS_RESPONSE,
                                responseStatusCode, httpResponse.getStatusMessage()));
                    } else {
                        LOG.info("Connection established successfully");
                        return remoteService;
                    }
                });
    }

    // -------------------------------------------------------------------------

    /**
     * The Google Sheets API v4 requests limit is 100 requests per 100 seconds per user.
     * <p>
     * See more in the <a href="https://developers.google.com/sheets/api/limits">official docs</a>.
     * <p>
     * <b>IMPLEMENTATION NOTE:</b>
     * The easiest way to implement this is to use a "per second" limit. A more elaborate rule may be used.
     */
    @SuppressWarnings("UnstableApiUsage")
    private RateLimiter requestsLimiter;

    private final AtomicInteger requestsCounter = new AtomicInteger();

    @SuppressWarnings("UnstableApiUsage")
    private void initRequestsLimiter() {
        requestsLimiter = RateLimiter.create(sheetsProperties.getApiRequestsLimitPerSecond());
    }

    private <R> Mono<R> doWithRemoteService(CheckedFunction1<Sheets, R> workload) {
        return Mono.defer(() -> awaitRemoteServiceReadiness()
                .flatMap(sheets -> throttle(workload, sheets)))
                .subscribeOn(remoteCallsScheduler);
    }

    @SuppressWarnings("BlockingMethodInNonBlockingContext") // this is only called from within a scheduler
    private Mono<Sheets> awaitRemoteServiceReadiness() {
        try {
            var remoteServiceFuture = remoteServiceFutureSupplier.get();
            // NOTE: Here we block any remote call
            //       until the service is ready...
            //       This's a single intra-process
            //       blocking op that is required.
            var remoteService = remoteServiceFuture.get();
            return Mono.just(remoteService);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Mono.error(e);
        } catch (Exception e) {
            LOG.error(REMOTE_SERVICE_CREATION_ERROR, e);
            return Mono.error(e);
        }
    }

    private <R> Mono<R> throttle(CheckedFunction1<Sheets, R> workload,
                                 Sheets remoteService) {
        final int reqNum = requestsCounter.incrementAndGet();
        try {
            // NOTE: First await and only then log, to have the exact
            //       time when the workload/request processing began.
            if (remoteService != null) requestsLimiter.acquire();

            // NOTE: Here we gather both of two types of statistics:
            //       1. total number of remote calls (will be exact,
            //          except for requests to establish connection)
            //       2. elapsed time between start and finish of the
            //          remote service call-involving operation
            LOG.trace(REMOTE_SERVICE_CALL_ATTEMPT, reqNum);
            long timeMs = System.currentTimeMillis();

            return Mono.just(workload.apply(remoteService))
                    .doFinally(ignored -> lazyTrace(LOG, REMOTE_SERVICE_CALL_TIME_MS,
                            () -> reqNum, () -> System.currentTimeMillis() - timeMs));
        } catch (Throwable e) {
            // NOTE: Here we usually get SocketTimeoutException
            //       which is a completely uninteresting event.
            //       This and any such error is to be processed
            //       downstream, with retries, defaults, etc.
            LOG.debug(REMOTE_SERVICE_CALL_ERROR, reqNum, e.getMessage());
            return Mono.error(e);
        }
    }

    // -------------------------------------------------------------------------

    private Cache<String, Spreadsheet> spreadsheetsCache;

    private Function<String, Mono<Signal<? extends Spreadsheet>>> spreadsheetsCacheReader
            = key -> Mono.justOrEmpty(spreadsheetsCache.getIfPresent(key)).map(Signal::next);

    private BiFunction<String, Signal<? extends Spreadsheet>, Mono<Void>> spreadsheetsCacheWriter
            = (key, signal) -> Mono.fromRunnable(() -> Optional.ofNullable(signal.get())
                    .ifPresent(value -> spreadsheetsCache.put(key, value)));

    private void initSpreadsheetsCache() {
        var cacheBuilder = Caffeine.newBuilder();
        var expiresAfter = sheetsProperties.getExpireSpreadsheetsCacheEvery();
        if (expiresAfter != null) {
            cacheBuilder.expireAfterWrite(expiresAfter);
        }
        spreadsheetsCache = cacheBuilder.build();
    }

    private Mono<Spreadsheet> retrieveSpreadsheet(String spreadsheetId) {
        return CacheMono.lookup(spreadsheetsCacheReader, spreadsheetId)
                .onCacheMissResume(() -> getRemote(spreadsheetId))
                .andWriteWith(spreadsheetsCacheWriter);
    }

    private Mono<Spreadsheet> getRemote(String spreadsheetId) {
        // NOTE: The 'CacheMono#lookup.onCacheMissResume' will get here anyway,
        //       even in the case of a successful cache hit — but it won't run
        //       the workload, i.e. subscribe to this Mono instance, until the
        //       real cache miss, i.e. empty Mono — then it simply do 'switch'.
        return doWithRemoteService(remoteService ->
                remoteService.spreadsheets()
                        .get(spreadsheetId).setIncludeGridData(false).execute()
        );
    }

    // -------------------------------------------------------------------------

    @Override
    public Mono<String> getTitleOf(String spreadsheetId) {
        return retrieveSpreadsheet(spreadsheetId) // normally, it is already cached
                .map(spreadsheet -> defaultString(spreadsheet.getProperties().getTitle()));
    }

    // -------------------------------------------------------------------------

    @Override
    public Flux<NamedRange> getNamedRangesOf(String spreadsheetId) {
        return getNamedRangesOf_Impl(spreadsheetId, false);
    }

    private Flux<NamedRange> getNamedRangesOf_Impl(String spreadsheetId, boolean forceUpdate) {
        return Mono.defer(() -> {
                    if (forceUpdate) {
                        return getRemote(spreadsheetId)
                                .doOnSuccess(spreadsheet -> spreadsheetsCache.put(spreadsheetId, spreadsheet));
                    } else {
                        // try to retrieve already cached version
                        return retrieveSpreadsheet(spreadsheetId);
                    }
                })
                .flatMapMany(spreadsheet -> Flux.fromIterable(spreadsheet.getNamedRanges()));
    }

    private Function<Triple<String, String, Boolean>, Mono<NamedRange>> getNamedRangeFn =
            triple -> {
                String spreadsheetId = triple.getLeft();
                String namedRangeName = triple.getMiddle();
                boolean tryCacheReload = triple.getRight();

                return getNamedRangesOf_Impl(spreadsheetId, tryCacheReload)
                        .filter(range -> namedRangeName.equals(range.getName()))
                        .next();
            };

    private Mono<NamedRange> getNamedRange(String spreadsheetId, String namedRangeName) {
        return getNamedRangeFn.apply(Triple.of(spreadsheetId, namedRangeName, false))
                .switchIfEmpty(getNamedRangeFn.apply(Triple.of(spreadsheetId, namedRangeName, true)));
    }

    // -------------------------------------------------------------------------

    @Override
    public Mono<ValueRange> getRangeByName(String spreadsheetId, String range) {
        lazyDebug(LOG, "Getting the '{}' range from '{}'", () -> range, () -> toLogForm(spreadsheetId));

        return doWithRemoteService(remoteService ->
                remoteService.spreadsheets().values()
                        .get(spreadsheetId, range).execute());
    }

    @Override
    public Flux<ValueRange> getRangesByName(String spreadsheetId, List<String> ranges) {
        lazyDebug(LOG, "Getting '{}' ranges from '{}'", ranges::size, () -> toLogForm(spreadsheetId));

        return doWithRemoteService(remoteService ->
                remoteService.spreadsheets().values()
                        .batchGet(spreadsheetId).setRanges(ranges).execute())
                .flatMapMany(response -> Flux.fromIterable(response.getValueRanges()));
    }

    // -------------------------------------------------------------------------

    private UnaryOperator<Mono<Void>> addRetryWithBackoffTransformer;

    private void initCopyRangesRetryStrategy() {
        var copyDataRetriesNum = sheetsProperties.getCopyDataRetriesNum();
        var copyData1stBackoff = sheetsProperties.getCopyData1stBackoff();
        var copyDataMaxBackoff = sheetsProperties.getCopyDataMaxBackoff();
        addRetryWithBackoffTransformer = mono ->
                mono.retryBackoff(copyDataRetriesNum, copyData1stBackoff, copyDataMaxBackoff);
    }

    @Override
    public Mono<Void> copyRangesBetweenSpreadsheets(final String fromSsId, final String toSsId,
                                                    final List<String> ranges, final boolean awaitTillReady) {
        return prepareCopyRangeContexts(fromSsId, toSsId, ranges)
                .flatMap(copyRangeContexts -> combineMainOperation(toSsId, copyRangeContexts, awaitTillReady))
                .transform(addRetryWithBackoffTransformer)
                .doOnError(e -> lazyWarn(LOG, e, COPY_RANGES_ERROR_MSG,
                        () -> toLogForm(fromSsId), () -> toLogForm(toSsId)));
    }

    private final RangeSizesHelper rangeSizesHelper = new RangeSizesHelper();

    private Mono<List<CopyRangeContext>> prepareCopyRangeContexts(final String fromSsId, final String toSsId,
                                                                  final List<String> ranges) {
        return Flux.zip(
                Flux.fromIterable(ranges),
                apiStatusChecker.awaitSpreadsheetReadyStatus(fromSsId)
                        .thenMany(getRangesByName(fromSsId, ranges)),
                getRangesByName(toSsId, ranges))
                .flatMap(tuple -> {
                    String namedRangeName = tuple.getT1();
                    ValueRange valueRangeFrom = tuple.getT2();
                    ValueRange valueRangeToOld = tuple.getT3();

                    final int rowsDelta = rangeSizesHelper
                            .getRowsDelta(valueRangeFrom, valueRangeToOld);

                    return Mono.zip(
                            prepareRange(valueRangeFrom, valueRangeToOld, rowsDelta),
                            prepareUpdateNamedRangeRequest(toSsId, namedRangeName, rowsDelta),
                            indicateUpdateType(rowsDelta)
                    ).map(TupleUtils.function(CopyRangeContext::new));
                })
                .collectList();
    }

    private Mono<Void> combineMainOperation(final String toSsId, List<CopyRangeContext> copyRanges,
                                            final boolean awaitTillReady) {
        // NOTE: Pre-copy requests are required to expand every range that does not fit the data size.
        //       Post-copy ones do the opposite (narrow the ranges down), which allows not to carry out
        //       additional cleaning from the remnants of previous data in case of corresponding ranges
        //       size reduction.
        Mono<Void> preCopyRequests = fireAuxiliaryRequestFor(PRE, copyRanges, toSsId);
        Mono<Void> postCopyRequests = fireAuxiliaryRequestFor(POST, copyRanges, toSsId);

        final List<ValueRange> valueRangesToNew = copyRanges.stream()
                .map(CopyRangeContext::getValueRange)
                .collect(toList());

        final UnaryOperator<Mono<Void>> optionallyAwait = mono -> awaitTillReady
                ? mono.then(apiStatusChecker.awaitSpreadsheetReadyStatus(toSsId)) : mono;

        // IMPORTANT:
        // Here we often get a 'Socket Timeout' caused by spreadsheet formulas recalculation.
        // This is largely due to the fact that some tables begin to recalculate the formulas
        // right after an auxiliary request, and that takes some time in cases where there're
        // many chained formulas and/or custom functions used. Therefore, we MUST AWAIT after
        // each request that we send (except for the first one which works fine w/o any waits
        // and this optimization saves us a few network calls on each pair synchronization).
        return preCopyRequests
                .then(fireClearingRequestFor(toSsId, valueRangesToNew))
                //.then(awaitSpreadsheetReadyStatus(toSsId)) // uncomment if unstable
                .then(fireCopyDataRequestFor(toSsId, valueRangesToNew))
                .then(apiStatusChecker.awaitSpreadsheetReadyStatus(toSsId))
                .then(postCopyRequests)
                .transform(optionallyAwait);
    }

    // -------------------------------------------------------------------------

    private static final List<Object> EMPTY_CELL_VALUE = singletonList(EMPTY);

    private Mono<ValueRange> prepareRange(ValueRange valueRangeFrom, ValueRange valueRangeTo, int rowsDelta) {
        return Mono.fromSupplier(() -> {
            ValueRange valueRangeToNew = new ValueRange();

            // set range + prepare update requests
            String rangeStr = valueRangeTo.getRange();
            if (rowsDelta > 0) {
                rangeStr = rangeSizesHelper.getIncreasedRangeStr(rangeStr, rowsDelta);
            }
            valueRangeToNew.setRange(rangeStr);

            // set range values (including empty ones to clean up cells)
            List<List<Object>> values = prepareValueRangeValues(valueRangeFrom, valueRangeTo);
            valueRangeToNew.setValues(values);

            return valueRangeToNew;
        });
    }

    private List<List<Object>> prepareValueRangeValues(ValueRange valueRangeFrom, ValueRange valueRangeTo) {
        int valuesSize = rangeSizesHelper.getBiggerRangeDimension(valueRangeFrom, valueRangeTo);
        List<List<Object>> values = new ArrayList<>(valuesSize);
        for (int k = 0; k < valuesSize; k++) {
            if (valueRangeFrom.getValues() != null
                    && k < valueRangeFrom.getValues().size()) {
                values.add(k, valueRangeFrom.getValues().get(k));
            } else {
                values.add(k, EMPTY_CELL_VALUE);
            }
        }
        return values;
    }

    // -------------------------------------------------------------------------

    private Mono<Request> prepareUpdateNamedRangeRequest(String spreadsheetId, String namedRangeName, int rowsDelta) {
        return getNamedRange(spreadsheetId, namedRangeName)
                .map(namedRange -> {
                    NamedRange newNamedRange = namedRange.clone(); // immutable, to not poison the cache

                    GridRange namedRangeGridRange = newNamedRange.getRange();
                    int newEndRowIndex = namedRangeGridRange.getEndRowIndex() + rowsDelta;
                    namedRangeGridRange.setEndRowIndex(newEndRowIndex);

                    return new Request().setUpdateNamedRange(new UpdateNamedRangeRequest()
                            .setNamedRange(newNamedRange.setRange(namedRangeGridRange))
                            .setFields("*")
                    );
                });
    }

    // -------------------------------------------------------------------------

    private Mono<CopyRangeContext.UpdateType> indicateUpdateType(int rowsDelta) {
        if (rowsDelta > 0) {
            return Mono.just(PRE);
        } else if (rowsDelta < 0) {
            return Mono.just(POST);
        }
        return Mono.just(NONE);
    }

    // -------------------------------------------------------------------------

    private Mono<Void> fireAuxiliaryRequestFor(CopyRangeContext.UpdateType updateType,
                                               List<CopyRangeContext> copyRanges,
                                               String spreadsheetId) {
        // NOTE: Only prepares an auxiliary request that is necessary for copying ranges,
        //       but doesn't call it. Will be executed on a resulting Mono subscription.
        Function<List<CopyRangeContext>, Mono<Void>> lazyUpdate = copyRangeList ->
                updateSpreadsheet(updateType, spreadsheetId, copyRangeList.stream()
                        .map(CopyRangeContext::getUpdateRequest)
                        .collect(toList()));

        return Mono.just(copyRanges)
                .map(contexts -> contexts.stream()
                        .filter(ctx -> ctx.getUpdateType() == updateType)
                        .collect(toList()))
                .flatMap(lazyUpdate);
    }

    private Mono<Void> updateSpreadsheet(CopyRangeContext.UpdateType updateType,
                                         String spreadsheetId, List<Request> requests) {
        if (isEmptyOrContainsOnlyNulls(requests)) {
            return Mono.empty();
        }

        BatchUpdateSpreadsheetRequest content
                = new BatchUpdateSpreadsheetRequest().setRequests(requests);

        lazyDebug(LOG, "Firing auxiliary request of type '{}' for '{}'",
                updateType::name, () -> toLogForm(spreadsheetId));

        return doWithRemoteService(remoteService ->
                remoteService.spreadsheets()
                        .batchUpdate(spreadsheetId, content).execute())
                .doOnSuccess(res -> lazyDebug(LOG, "Spreadsheet '{}' was updated",
                        () -> toLogForm(spreadsheetId)))
                .doOnError(e -> lazyWarn(LOG, e, ENLARGE_RANGE_ERROR_MSG,
                        () -> toLogForm(spreadsheetId)))
                .then();
    }

    // -------------------------------------------------------------------------

    private Mono<Void> fireClearingRequestFor(String toSsId,
                                              List<ValueRange> valueRangesToNew) {
        lazyDebug(LOG, "Firing clearing request for '{}'", () -> toLogForm(toSsId));

        return doWithRemoteService(remoteService ->
                remoteService.spreadsheets().values()
                        .batchClear(toSsId, new BatchClearValuesRequest()
                                .setRanges(valueRangesToNew.stream()
                                        .map(ValueRange::getRange)
                                        .collect(toList())))
                        .execute())
                .doOnSuccess(res -> lazyDebug(LOG, CLEAR_VALUES_SUCCESS_MSG,
                        () -> toLogForm(toSsId)))
                .doOnError(e -> lazyWarn(LOG, CLEAR_VALUES_FAILURE_MSG,
                        () -> toLogForm(toSsId), e::getMessage))
                .then();
    }

    private Mono<Void> fireCopyDataRequestFor(String toSsId,
                                              List<ValueRange> valueRangesToNew) {
        lazyDebug(LOG, "Firing copy data request for '{}'", () -> toLogForm(toSsId));

        return doWithRemoteService(remoteService ->
                remoteService.spreadsheets().values()
                        .batchUpdate(toSsId, new BatchUpdateValuesRequest()
                                .setValueInputOption("USER_ENTERED")
                                .setData(valueRangesToNew))
                        .execute())
                .doOnSuccess(res -> lazyDebug(LOG, UPDATE_VALUES_SUCCESS_MSG,
                        res::getTotalUpdatedCells, () -> toLogForm(toSsId)))
                .doOnError(e -> lazyWarn(LOG, UPDATE_VALUES_FAILURE_MSG,
                        () -> toLogForm(toSsId), e::getMessage))
                .then();
    }

}
