# Data Sources
Google Spreadsheets as reactive remote Data Sources in Java.

> **TL;DR**
> 
> This library mainly addresses the two missing parts of the Google Sheets API:
> 1. consistent _data synchronization_ between multiple dependent spreadsheets
> 2. retrieving data as _business entities_ instead of raw cell data
> 
> As a byproduct, the first reactive wrapper around the existing Java API 
> began to emerge.



## The Why

Google Sheets is a great repository for small data. Spreadsheets release business users from the burden of interaction 
with obscure RDBMS systems while providing them with yet more powerful interface for entering and working with tabular 
data, allowing for [illustrative programming](https://martinfowler.com/bliki/IllustrativeProgramming.html) and even 
giving rise to ["code less" movements](https://codingisforlosers.com/) among IT laypeople (and it's totally ok).

Nowadays it seems to be ok to pick Google Spreadsheets for prototypes, internal tools or even real-world apps with short
time-to-market terms. Sheets tend to be more of a database. The major shortcoming of this approach becomes apparent when
your data reaches the boundary of this "small" amount (which is precisely `400,000` cells per sheet for Google Sheets) 
and then finally exceeds it (thanks to the combinatorial explosion of the denormalized data representation).

The usual means, e.g. the built-in function `IMPORTRANGE`, do not help to solve this problem — dependent tables do not 
automatically receive notifications about the need for their dependencies data updates. And even if they received such 
notifications, it is not entirely clear how they would work in case of cascading updates of the dependent tables graph.

Here's where the _Data Sources_ come to the rescue! With this ready-to-go Java library one may keep their tabular data 
and business logic in a familiar Google Spreadsheets format (with built-in formulas and/or custom AppScript functions) 
while [synchronizing dependencies graph](#the-how) automatically via the minimalistic [API](#the-code).

Moreover, you might want to export the data or run [some Java workloads](#usage-examples) with it — here is where the 
built-in JSON Schema-based data mapping facilities come in very handy. The ability to receive from your spreadsheets 
not the raw data (meaningless `List<List<...>>` in Java), but full-fledged _business entities_ (with clear semantics 
and defined structure) takes work with Google Sheets API to a new level of convenience, starting to resemble the ORM 
for a traditional database.



## The How

The essence of the solution is to divide large spreadsheets into a set of smaller interconnected ones that are equipped 
with certain kind of metadata (may be totally invisible for an end user, more on this below). They can stay denormalized
but much less frustrating to work with thanks to narrowing the scope for cascade formulas re-calculations.

[//]: # (TODO: Add an illustration. Diagram #1. Spreadsheets separation process.)

Main concepts in use are:

- _Data Source_ — a source of data for the application
- _data models_ — a set of schemas defining your _business entities_
- _data mapping_ — a prescription for mapping Data Sources data to business entities

[//]: # (TODO: Add an illustration. Diagram #2. Main concepts.)


### Data Source

_Data Source_ description includes it's name, data structure, dependencies, etc. Each one is backed by the corresponding 
Google Spreadsheet which form its data structure with the help of _named value ranges_. One may think of these ranges as 
of columns in the column-oriented databases.

A _business entity_ is composed of a group of similarly-prefixed named value ranges. The prefix is alphanumeric and ends 
with `_`, e.g. `goods_`. In this case a group may consist of `goods_ids`, `goods_names`, `goods_descriptions_full`, etc.
A single Data Source may serve as a data repository for multiple business entities, i.e. a single Google Spreadsheet may
contain more than one group of similarly-prefixed named value ranges.

Data Sources may (and normally _should_) depend on each other's data, a set of shared named value ranges. The dependency
may be of two types: regular and historical. The historical data dependency is best captured in the following example:
imagine you have to predict future costs of _supplies_ by the previously made _orders_ of these _supplies_.

The Data Sources dependency graph may have all possible quirks: detached vertices, loops (when one Data Source depends
on the other which in turn depends on the historical data from the first one), etc. Cascade update may be started from
any particular graph node. Partial synchronization of a pair of Data Sources is supported, still not recommended.

This graph can be built automatically, on a first remote request (for pre-initialized set), or upon the registration of 
a new Data Source.

The metadata that one is required to provide (in a form of named value ranges) for a particular Data Source consists of:

- the `spreadsheet_name`, a unique Data Source identifier (**required**)

- a set of `3` indicators calculated by _boolean_ spreadsheet formulas:
  * `spreadsheet_status_api_ready` — indicates the absence of running re-calculations
  * `spreadsheet_status_data_errors` — indicates data inconsistency (optional)
  * `spreadsheet_status_data_warnings` — indicates data incompleteness (optional)
  
- a set of up to `3` dependency descriptors, e.g. the first one is defined with:
  * `spreadsheet_dependency_1_source` — the dependency identifier
  * `spreadsheet_dependency_1_ranges` — the named ranges of the dependency
  * `spreadsheet_dependency_1_historical` — is it used as a historical data only

[//]: # (TODO: Add examples of such formulas. Better to create a live read-only spreadsheet.)


### Data models

These are simply a set of JSON Schema type definitions of your _business entities_. The "code less" JSON Schema approach 
was chosen as a logical continuation of the idea of using Google Spreadsheets as data repositories with the flexible and 
dynamic data structure. Ideally, you simply should not need to recompile your client code if you add a new data column 
to your business entity.

Here's how a `Design` data model might look like:

```metadata json
{
  "$schema": "http://json-schema.org/draft-04/schema#",
  "$id": "http://terakulta.com/schemas/model/Design.json",
  "title": "Design",
  "description": "A particular 'Tera kulta' design that can be ordered yet customized",
  "definitions": {
    "DesignDescriptions": {
      "$id": "designDescriptions",
      "type": "object",
      "properties": {
        "full": {
          "type": "object",
          "$ref": "Text.json"
        },
        "short": {
          "type": "object",
          "$ref": "Text.json",
          "$comment": "Recommended max length is 64 characters"
        }
      }
    }
  },
  "type": "object",
  "properties": {
    "id": {
      "type": "string"
    },
    "name": {
      "type": "string"
    },
    "ver": {
      "type": "integer",
      "exclusiveMinimum": 0
    },
    "code": {
      "type": "string",
      "pattern": "[A-Z0-9]{3}-[A-Z]{1}"
    },
    "category": {
      "type": "object",
      "$ref": "GoodCategory.json"
    },
    "descriptions": {
      "type": "object",
      "$ref": "#designDescriptions"
    },
    "tags": {
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  },
  "required": [
    "id",
    "name",
    "ver",
    "code",
    "category"
  ]
}
```

Please, find all main JSON data types used in this example, as well as an internal entity (the `DesignDescription`).

**NB:** The supported JSON schema version is `draft-04` which don't support some latest features, e.g. handy `const` 
construct. Still, there's a trivial work-around for this — just use a single-valued `enum` properties instead.

```metadata json
      ...
      "type": "string",
      "enum": [ "?" ]
      ...
```


### Data mapping

This JSON file prescribes how to map Data Sources data (i.e. the corresponding Spreadsheet's named value ranges values) 
into the [data models](#data-models) described above.

Here's how the data mapping for `Design` entities might look like.

```metadata json
{
  "designs": {
    "type": "com.tera.kulta.schemas.model.Design",
    "dataSource": "tk-products",
    "groups": [{
      "key": "goods",
      "skipRows": 1,
      "splitIntoList": [{
        "props": [ "tags" ],
        "regex": ",\\s*"
      }]
    }],
    "mapping": {
      "goods_ids": "id",
      "goods_names": "name",
      "goods_vers": "ver",
      "goods_codes": "code",
      "goods_categories": "category.code",
      "goods_descriptions_full": "descriptions.full.code",
      "goods_descriptions_short": "descriptions.short.code",
      "goods_tags": "tags"
    }
  }
}
```

**NB:** The usual Java Beans properties path syntax is used for mapping (including `.` for nested and `[]` for mapped).
See [Apache BeanUtils](https://commons.apache.org/proper/commons-beanutils/) for more details.

Properties requiring multiple named ranges can be constructed as well with the following syntax:

```metadata json
      ...
      "parts_gems1_size": [ "gems[0].size.length", "gems[0].size.width", "gems[0].size.height", "gems[0].size.diameter" ],
      ...
      
      ...
      "assemblages_platings_2": [ "platings[1].quantity", "platings[1].code", "platings[1].valuation" ],
      ...
```

Please, mind that indexing starts from `0` which is usual for Java.

[//]: # (TODO: Add other mapping rules with details and examples.)



## Using

> Talk is cheap. Show me the code.
>
> &mdash; Linus Torvalds


### Dependency

First, add _Data Sources_ as a dependency in your build/project-management system, for instance with Maven add [JitPack](https://jitpack.io/) 
as a repository and the [latest release](https://github.com/marksto/data-sources/releases) as an artifact in the `pom.xml`:

    ```xml
    <repositories>
        ...
        <repository>
            <id>jitpack.io</id>
            <url>https://jitpack.io</url>
        </repository>
        ...
    </repositories>
    
    ...
    
    <dependencies>
        ...
        <dependency>
            <groupId>com.github.marksto</groupId>
            <artifactId>data-sources</artifactId>
            <version>RELEASE_VERSION</version>
        </dependency>
        ...
    </dependencies>
    ```


### API

The main API is consolidated within these _core services_ (see their JavaDoc for detailed description):

- [DataSourcesService](src/main/java/marksto/data/service/DataSourcesService.java)
- [DataRetrievalService](src/main/java/marksto/data/service/DataRetrievalService.java)
- [GoogleSheetsService](src/main/java/marksto/data/service/GoogleSheetsService.java)

Most of the time the first two are enough for the aforementioned data sources synchronization and mapping functionality.
But there's always a lower-level `GoogleSheetsService` for abstractions to leak.

There is also the thinnest domain model represented by the [DomainType](src/main/java/marksto/data/model/DomainType.java) 
interface and its implementations. With these you wrap your actual domain models to pass them between your client code 
and the core library services. See the data mapping [example](#data-mapping-feature) below.


### Basic Features

The basic feature set consists of operations on Data Sources level: registration, retrieval, and synchronization.

1. Supplement your Spring-based project with the following configuration:

    ```java
    @Import({
        marksto.data.config.ServiceConfiguration.class,
        marksto.data.config.MappingConfiguration.class
    })
    ```

2. Provide the **required** basic configuration properties (see [Configuration](#configuration) below).

   If you configured everything right, the startup logs should look similar to these:
   
   ```
   2019-09-25 03:26:39.604  INFO 43541 --- [           main] m.d.s.impl.RemoteDataSourceInitializer   : Initializing a Data Source for '1ZWM...'
   2019-09-25 03:26:39.704  INFO 43541 --- [hot-timeouter-0] m.d.i.s.impl.GoogleSheetsServiceImpl     : Establishing connection to the remote service...
   2019-09-25 03:26:42.579  INFO 43541 --- [hot-timeouter-0] m.d.i.s.impl.GoogleSheetsServiceImpl     : Connection established successfully
   2019-09-25 03:26:43.090  WARN 43541 --- [ remote-calls-3] m.d.s.impl.DataMappingProviderImpl       : No 'data-mapping.json' file provided, only the default one is used
   2019-09-25 03:26:47.032  INFO 43541 --- [       mapper-0] m.d.service.impl.DataSourcesServiceImpl  : Registering new DataSource: name='tk-products'
   ```
   
   Note that `tk-products` here comes from a dedicated named value range (`spreadsheet_name`) in the provided spreadsheet.
   Find more details on what metadata you need to provide in your [Data Source](#data-source).

3. Now you are good to use Data Sources in your client code! An arbitrary _Spring WebFlux_ controller code for 
Data Sources metadata retrieval (update) and synchronization might look like this:

    ```java
        public static final String SYNC_ALL_ACTION = "syncAll";
        
        ...
        
        private final DataSourcesService dataSourcesService;
        
        ...
        
        @PostMapping(path = "/update", consumes = APPLICATION_JSON_UTF8_VALUE)
        public Flux<DataSource> updateDataSources(@RequestBody DataSource_Update body) {
            if (StringUtils.isEmpty(body.getDataSource())) {
                return dataSourcesService.retrieveDataSources(body.getForceRemote());
            } else {
                return dataSourcesService.retrieveDataSource(body.getName(), body.getForceRemote()).flux();
            }
        }
        
        @PostMapping(path = "/sync", consumes = APPLICATION_JSON_UTF8_VALUE)
        public Mono<Void> synchronizeDataSources(@RequestBody DataSource_Sync body) {    
            if (SYNC_ALL_ACTION.equals(body.getActionType())) {
                return dataSourcesService.synchronizeData();
            } else {
                return dataSourcesService.synchronizeDataBetween(
                        body.getDependency(), body.getDataSource(), body.getAndSubGraph());
            }
        }
    ```


### Data Mapping Feature

In case you want to use the [data mapping](#data-mapping) capabilities of the _Data Sources_ library:

1. Specify the `app.data.mapping.path` configuration property (there have to be a valid JSON file).

2. Introduce your [data models](#data-models) and Google Spreadsheets named value ranges mapping rules for them.
Place models anywhere under the `resources` directory and mapping rules in the specified path.

3. An arbitrary service code that _retrieves remote data_ from registered Data Sources may look like this:

    ```java
        private static final DomainType<Design> DESIGNS = new StaticType<>("designs", Design.class);
        private static final DomainType<Assemblage> ASSEMBLAGES = new StaticType<>("assemblages", Assemblage.class);
        private static final DomainType<Position> PRICE_LIST_POSITIONS = new StaticType<>("positions", Position.class);
    
        private static final DomainType[] PRODUCT_REQUIRED_DOMAIN_TYPES = { DESIGNS, ASSEMBLAGES, PRICE_LIST_POSITIONS };
        
        ...
        
        private final DataRetrievalService dataRetrievalService;
        
        ...
        
        private Mono<List<TypedObjects>> uploadRequiredRemoteData() {
            return dataRetrievalService
                    .getDataFrom(PRODUCT_REQUIRED_DOMAIN_TYPES)
                    .collectList();
        }
    ```

Note the `StaticType` being used to wrap `designs`, `assemblages` and `positions` which are the names of the _business 
entities_ which are used, among other things, as keys in [data mapping](#data-mapping) ruleset.


### Data Sources Events Feature

In case you want to use the `EventReactivePublisher` features, e.g. for streaming operation status events to clients:

[//]: # (TODO: Describe this features in more detail in a dedicated section.)

1. Supply the following configuration as well:

    ```java
    @Import({
        marksto.events.config.EventsConfiguration.class
    })
    ```

2. An arbitrary _Spring WebFlux_ controller code for _events streaming_ might look like this:

    ```java
    private final EventReactivePublisher eventReactivePublisher;
    
    ...
    
    @GetMapping(path = "/status/events", produces = TEXT_EVENT_STREAM_VALUE)
    public Flux<DataSourceEvent> streamStatusEvents() {
        return eventReactivePublisher.on(DataSourceEvent.class);
    }
    ```


### Configuration

The standard `application.properties` are used to configure the core services.

#### GoogleSheetsService

The **required** basic configuration:

```properties
sheets.client.type=SERVICE_ACCOUNT
sheets.client.name=<name of your application>
sheets.client.secret=<from your Service Account as JSON string>

sheets.test-sheet-id=<any spreadsheet ID to check the established connection>
```

These could also be passed as environment variables, e.g. `SHEETS_CLIENT_SECRET = { ... }`.

**NB:** If you choose to do so, you might need to escape the `=` characters in JSON values with a backslash.

Advanced properties with their defaults (for fine tuning):

```properties
sheets.firstConnectionTimeout=90s
sheets.apiRequestsLimitPerSecond=0.75

sheets.expireSpreadsheetsCacheEvery= ##unset##

sheets.copyDataRetriesNum=3
sheets.copyData1stBackoff=5s
sheets.copyDataMaxBackoff=10s

sheets.apiCheckRetriesNum=10
sheets.apiCheck1stBackoff=3s
sheets.apiCheckMaxBackoff=10s
```

Please, find the detailed description of these in [SheetsProperties](src/main/java/marksto/data/config/properties/SheetsProperties.java).

#### DataSourcesService

The **required** basic configuration:

```properties
app.data.sources.sheets-ids=<comma-separated list of spreadsheets IDs to pre-initialise>
app.data.sources.path=<e.g. '/data/meta/data-sources.json'>
```

These could also be passed as environment variables, e.g. `APP_DATA_SOURCES_SHEETS_IDS = ...`.

Advanced properties with their defaults (for fine tuning):

```properties
app.data.sources.default-name-prefix= ##unset##

app.data.sources.autoReInitIn=3s
app.data.sources.reInitRetriesNum=1
```

Please, find the detailed description of these in [DataSourcesProperties](src/main/java/marksto/data/config/properties/DataSourcesProperties.java).

#### DataMappingService

The basic configuration (optional until you want to use [data mapping feature](#data-mapping-feature)):

```properties
app.data.mapping.path=<e.g. '/data/meta/data-mapping.json'>
```

Advanced properties with their defaults (for fine tuning):

```properties
app.data.mapping.thread-pool-size=2
app.data.mapping.expireCacheEvery= ##unset##
```

Please, find the detailed description of these in [DataMappingProperties](src/main/java/marksto/data/config/properties/DataMappingProperties.java).

#### Data providers

Advanced properties with their defaults (for fine tuning):
```properties
app.data.providers.retrieveMetadataRetriesNum=3
app.data.providers.retrieveMetadata1stBackoff=500ms

app.data.providers.retrieveDataStructureRetriesNum=3
app.data.providers.retrieveDataStructure1stBackoff=500ms

app.data.providers.retrieveDataRetriesNum=3
app.data.providers.retrieveData1stBackoff=500ms
```

Please, find the detailed description of these in [DataProvidersProperties](src/main/java/marksto/data/config/properties/DataProvidersProperties.java).


### Caveats

**TBD**

[//]: # (TODO: Add possible caveats, e.g. when named ranges size is not enough for synchronization.)



## Usage Examples

The library was used in developing the solution for the [Tera kulta](https://terakulta.com) web catalogue. This backend 
service initializes `8` Data Sources upon the application startup (the exemplary startup [logs](./doc/app-startup.log)).

Here's how it looks like in a dedicated Spring WebFlux-based _Admin UI_.

![Tk Admin - Data Init](./doc/Tk%20Admin%20-%20Data%20Init.png "Tera kulta Admin UI — Data Initialization example")

And here's how actually _slow_ they get fully synchronized. Of course, this is due to the Google Spreadsheets themselves
which are well-known for their catastrophic slowdown on fairly large data sets. Just imagine how terrible and error-prone 
would it be to synchronize all these spreadsheets manually! With Data Sources it's just a single method call!

![Tk Admin - Data Sync](./doc/Tk%20Admin%20-%20Data%20Sync.png "Tera kulta Admin UI — Data Synchronization example")

In **Tera kulta** we use Data Sources to compose a _localized price-list_ of more than 3,500 stock items from hundreds
of thousands of pre-calculated cell values synchronized between spreadsheets, which then get converted into Tilda CSV
format and split into semi-equal parts, which can then be easily uploaded to the web site's product catalog.

[//]: # (TODO: Add an illustration. Diagram #3. System overview.)



## Contributing

### Building

To build _Data Sources_ locally you'll need [Java 12 or later](https://adoptopenjdk.net/) and [Maven 3](http://maven.apache.org).

While in the project root as a working directory, build and install the project with the following command:

```bash
mvn clean install
```

You will get a fresh `target/data-sources-{version}.jar` file within the project, as well as its copy installed 
in your local Maven repository under the following path:

```bash
$M2_REPO/name/marksto/data-sources/{version}/data-sources-{version}.jar
```


### What's inside

Under the hood Data Sources leverage:

- **Google Sheets API client** with OAuth 2.0 credentials of the [service account](https://cloud.google.com/docs/authentication/production)
- [JSON Schema](https://json-schema.org/) for Data Sources and Data Mapping definition (with support for validation)
- [Manifold](https://github.com/manifold-systems/manifold) as the main data modeling framework with support for dynamic 
  type reloading
- [Kryo](https://github.com/EsotericSoftware/kryo) and [BeanUtils](https://commons.apache.org/proper/commons-beanutils/) 
  for responses deserialization via the data mapping rules
- [Project Reactor](https://projectreactor.io/) as a Reactive Streams API for data flows

Make sure you are familiar with these, especially the [Reactor's documentation](https://projectreactor.io/docs/core/release/reference/index.html).

**NB:** You might want to install the (paid) _Manifold_ IntelliJ IDEA plugin for better meta-programming experience.


### Future Development

- Add new features such as _Dynamic Domain Types_ support for reloading data models in runtime
- Make Sheets API use an asynchronous non-blocking web client (Spring's `WebClient`)
- Upload/download data sources and data mapping to/from the Google Drive
- Describe data mapping rules in more details with examples
- Create an environment for live demonstration
- Add more unit tests ¯\\\_(ツ)\_/¯



## License

Copyright © 2019 Mark Sto. See the [LICENSE](./LICENSE) file for license rights and limitations.
