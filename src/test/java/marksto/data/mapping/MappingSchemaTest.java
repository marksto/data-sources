package marksto.data.mapping;

import com.esotericsoftware.kryo.Kryo;
import com.google.common.collect.Lists;
import marksto.data.config.KryoConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.AbstractMap.SimpleEntry;
import static java.util.stream.Collectors.toMap;
import static org.junit.Assert.*;

@RunWith(SpringRunner.class)
@Import({KryoConfiguration.class})
@SuppressWarnings("squid:S00100")
public class MappingSchemaTest {

    public static class Entry {

        private String val;
        private int intVal;

        public Entry() {}
        public Entry(String val) {
            this.val = val;
        }
        public Entry(int intVal) {
            this.intVal = intVal;
        }

        public String getVal() {
            return val;
        }

        public void setVal(String val) {
            this.val = val;
        }

        public int getIntVal() {
            return intVal;
        }

        public void setIntVal(int intVal) {
            this.intVal = intVal;
        }
    }

    @Autowired
    private Kryo kryo;

    // -------------------------------------------------------------------------

    @Test
    public void test_existenceCheck() {
        // prepare
        MappingSchema schema = new MappingSchemaBean(kryo);

        // test
        boolean checkResult1 = schema.exists("a");

        schema.get("b"); // ignore — the result is 'null'
        boolean checkResult2 = schema.exists("b");

        // check
        assertFalse(checkResult1);
        assertFalse(checkResult2);
    }

    @Test
    public void test_setProperty_simple() {
        // prepare
        MappingSchema schema = new MappingSchemaBean(kryo);

        schema.set("simple_1", "a");
        schema.set("simple_2", 100);

        Object newPropValue = new Object();

        // test
        schema.set("simple_1", "b");
        schema.set("simple_2", 200);
        schema.set("simple_3", newPropValue);

        // check
        assertEquals("b", schema.get("simple_1"));
        assertEquals(200, schema.get("simple_2"));
        assertEquals(newPropValue, schema.get("simple_3"));
    }

    @Test
    public void test_setProperty_indexedSimple() {
        // prepare
        MappingSchema schema = new MappingSchemaBean(kryo);

        schema.set("indexed_simple", Lists.newArrayList("a", "b", "c"));

        // test
        schema.set("indexed_simple", 0, "c");
        schema.set("indexed_simple[2]", "a");
        schema.set("indexed_simple[3]", "!"); // new entry

        // check
        assertEquals("c", ((List) schema.get("indexed_simple")).get(0));
        assertEquals("b", ((List) schema.get("indexed_simple")).get(1));
        assertEquals("a", ((List) schema.get("indexed_simple")).get(2));
        assertEquals("!", ((List) schema.get("indexed_simple")).get(3));
    }

    @Test
    public void test_setProperty_indexedComplex() {
        // prepare
        MappingSchema schema = new MappingSchemaBean(kryo);

        Entry entry1 = new Entry("1");
        Entry entry2 = new Entry(2);
        Entry entry3 = new Entry();

        schema.set("indexed_complex", Lists.newArrayList(entry1, entry2, entry3));

        Object newOne = new Entry();

        // test
        schema.set("indexed_complex", 0, entry3);
        schema.set("indexed_complex[0].val", "3!");
        schema.set("indexed_complex[0].intVal", 3);
        schema.set("indexed_complex[2]", entry1);
        schema.set("indexed_complex[3]", newOne);

        // check
        assertEquals(entry3, ((List) schema.get("indexed_complex")).get(0));
        assertEquals("3!", ((Entry) ((List) schema.get("indexed_complex")).get(0)).getVal());
        assertEquals(3, ((Entry) ((List) schema.get("indexed_complex")).get(0)).getIntVal());
        assertEquals(entry2, ((List) schema.get("indexed_complex")).get(1));
        assertEquals(entry1, ((List) schema.get("indexed_complex")).get(2));
        assertEquals(newOne, ((List) schema.get("indexed_complex")).get(3));
    }

