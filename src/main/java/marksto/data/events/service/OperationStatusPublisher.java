package marksto.data.events.service;

import marksto.data.schemas.DataSource;

import static marksto.events.OperationStatusEvent.Status;

public interface OperationStatusPublisher {

    // --- w/o status type -----------------------------------------------------

    void publishStatusForDataSources(String statusMessage);

    void publishStatusFor(String dataSource, String statusMessage);
    void publishStatusFor(DataSource dataSource, String statusMessage);

    // --- with status type ----------------------------------------------------

    void publishStatusForDataSources(Status statusType, String statusMessage);

    void publishStatusFor(String dataSource, Status statusType, String statusMessage);
    void publishStatusFor(DataSource dataSource, Status statusType, String statusMessage);

}
