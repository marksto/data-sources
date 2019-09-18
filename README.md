# Data Sources
Google Spreadsheets as reactive remote Data Sources in Java.


## The Why
Google Sheets is a great repository for small data. Spreadsheets empower business users with the means of 
[Illustrative Programming](https://martinfowler.com/bliki/IllustrativeProgramming.html), allow not to stall 
with obscure RDBMS interfaces and even give rise to ["code less" movements](https://codingisforlosers.com/).

The major shortcoming of this approach becomes apparent when your data reaches the boundary of this "small" amount 
(which is precisely `400,000` cells per sheet for Google Sheets) and then exceeds it.

The usual means, e.g. the built-in function `IMPORTRANGE`, do not help to solve this problem — dependent tables do not 
automatically receive notifications about the need to update their dependencies data. And even if they received such 
notifications, it is not entirely clear how they would work in case of cascading updates of the dependent tables graph.

Here's where the _Data Sources_ come to the rescue!

With this ready-to-go Java library one may keep their data and business logic in a familiar Google Spreadsheets format 
while synchronizing dependencies graph automatically via a minimalistic [API](#the-code). Moreover, one might want to 
export the data or run some Java workloads with it — here's where the built-in JSON Schema-based mapping facilities 
come in very handy.


## The How
The essence of the solution is to divide large spreadsheets into a group of smaller interconnected ones that are equipped 
with certain kind of metadata (may be totally invisible for an end user, more on this below).

Main concepts used are:

- _Data Source_ — a source of data for the application
- _data models_ — a set of schemas defining your _business entities_
- _data mapping_ — a prescription for mapping Data Sources data to business entities

### Data Source
_Data Source_ description includes it's name, data structure, dependencies, etc. Each one is backed by the corresponding 
Google Spreadsheet which form it's data structure with the help of _named value ranges_. One may think of these as of 
columns of data in the column-oriented NoSQL database.

A _business entity_ is composed of a group of similarly-prefixed named value ranges. The prefix is alphanumeric and ends 
with `_`, e.g. `goods_`. In this case a group might consist of `goods_ids`, `goods_names`, `goods_descriptions_full`, etc.
A single Data Source may serve as a data warehouse for multiple business entities, i.e. a single Google Spreadsheet may
contain more than one group of similarly-prefixed named value ranges.

Data Sources may (and normally _should_) depend on each other's data: a set of shared _named value ranges_.

The Data Sources dependency graph may have all possible quirks: detached vertices, loops (when one Data Source depends
on the other which in turn depends on the historical data from the first one), etc. Cascade update may be started from
any particular graph node. Partial synchronization of a pair of Data Sources is supported, still not recommended.

Get built automatically, on a first remote request (for pre-initialized set) or upon an addition of a new Data Source.

The metadata that one needs to provide for a particular Data Source looks consists of:

- the `spreadsheet_name`, a unique Data Source identifier
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

These are simply a set of JSON Schema type definitions of your _business entities_. Here's how a `Design` data model
might look like:

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

And here's how the corresponding mapping for `Design` entities might look like.

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
      "assemblages_platings_2": [ "platings[1].quantity", "platings[1].unicode", "platings[1].valuation" ],
      ...
```

Please, mind that indexing starts from `0` which is usual for Java.

[//]: # (TODO: Add other mapping rules with details and examples.)


## The Code

> Talk is cheap. Show me the code.
>
> &mdash; Linus Torvalds

### API

The main API is consolidated within the core services:

- [GoogleSheetsService](src/main/java/marksto/data/service/GoogleSheetsService)
- [DataSourcesService](src/main/java/marksto/data/service/DataSourcesService)
- [DataRetrievalService](src/main/java/marksto/data/service/DataRetrievalService)

### Client code

An arbitrary _Spring WebFlux_ controller method for Data Sources metadata retrieval might look like this:

```java
    @PostMapping(path = "/update", consumes = APPLICATION_JSON_UTF8_VALUE)
    public Flux<DataSource> updateDataSources(@RequestBody DataSource_Update body) {
        if (StringUtils.isEmpty(data.getDataSource())) {
            return dataSourcesService.retrieveDataSources(body.getForceRemote());
        } else {
            return dataSourcesService.retrieveDataSource(body.getDataSource(), body.getForceRemote()).flux();
        }
    }
```

At the same time, the Data Sources remote data retrieval might look like this:

```java
    private static final DomainType<Design> DESIGNS = new StaticType<>("designs", Design.class);
    private static final DomainType<Assemblage> ASSEMBLAGES = new StaticType<>("assemblages", Assemblage.class);
    private static final DomainType<Position> PRICE_LIST_POSITIONS = new StaticType<>("positions", Position.class);

    private static final DomainType[] PRODUCT_REQUIRED_DOMAIN_TYPES = { DESIGNS, ASSEMBLAGES, PRICE_LIST_POSITIONS };

    private Mono<List<TypedObjects>> uploadRequiredRemoteData() {
        return dataRetrievalService
                .getDataFrom(PRODUCT_REQUIRED_DOMAIN_TYPES)
                .collectList();
    }
```

### What's inside

Under the hood Data Sources leverage:

- **Google Sheets API client** with OAuth 2.0 credentials of the [service account](https://cloud.google.com/docs/authentication/production)
- [JSON Schema](https://json-schema.org/) for Data Sources and Mapping definition and validation
- [Manifold](https://github.com/manifold-systems/manifold) and [Kryo](https://github.com/EsotericSoftware/kryo) for responses 
deserialization into Java objects
- [Project Reactor](https://projectreactor.io/) as a Reactive Streams API for data flows


## Configuration

The standard `application.properties` are used to configure the core services.

### GoogleSheetsService

The basic configuration:

```properties
sheets.client.type=SERVICE_ACCOUNT
sheets.client.name=<name of your application>
sheets.client.secret=<from your Service Account as JSON string>

sheets.test-sheet-id=<any spreadsheet ID to check the established connection>
```

These could also be passed as environment variables, e.g. `SHEETS_CLIENT_SECRET = { ... }`.
**NB:** If you choose to do so, don't forget to escape the `=` characters with a backslash.

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

### DataSourcesService

The basic configuration:

```properties
app.data.sources.sheets-ids=<comma-separated list of spreadsheets IDs to pre-initialise>
app.data.sources.path=<e.g. '/data/meta/data-sources.json'>
```

These could also be passed as environment variables, e.g. `APP_DATA_SOURCES_SHEETS_IDS = ...`.

Advanced properties with their defaults (for fine tuning):

```properties
app.data.sources.autoReInitIn=3s
app.data.sources.reInitRetriesNum=1
```

Please, find the detailed description of these in [DataSourcesProperties](src/main/java/marksto/data/config/properties/DataSourcesProperties.java).

### DataMappingService

The basic configuration:

```properties
app.data.mapping.path=<e.g. '/data/meta/data-mapping.json'>
app.data.mapping.expireCacheEvery= ##unset##
```

Please, find the detailed description of these in [DataMappingProperties](src/main/java/marksto/data/config/properties/DataMappingProperties.java).

### Data providers

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


## Usage Examples

The solution was used when creating the Admin application for [Tera kulta](https://terakulta.com) web site. This backend 
service initializes `8` Data Sources upon the application startup (the exemplary startup [logs](./doc/app-startup.log)).

Here's how fast 

![Read from file](./doc/Tk%20Admin%20-%20Data%20Init.png "Tera kula Admin UI — Data Initialization example")

And here's how actually _slow_ they get fully synchronized! Of course, this is due to the Google Spreadsheets themselves
which are well-known for their catastrophic slowdown on fairly large data sets. Just imagine how terrible and error-prone 
would it be to synchronize all these spreadsheets manually! With Data Sources it's just a single method call!

![Read from file](./doc/Tk%20Admin%20-%20Data%20Sync.png "Tera kula Admin UI — Data Synchronization example")


## Caveats

**TBD**

[//]: # (TODO: Add possible caveats, e.g. when named ranges size is not enough for synchronization.)


## Future Development
- Switch to a fully asynchronous web client, e.g. Spring's `WebClient`
- Describe mapping rules in more details with examples
- Create an environment for live demonstration
- Add more tests ¯\_(ツ)_/¯

## License

Copyright (c) 2019 Mark Sto. See the [LICENSE](./LICENSE) file for license rights and limitations.