    @Test
    public void test_setProperty_mappedComplex() {
        // prepare
        MappingSchema schema;

        Entry entry1 = new Entry("1");
        Entry entry2 = new Entry(2);
        Entry entry3 = new Entry();

        schema = createMappingSchema_mappedComplex(entry1, entry2, entry3);

        Object newOne = new Entry();

        // test
        schema.set("mapped_complex", "a", entry2);
        schema.set("mapped_complex(b)", entry3);
        schema.set("mapped_complex.c", entry1);
        schema.set("mapped_complex.c.val", "1!");
        schema.set("mapped_complex.c.intVal", 1);
        schema.set("mapped_complex.d", newOne);

        // check
        assertEquals(entry2, ((Map) schema.get("mapped_complex")).get("a"));
        assertEquals(entry3, ((Map) schema.get("mapped_complex")).get("b"));
        assertEquals(entry1, ((Map) schema.get("mapped_complex")).get("c"));
        assertEquals("1!", ((Entry) ((Map) schema.get("mapped_complex")).get("c")).getVal());
        assertEquals(1, ((Entry) ((Map) schema.get("mapped_complex")).get("c")).getIntVal());
        assertEquals(newOne, ((Map) schema.get("mapped_complex")).get("d"));
    }

    // -------------------------------------------------------------------------

    @Test
    public void test_newInstance_oneLevelMapping() {
        // prepare
        MappingSchema schema;

        Entry entry1 = new Entry("1");
        Entry entry2 = new Entry(2);

        schema = createMappingSchema_mappedComplex(entry1, entry2, null);

        // test
        MappingSchema copy = schema.createCopy();

        // check
        assertNotEquals(schema, copy);
        assertNotEquals(entry1, ((Map) copy.get("mapped_complex")).get("a"));
        assertEquals(entry1.getVal(), ((Entry) ((Map) copy.get("mapped_complex")).get("a")).getVal());
        assertNotEquals(entry2, ((Map) copy.get("mapped_complex")).get("b"));
        assertEquals(entry2.getIntVal(), ((Entry) ((Map) copy.get("mapped_complex")).get("b")).getIntVal());
    }

    @Test
    public void test_newInstance_multiLevel() {
        // prepare
        MappingSchema schema = createMappingSchema_multiLevelMapping();
        MappingSchema internalSchema = (MappingSchema) schema.get("internal_schema");
        MappingSchema internalSubSchema = (MappingSchema) internalSchema.get("internal_sub_schema");

        // test
        MappingSchema copy = schema.createCopy();

        // check
        assertNotEquals(schema, copy);

        assertTrue(copy.get("internal_schema") instanceof MappingSchema);
        MappingSchema copiedInternalSchema = (MappingSchema) copy.get("internal_schema");
        assertNotEquals(internalSchema, copiedInternalSchema);
        assertEquals("!!!", copiedInternalSchema.get("test_prop"));

        assertTrue(copiedInternalSchema.get("internal_sub_schema") instanceof MappingSchema);
        MappingSchema copiedInternalSubSchema = (MappingSchema) copiedInternalSchema.get("internal_sub_schema");
        assertNotEquals(internalSubSchema, copiedInternalSubSchema);
        assertEquals("!!!", copiedInternalSubSchema.get("test_prop"));
    }

    @Test
    public void test_newInstance_deepCopying() {
        // prepare
        MappingSchema schema = new MappingSchemaBean(kryo);

        Entry entry = new Entry("value");

        schema.set("mapped", "key", entry);

        // test
        MappingSchema copy = schema.createCopy();

        // check
        assertNotEquals(schema, copy);
        assertNotEquals(schema.get("mapped"), copy.get("mapped"));
        assertNotEquals(entry, ((Map) copy.get("mapped")).get("key"));
        assertEquals(entry.getVal(), ((Entry) ((Map) copy.get("mapped")).get("key")).getVal());
    }

    // -------------------------------------------------------------------------

