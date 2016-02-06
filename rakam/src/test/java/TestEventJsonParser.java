import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.rakam.EventBuilder;
import org.rakam.analysis.InMemoryMetastore;
import org.rakam.collection.Event;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.collection.event.EventCollectionHttpService.EventList;
import org.rakam.collection.event.EventDeserializer;
import org.rakam.collection.event.EventListDeserializer;
import org.rakam.collection.event.FieldDependencyBuilder;
import org.rakam.collection.event.metastore.Metastore;
import org.rakam.util.JsonHelper;
import org.rakam.util.RakamException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.testng.Assert.assertEquals;

public class TestEventJsonParser {
    private ObjectMapper mapper;
    private Metastore.ProjectApiKeys apiKeys;
    private EventBuilder eventBuilder;
    private InMemoryMetastore metastore;
    private EventDeserializer eventDeserializer;

    @BeforeSuite
    public void setUp() throws Exception {
        FieldDependencyBuilder.FieldDependency fieldDependency = new FieldDependencyBuilder().build();
        metastore = new InMemoryMetastore();

        eventDeserializer = new EventDeserializer(metastore, fieldDependency);
        EventListDeserializer eventListDeserializer = new EventListDeserializer(metastore, fieldDependency);

        mapper = JsonHelper.getMapper();
        mapper.registerModule(new SimpleModule()
                .addDeserializer(Event.class, eventDeserializer)
                .addDeserializer(EventList.class, eventListDeserializer));
        eventBuilder = new EventBuilder("test", metastore);
    }

    @AfterMethod
    public void tearDownMethod() throws Exception {
        metastore.deleteProject("test");
        eventDeserializer.cleanCache();
        eventBuilder.cleanCache();
    }

    @BeforeMethod
    public void setupMethod() throws Exception {
        metastore.createProject("test");
        apiKeys = metastore.createApiKeys("test");
    }

    @Test
    public void testSimple() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", ImmutableMap.of()));

        Event event = mapper.readValue(bytes, Event.class);

