package marksto.data.events;

import marksto.data.events.dto.DataSourceInfo;
import marksto.events.OperationStatusEvent;

public class DataSourceEvent extends OperationStatusEvent {

    private static final DataSourceInfo ALL = new DataSourceInfo("all");

    /**
     * Create a new {@link DataSourceEvent}.
     *
     * @param statusMessage the status message of a certain operation
     */
    public DataSourceEvent(String statusMessage) {
        super(ALL, statusMessage);
    }

    /**
     * Create a new {@link DataSourceEvent}.
     *
     * @param statusMessage the status message of a certain operation
     * @param statusType the operation status (start, in progress or termination)
     */
    public DataSourceEvent(Status statusType, String statusMessage) {
        super(ALL, statusType, statusMessage);
    }

    /**
     * Create a new {@link DataSourceEvent}.
     *
     * @param dataSource the source {@link DataSourceInfo} object
     * @param statusMessage the status message of a certain operation
     */
    public DataSourceEvent(DataSourceInfo dataSource, String statusMessage) {
        super(dataSource, statusMessage);
    }

    /**
     * Create a new {@link DataSourceEvent}.
     *
     * @param dataSource the source {@link DataSourceInfo} object
     * @param statusMessage the status message of a certain operation
     * @param statusType the operation status (start, in progress or termination)
     */
    public DataSourceEvent(DataSourceInfo dataSource, Status statusType, String statusMessage) {
        super(dataSource, statusType, statusMessage);
    }

    @Override
    public DataSourceInfo getSource() {
        return (DataSourceInfo) super.getSource();
    }
}
