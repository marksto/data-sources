package marksto.data.mapping;

import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import marksto.data.schemas.DataMapping;
import manifold.api.json.JsonStructureType;
import marksto.data.mapping.dto.TypedObjects;
import marksto.data.model.DomainType;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static marksto.data.schemas.DataMapping.SplitIntoListConfig;
import static marksto.data.schemas.DataMapping.TypeEntry;
import static marksto.data.config.KryoConfiguration.KryoThreadLocal;
import static marksto.data.mapping.util.ManifoldUtils.getPropertyType;
import static marksto.data.mapping.util.ManifoldUtils.retrieveTypeInformation;
import static marksto.common.util.FunctionalUtils.zipped;

@Service
public class ValueRangesMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ValueRangesMapper.class);

    public static final class Context<T> {

        private final DomainType<T> domainType;
        private final List<String> ranges;
        private final List<ValueRange> response;
        private final DataMapping mapping;

        public Context(DomainType<T> domainType,
                       List<String> ranges,
                       List<ValueRange> response,
                       DataMapping mapping) {
            Preconditions.checkArgument(ranges.size() == response.size(),
                "Ranges names and values lists are of different sizes");
            this.ranges = Collections.unmodifiableList(ranges);
            this.response = Collections.unmodifiableList(response);

            Preconditions.checkArgument(mapping.get(domainType.getName()) != null,
                "Invalid Domain Type passed ('%s')", domainType);
            this.domainType = domainType;
            this.mapping = mapping;
        }

        DomainType<T> getDomainType() {
            return domainType;
        }

        List<String> getRanges() {
            return ranges;
        }

        List<ValueRange> getResponse() {
            return response;
        }

        DataMapping getMapping() {
            return mapping;
        }
    }

    // -------------------------------------------------------------------------

    private final KryoThreadLocal kryoThreadLocal;

    public ValueRangesMapper(KryoThreadLocal kryoThreadLocal) {
        this.kryoThreadLocal = kryoThreadLocal;
    }

    private MappingSchemaBean initNewMappingSchemaBean() {
        return new MappingSchemaBean(kryoThreadLocal.get());
    }

    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public <T> TypedObjects<T> map(final Context<T> context) {
        final DomainType<T> domainType = context.getDomainType();
        LOG.debug("Mapping the ValueRange response for '{}'", domainType);

        final TypeEntry typeEntry = (TypeEntry) context.getMapping().get(domainType.getName());
        Preconditions.checkState(typeEntry != null,
            "No mapping entry provided for the '%s'", domainType.getName());
        final JsonStructureType jsonType = retrieveTypeInformation(typeEntry.getType());

        if (CollectionUtils.isEmpty(context.getResponse())) {
            return new TypedObjects<>(domainType, emptyList());
        }

        // 1. Construct the dynamic schema Java Bean
        LOG.trace("Constructing the dynamic schema Java Bean for '{}'...", typeEntry.getType());
        final MappingSchema schema = initNewMappingSchemaBean();
        final Map<String, Object> typeMapping = (Map) typeEntry.getMapping();
        for (Map.Entry<String, Object> mappingEntry : typeMapping.entrySet()) {
            processMappingEntry(mappingEntry,
                new PropertyConstructor(schema, jsonType));
        }

        // 2. Prepares the information necessary for instantiation
        LOG.trace("Preparing the information necessary for instantiation...");
        final Map<String, ValueRange> valueRanges
            = zipped(context.getRanges(), context.getResponse())
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        final Map<String, ValueRange> typeValueRanges = valueRanges.entrySet().stream()
                .filter(e -> typeMapping.containsKey(e.getKey()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

        // 3. Instantiate a set of typed objects using this schema
        final List<T> objectsOfType
            = instantiateObjects(typeEntry, schema, typeValueRanges);
        return new TypedObjects<>(domainType, objectsOfType);
    }

    // -------------------------------------------------------------------------

    private <T> List<T> instantiateObjects(TypeEntry typeEntry, MappingSchema schema,
                                           Map<String, ValueRange> typeValueRanges) {
        LOG.trace("Instantiating a set of type objects using this schema...");

        // NOTE: An actual number of entities lessens in case of a non-zero 'skipRows'.
        final int maxNumberOfTypedEntities = getMaxNumberOfTypeEntities(typeValueRanges);

        final List<MappingSchema> entities = Lists.newArrayListWithCapacity(maxNumberOfTypedEntities);
        try {
            for (int i = 0; i < maxNumberOfTypedEntities; i++) {
                entities.add(schema.createCopy());
            }
        } catch (Exception e) {
            LOG.error("Was not able to instantiate objects of type '{}'", typeEntry.getType());
        }
        LOG.trace("Instantiated objects total number={}", entities.size());

        if (maxNumberOfTypedEntities > 0) {
            for (Map.Entry<String, ValueRange> typeValueRange : typeValueRanges.entrySet()) {
                processTypeValueRange(typeEntry, typeValueRange, entities);
            }
        }
        return convertToType(entities);
    }

    private int getMaxNumberOfTypeEntities(Map<String, ValueRange> typeValueRanges) {
        int maxNumberOfTypeEntities = typeValueRanges.values().stream()
            .mapToInt(valueRange -> {
                List valuesList = valueRange.getValues();
                return valuesList == null ? 0 : valuesList.size();
            })
            .max().orElse(0);
        LOG.trace("Max number of Type entities={}", maxNumberOfTypeEntities);
        return maxNumberOfTypeEntities;
    }

    @SuppressWarnings("unchecked")
    private void processTypeValueRange(TypeEntry typeEntry,
                                       Map.Entry<String, ValueRange> typeValueRange,
                                       List<MappingSchema> entities) {
        final String valueRangeKey = typeValueRange.getKey();
        final ValueRange valueRange = typeValueRange.getValue();
        final Object mappingEntryValue = typeEntry.getMapping().get(valueRangeKey);

        final Map<String, Object> groupPrefs = (Map) typeEntry.getGroups().stream()
            .filter(groupsItem -> valueRangeKey.startsWith(groupsItem.getKey()))
            .findFirst().orElse(null);

        processMappingEntry(Map.entry(valueRangeKey, mappingEntryValue),
            new ValueRangeProcessor(entities, valueRange, groupPrefs));
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> convertToType(List<MappingSchema> entities) {
        return entities.stream()
            .map(MappingSchema::asMap)
            .filter(Objects::nonNull)
            .map(map -> (T) map) // Manifold specifics
            .collect(toList());
    }

    // -------------------------------------------------------------------------

    private void processMappingEntry(Map.Entry<String, Object> mappingEntry,
                                     MappingEntryProcessor processor) {
        Object mappingEntryValue = mappingEntry.getValue();
        if (mappingEntryValue instanceof Iterable) {
            int i = 0;
            for (Object mappingSubEntryValue : (Iterable) mappingEntryValue) {
                processor.accept((String) mappingSubEntryValue, i++);
            }
        } else {
            processor.accept((String) mappingEntryValue, 0);
        }
    }

    interface MappingEntryProcessor extends BiConsumer<String, Integer> {
        void accept(String mappingEntryValue, Integer index);
    }

    // -------------------------------------------------------------------------

    @SuppressWarnings("RegExpRedundantEscape")
    private static final Pattern INDEXED_PATTERN = Pattern.compile("\\w+\\[(\\d+)\\]");

    private class PropertyConstructor implements MappingEntryProcessor {

        private final MappingSchema schema;
        private final JsonStructureType jsonType;

        PropertyConstructor(MappingSchema schema, JsonStructureType jsonType) {
            this.schema = schema;
            this.jsonType = jsonType;
        }

        @Override
        public void accept(String mappingEntryValue, Integer index) {
            String[] parts = mappingEntryValue.split("\\.");
            MappingSchema levelSchema = schema;

            List<String> unindexedNames = new LinkedList<>();

            for (int i = 0; i < parts.length; i++) {
                Matcher indexedMatcher = INDEXED_PATTERN.matcher(parts[i]);
                boolean nonLeaf = i < parts.length - 1;

                if (indexedMatcher.matches()) {
                    String propName = parts[i].substring(0, parts[i].indexOf('['));
                    unindexedNames.add(propName);

                    int internalIdx = Integer.parseInt(indexedMatcher.group(1));
                    levelSchema = processIndexedProperty(propName, nonLeaf, internalIdx, levelSchema);
                } else {
                    String propName = parts[i];
                    unindexedNames.add(propName);

                    levelSchema = processRegularProperty(propName, nonLeaf, unindexedNames, levelSchema);
                }
            }
        }

        private MappingSchema processRegularProperty(String propName, boolean nonLeaf,
                                                     List<String> unindexedNames,
                                                     MappingSchema levelSchema) {
            if (nonLeaf) {
                MappingSchema mappedPropertyContainer;
                if (!levelSchema.exists(propName)) {
                    mappedPropertyContainer = initNewMappingSchemaBean();
                    levelSchema.set(propName, mappedPropertyContainer);
                } else {
                    mappedPropertyContainer = (MappingSchema) levelSchema.get(propName);
                }
                return mappedPropertyContainer;
            } else {
                // NOTE: Method 'add' auto-tests property for existence.
                levelSchema.add(propName, getPropertyType(jsonType, unindexedNames));
            }
            return levelSchema; // by default, not a business case
        }

        @SuppressWarnings("unchecked")
        private MappingSchema processIndexedProperty(String propName, boolean nonLeaf, int idx,
                                                     MappingSchema levelSchema) {
            ArrayList indexedPropertyContainer;
            if (!levelSchema.exists(propName)) {
                indexedPropertyContainer = new ArrayList();
                levelSchema.set(propName, indexedPropertyContainer);
            } else {
                indexedPropertyContainer = (ArrayList) levelSchema.get(propName);
            }

            indexedPropertyContainer.ensureCapacity(idx + 1);
            Object valueOnIdx;
            try {
                valueOnIdx = indexedPropertyContainer.get(idx);
            } catch (IndexOutOfBoundsException oob) {
                valueOnIdx = null;
            }

            if (nonLeaf) {
                MappingSchema mappedPropertyContainer;
                if (valueOnIdx == null) {
                    mappedPropertyContainer = initNewMappingSchemaBean();
                    indexedPropertyContainer.add(idx, mappedPropertyContainer);
                } else {
                    mappedPropertyContainer
                        = (MappingSchema) indexedPropertyContainer.get(idx);
                }
                return mappedPropertyContainer;
            }
            return levelSchema; // by default, not a business case
        }
    }

    private class ValueRangeProcessor implements MappingEntryProcessor {

        private final List<MappingSchema> objectsOfType;
        private final ValueRange valueRange;

        private final int skipRows;
        private final List<String> horizontal;
        private final List<String> keepAsList;
        private final List<SplitIntoListConfig> splitIntoList;

        @SuppressWarnings("unchecked")
        ValueRangeProcessor(List<MappingSchema> objectsOfType, ValueRange valueRange,
                            Map<String, Object> groupPrefs) {
            this.objectsOfType = objectsOfType;
            this.valueRange = valueRange;

            this.skipRows = (int) getPreference(groupPrefs, "skipRows", 0);
            this.horizontal = (List<String>) getPreference(groupPrefs, "horizontal");
            this.keepAsList = (List<String>) getPreference(groupPrefs, "keepAsList");
            this.splitIntoList = (List<SplitIntoListConfig>) getPreference(groupPrefs, "splitIntoList", emptyList());
        }

        private Object getPreference(Map<String, Object> groupPrefs, String key) {
            return getPreference(groupPrefs, key, null);
        }
        private Object getPreference(Map<String, Object> groupPrefs, String key, Object defaultValue) {
            if (groupPrefs == null || groupPrefs.get(key) == null) {
                return defaultValue;
            } else {
                return groupPrefs.get(key);
            }
        }

        @Override
        public void accept(String mappingEntryValue, Integer index) {
            List<List<Object>> values = valueRange.getValues();
            if (CollectionUtils.isEmpty(values)) {
                return; // skip empty value ranges
            }

            if (horizontal != null && horizontal.contains(mappingEntryValue)) {
                processHorizontalValueRange(mappingEntryValue, index, values);
            } else {
                processVerticalValueRange(mappingEntryValue, index, values);
            }
        }

        private void processVerticalValueRange(String mappingEntryValue,
                                               Integer index, List<List<Object>> values) {
            Iterator<MappingSchema> objIterator = objectsOfType.iterator();
            for (int i = skipRows; i < values.size(); i++) {
                MappingSchema object = objIterator.next();
                List<Object> valueList = values.get(i);
                if (!valueList.isEmpty() && index < valueList.size()) {
                    setPropertyValue(mappingEntryValue, object, valueList.get(index));
                }
            }
        }

        private void processHorizontalValueRange(String mappingEntryValue,
                                                 Integer index, List<List<Object>> values) {
            if (index < values.size()) {
                List<Object> valueList = values.get(index).stream()
                        .sequential() /* just in case */ .skip(skipRows)
                        .filter(obj -> !Objects.toString(obj).isEmpty())
                        .collect(toList());

                MappingSchema object = objectsOfType.get(index);
                if (valueList.size() == 1 && (keepAsList == null
                        || !keepAsList.contains(mappingEntryValue))) {
                    setPropertyValue(mappingEntryValue, object, valueList.get(0));
                } else {
                    setPropertyValue(mappingEntryValue, object, valueList);
                }
            }
        }

        @SuppressWarnings("Convert2MethodRef") // otherwise, Manifold will fail with class cast errors
        private void setPropertyValue(String mappingEntryValue, MappingSchema object, Object value) {
            if (value instanceof String) {
                Optional<String> valueSeparator = splitIntoList.stream()
                    .filter(splitConfig -> splitConfig.getProps().contains(mappingEntryValue))
                    .map(splitConfig -> splitConfig.getRegex())
                    .findFirst();

                if (valueSeparator.isPresent()) {
                    value = Arrays.asList(((String) value).split(valueSeparator.get()));
                }
            }
            object.set(mappingEntryValue, value);
        }
    }

}