        assertEquals("test", event.project());
        assertEquals("test", event.collection());
        assertEquals(api, event.api());
        assertEquals(eventBuilder
                .createEvent("test", ImmutableMap.of()).properties(), event.properties());
    }

    @Test
    public void testPrimitiveTypes() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        ImmutableMap<String, Object> properties = ImmutableMap.of(
                "test", 1,
                "test1", false,
                "test2", Instant.now(),
                "test3", "test",
                "test4", LocalDate.now());

        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", properties));

        Event event = mapper.readValue(bytes, Event.class);

        assertEquals("test", event.project());
        assertEquals("test", event.collection());
        assertEquals(api, event.api());
        assertEquals(eventBuilder
                .createEvent("test", properties).properties(), event.properties());

        assertEquals(ImmutableSet.copyOf(metastore.getCollection("test", "test")), ImmutableSet.of(
                new SchemaField("test", FieldType.DOUBLE),
                new SchemaField("test1", FieldType.BOOLEAN),
                new SchemaField("test2", FieldType.TIMESTAMP),
                new SchemaField("test3", FieldType.STRING),
                new SchemaField("test4", FieldType.DATE)));
    }

    @Test
    public void testMapType() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        ImmutableMap<String, Object> properties = ImmutableMap.of("test0", "test",
                "test1", ImmutableMap.of("a", 4.0, "b", 5.0, "c", 6.0, "d", 7.0),
                "test2", false);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", properties));

        Event event = mapper.readValue(bytes, Event.class);

        assertEquals("test", event.project());
        assertEquals("test", event.collection());
        assertEquals(api, event.api());
        assertEquals(eventBuilder
                .createEvent("test", properties).properties(), event.properties());
    }

    @Test
    public void testArrayType() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        ImmutableMap<String, Object> properties = ImmutableMap.of("test0", "test",
                "test1", ImmutableList.of("test", "test"),
                "test2", false);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", properties));

        Event event = mapper.readValue(bytes, Event.class);
        ;

        assertEquals("test", event.project());
        assertEquals("test", event.collection());
        assertEquals(api, event.api());
        assertEquals(eventBuilder
                .createEvent("test", properties).properties(), event.properties());
    }

    @Test(expectedExceptions = JsonMappingException.class, expectedExceptionsMessageRegExp = "'project' and 'collection' fields must be located before 'properties' field.")
    public void testInvalidOrder() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "properties", ImmutableMap.of("test0", "test",
                        "test1", ImmutableList.of("test", "test"),
                        "test2", false),
                "api", api,
                "collection", "test"));

        mapper.readValue(bytes, Event.class);
    }

    @Test(expectedExceptions = RakamException.class)
    public void testInvalidField() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", ImmutableMap.of("test0", "test",
                        "test1", ImmutableList.of("test", "test"),
                        "test2", false),
                "test", "test"
        ));

        Event event = mapper.readValue(bytes, Event.class);
        ;

        assertEquals("test", event.project());
        assertEquals("test", event.collection());
        assertEquals(api, event.api());
        assertEquals(eventBuilder
                .createEvent("test", ImmutableMap.of()).properties(), event.properties());
    }

    @Test(expectedExceptions = JsonMappingException.class, expectedExceptionsMessageRegExp = "Nested properties are not supported. \\(\'test1\\' field\\)")
    public void testInvalidArrayRecursiveType() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", ImmutableMap.of("test0", "test",
                        "test1", ImmutableList.of("test", ImmutableMap.of("test", 2)),
                        "test2", false)));

        mapper.readValue(bytes, Event.class);
    }

    @Test(expectedExceptions = RakamException.class, expectedExceptionsMessageRegExp = "Nested properties is not supported")
    public void testInvalidMapRecursiveType() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", ImmutableMap.of("test0", "test",
                        "test1", ImmutableMap.of("test", ImmutableList.of("test")),
                        "test2", false)));

        mapper.readValue(bytes, Event.class);
    }

    @Test
    public void testInvalidArray() throws Exception {

        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", ImmutableMap.of("test1", ImmutableList.of(true, 10))));

        Event event = mapper.readValue(bytes, Event.class);

        assertEquals("test", event.project());
        assertEquals("test", event.collection());
        assertEquals(api, event.api());
        assertEquals(eventBuilder.createEvent("test", ImmutableMap.of("test1", ImmutableList.of(true, true))).properties(),
                event.properties());
    }

    @Test
    public void testInvalidMap() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "collection", "test",
                "api", api,
                "properties", ImmutableMap.of("test1", ImmutableMap.of("test", 1, "test2", "test"))));

        Event event = mapper.readValue(bytes, Event.class);

        assertEquals("test", event.project());
        assertEquals("test", event.collection());
        assertEquals(api, event.api());
        assertEquals(eventBuilder
                .createEvent("test",  ImmutableMap.of("test1", ImmutableMap.of("test", 1.0, "test2", 0.0))).properties(),
                event.properties());
    }

    @Test
    public void testBatch() throws Exception {
        Event.EventContext api = new Event.EventContext(apiKeys.writeKey, "1.0", null, null);
        ImmutableMap<String, Object> props = ImmutableMap.of(
                "test0", "test",
                "test1", ImmutableList.of("test"),
                "test2", false);
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "api", api,
                "events", ImmutableList.of(
                        ImmutableMap.of("collection", "test", "properties", props),
                        ImmutableMap.of("collection", "test", "properties", props))));

        EventList events = mapper.readValue(bytes, EventList.class);

        assertEquals("test", events.project);
        assertEquals(api, events.api);

        for (Event event : events.events) {
            assertEquals("test", event.collection());

            assertEquals(eventBuilder.createEvent("test", props).properties(), event.properties());
        }
    }

    @Test(expectedExceptions = RakamException.class, expectedExceptionsMessageRegExp = "project is already set")
    public void testBatchProjectInEvent() throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(ImmutableMap.of(
                "project", "test",
                "api", new Event.EventContext(apiKeys.writeKey, "1.0", null, null),
                "events", ImmutableList.of(
                        ImmutableMap.of("project", "test", "collection", "test", "properties", ImmutableMap.of()))));

        mapper.readValue(bytes, EventList.class);
    }

    // TODO: test invalid json data
}