    @Test
    public void test_transformIntoMap_multiLevel_withSimple() {
        // prepare
        MappingSchema schema = createMappingSchema_multiLevelMapping();
        MappingSchema internalSchema = (MappingSchema) schema.get("internal_schema");
        MappingSchema internalSubSchema = (MappingSchema) internalSchema.get("internal_sub_schema");

        internalSchema.set("null_prop", null);
        internalSubSchema.set("null_prop", null);

        // test
        Map<String, Object> map = schema.asMap();

        // check
        assertTrue(map.get("internal_schema") instanceof Map);
        Map internalSchemaMap = (Map) map.get("internal_schema");
        assertEquals("!!!", internalSchemaMap.get("test_prop"));
        assertFalse(internalSchemaMap.containsKey("null_prop"));

        assertTrue(internalSchemaMap.get("internal_sub_schema") instanceof Map);
        Map internalSubSchemaMap = (Map) internalSchemaMap.get("internal_sub_schema");
        assertEquals("!!!", internalSubSchemaMap.get("test_prop"));
        assertFalse(internalSubSchemaMap.containsKey("null_prop"));
    }

    @Test
    public void test_transformIntoMap_multiLevel_withIndexed() {
        // prepare
        MappingSchema schema = new MappingSchemaBean(kryo);

        MappingSchema aNonEmptyInternalSchema = new MappingSchemaBean(kryo);
        aNonEmptyInternalSchema.set("some_prop", new Entry()); // to not be wiped out

        schema.set("indexed_mapping", 0, aNonEmptyInternalSchema);
        schema.set("indexed_mapping", 1, new MappingSchemaBean(kryo)); // have to be wiped out
        schema.set("indexed_mapping", 2, null);              // have to be wiped out

        // test
        Map<String, Object> map = schema.asMap();

        // check
        Object indexedAt0 = ((List) map.get("indexed_mapping")).get(0);
        assertTrue(indexedAt0 instanceof Map);
        assertNotNull(((Map) indexedAt0).get("some_prop"));
        assertFalse(((List) map.get("indexed_mapping")).size() > 1);
    }

    @Test
    public void test_transformIntoMap_multiLevel_withMapped() {
        // prepare
        MappingSchema schema = new MappingSchemaBean(kryo);

        MappingSchema aNonEmptyInternalSchema = new MappingSchemaBean(kryo);
        aNonEmptyInternalSchema.set("some_prop", new Entry()); // to not be wiped out

        schema.set("mapped_mapping", "key_0", aNonEmptyInternalSchema);
        schema.set("mapped_mapping", "key_1", new MappingSchemaBean(kryo)); // have to be wiped out
        schema.set("mapped_mapping", "key_2", null);              // have to be wiped out

        // test
        Map<String, Object> map = schema.asMap();

        // check
        Object mappedWithKey0 = ((Map) map.get("mapped_mapping")).get("key_0");
        assertTrue(mappedWithKey0 instanceof Map);
        assertNotNull(((Map) mappedWithKey0).get("some_prop"));
        assertFalse(((Map) map.get("mapped_mapping")).containsKey("key_1"));
        assertFalse(((Map) map.get("mapped_mapping")).containsKey("key_2"));
    }

    // -------------------------------------------------------------------------

    private MappingSchema createMappingSchema_mappedComplex(Entry entry1, Entry entry2, Entry entry3) {
        MappingSchema schema = new MappingSchemaBean(kryo);

        Map<String, Object> internalMap = Stream.of(
                new SimpleEntry<>("a", entry1),
                new SimpleEntry<>("b", entry2),
                new SimpleEntry<>("c", entry3))
                .filter(entry -> entry.getValue() != null)
                .collect(toMap(SimpleEntry::getKey, SimpleEntry::getValue));
        schema.set("mapped_complex", internalMap);

        return schema;
    }

    private MappingSchema createMappingSchema_multiLevelMapping() {
        MappingSchema schema = new MappingSchemaBean(kryo);

        MappingSchema internalSchema = new MappingSchemaBean(kryo);
        internalSchema.set("test_prop", "!!!");

        MappingSchema internalSubSchema = new MappingSchemaBean(kryo);
        internalSubSchema.set("test_prop", "!!!");

        internalSchema.set("internal_sub_schema", internalSubSchema);

        schema.set("internal_schema", internalSchema);

        return schema;
    }

}
