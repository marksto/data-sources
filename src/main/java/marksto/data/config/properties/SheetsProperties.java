package marksto.data.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.security.core.CredentialsContainer;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@ConfigurationProperties("sheets")
public class SheetsProperties {

    public static class Client implements CredentialsContainer {

        private ClientType type;
        private String name;
        private String secret;

        public ClientType getType() {
            return type;
        }

        public void setType(ClientType type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        @Override
        public void eraseCredentials() {
            secret = null;
        }

        public enum ClientType {
            /*
             * The one that accesses the User private data and thus needs an explicit consent (via the Auth Code flow).
             * NOTE: We won't use it for now, until there are specific requirements for such a quirky functionality.
             */
            REGULAR,

            /*
             * The Google-specific type of Client tied to a Google Account itself and dealing solely with its data.
             */
            SERVICE_ACCOUNT
        }
    }

    /**
     * OAuth 2.0 client description.
     */
    @NestedConfigurationProperty
    private Client client;

    /**
     * The <em>test spreadsheet</em> ID to check the established connection.<br/>
     * It may be one of the spreadsheets used by an application.
     */
    private String testSheetId;

    /**
     * A timeout of the first <em>Google Spreadsheets API</em> call to test
     * the established connection.<br/>The default value is {@code 90} seconds.
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration firstConnectionTimeout = Duration.ofSeconds(90);

    /**
     * The "per second" limit of <em>Google Spreadsheets API</em> requests.<br/>
     * The default value of {@code 0.75} results in good stability and low
     * requests rejection rate.
     */
    private double apiRequestsLimitPerSecond = 0.75;

    /**
     * Sets the expiration (eviction) period for cached response entries of the
     * {@link com.google.api.services.sheets.v4.model.Spreadsheet} type.<br/>
     * The default is to never expire the cached spreadsheets entries.
     */
    @DurationUnit(ChronoUnit.HOURS)
    private Duration expireSpreadsheetsCacheEvery;

    /**
     * The configuration of a "retry with exponential backoff" strategy for the
     * <em>copy data between spreadsheets</em> operation.<br/>
     * The defaults are: {@code 3} retries with an exponential delay
     * of {@code 5} to {@code 10} seconds each.
     */
    private long copyDataRetriesNum = 3;
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration copyData1stBackoff = Duration.ofSeconds(5);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration copyDataMaxBackoff = Duration.ofSeconds(10);

    /**
     * The configuration of a "retry with exponential backoff" strategy for the
     * <em>API readiness status check</em> operation.<br/>
     * The defaults are: {@code 10} retries with an exponential delay
     * of {@code 3} to {@code 10} seconds each.
     */
    private long apiCheckRetriesNum = 10;
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration apiCheck1stBackoff = Duration.ofSeconds(3);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration apiCheckMaxBackoff = Duration.ofSeconds(10);

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public String getTestSheetId() {
        return testSheetId;
    }

    public void setTestSheetId(String testSheetId) {
        this.testSheetId = testSheetId;
    }

    public Duration getFirstConnectionTimeout() {
        return firstConnectionTimeout;
    }

    public void setFirstConnectionTimeout(Duration firstConnectionTimeout) {
        this.firstConnectionTimeout = firstConnectionTimeout;
    }

    public double getApiRequestsLimitPerSecond() {
        return apiRequestsLimitPerSecond;
    }

    public void setApiRequestsLimitPerSecond(double apiRequestsLimitPerSecond) {
        this.apiRequestsLimitPerSecond = apiRequestsLimitPerSecond;
    }

    public Duration getExpireSpreadsheetsCacheEvery() {
        return expireSpreadsheetsCacheEvery;
    }

    public void setExpireSpreadsheetsCacheEvery(Duration expireSpreadsheetsCacheEvery) {
        this.expireSpreadsheetsCacheEvery = expireSpreadsheetsCacheEvery;
    }

    public long getCopyDataRetriesNum() {
        return copyDataRetriesNum;
    }

    public void setCopyDataRetriesNum(long copyDataRetriesNum) {
        this.copyDataRetriesNum = copyDataRetriesNum;
    }

    public Duration getCopyData1stBackoff() {
        return copyData1stBackoff;
    }

    public void setCopyData1stBackoff(Duration copyData1stBackoff) {
        this.copyData1stBackoff = copyData1stBackoff;
    }

    public Duration getCopyDataMaxBackoff() {
        return copyDataMaxBackoff;
    }

    public void setCopyDataMaxBackoff(Duration copyDataMaxBackoff) {
        this.copyDataMaxBackoff = copyDataMaxBackoff;
    }

    public long getApiCheckRetriesNum() {
        return apiCheckRetriesNum;
    }

    public void setApiCheckRetriesNum(long apiCheckRetriesNum) {
        this.apiCheckRetriesNum = apiCheckRetriesNum;
    }

    public Duration getApiCheck1stBackoff() {
        return apiCheck1stBackoff;
    }

    public void setApiCheck1stBackoff(Duration apiCheck1stBackoff) {
        this.apiCheck1stBackoff = apiCheck1stBackoff;
    }

    public Duration getApiCheckMaxBackoff() {
        return apiCheckMaxBackoff;
    }

    public void setApiCheckMaxBackoff(Duration apiCheckMaxBackoff) {
        this.apiCheckMaxBackoff = apiCheckMaxBackoff;
    }

}
