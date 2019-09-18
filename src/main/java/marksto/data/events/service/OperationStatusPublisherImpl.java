package marksto.data.events.service;

import marksto.data.events.DataSourceEvent;
import marksto.data.events.dto.DataSourceInfo;
import marksto.data.schemas.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import static marksto.events.OperationStatusEvent.Status;

@Service
public class OperationStatusPublisherImpl implements OperationStatusPublisher {

    private final ApplicationEventPublisher publisher;

    public OperationStatusPublisherImpl(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publishStatusForDataSources(String statusMessage) {
        publisher.publishEvent(new DataSourceEvent(statusMessage));
    }

    @Override
    public void publishStatusFor(String dataSource, String statusMessage) {
        DataSourceInfo dataSourceInfo = new DataSourceInfo(dataSource);
        publisher.publishEvent(new DataSourceEvent(dataSourceInfo, statusMessage));
    }

    @Override
    public void publishStatusFor(DataSource dataSource, String statusMessage) {
        publishStatusFor(dataSource.getName(), statusMessage);
    }

    @Override
    public void publishStatusForDataSources(Status statusType, String statusMessage) {
        publisher.publishEvent(new DataSourceEvent(statusType, statusMessage));
    }

    @Override
    public void publishStatusFor(String dataSource, Status statusType, String statusMessage) {
        DataSourceInfo dataSourceInfo = new DataSourceInfo(dataSource);
        publisher.publishEvent(new DataSourceEvent(dataSourceInfo, statusType, statusMessage));
    }

    @Override
    public void publishStatusFor(DataSource dataSource, Status statusType, String statusMessage) {
        publishStatusFor(dataSource.getName(), statusType, statusMessage);
    }

}
