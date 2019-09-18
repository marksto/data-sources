package marksto.data.service;

import marksto.data.schemas.DataSource;
import reactor.core.publisher.Mono;

import static marksto.data.schemas.DataSource.state.empty;
import static marksto.data.schemas.DataSource.state.ready;

/**
 * A separate service (facade) that specializes in creating new {@link DataSource}
 * instances, representing the complex process behind this operation as a single,
 * atomic action with a definite set of possible outcomes.
 *
 * @author marksto
 * @since 03.06.2019
 */
public interface DataSourceInitializer {

    /**
     * Initializes a new {@link DataSource} instance by its unique external system ID.
     * <p>
     * <b>NOTE:</b> May result in a {@link DataSource} instance in an "unready" state.
     *
     * @param spreadsheetId the corresponding Google Spreadsheet ID
     * @return A new (non-{@code null}) {@link DataSource} instance in one of the
     *         following states:
     *         <ul>
     *             <li>
     *                 the instance being {@link empty} or "shallow", i.e. that
     *                 didn't pass the initialization process at all and keeping
     *                 only the {@code spreadsheetId}
     *             </li>
     *             <li>
     *                 the instance being completely {@link ready}, i.e. that
     *                 did successfully pass the initialization process
     *             </li>
     *         </ul>
     */
    Mono<DataSource> initDataSourceFor(String spreadsheetId);

}
