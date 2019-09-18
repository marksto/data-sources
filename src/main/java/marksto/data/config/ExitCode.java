package marksto.data.config;

/**
 * A set of pre-defined application exit codes.
 *
 * @author marksto
 */
@SuppressWarnings("squid:S00115")
public enum ExitCode {

    GoogleSheetsUnavailable(1),
    DataSourcesUninitialized(2);

    private final int status;

    ExitCode(int status) {
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
