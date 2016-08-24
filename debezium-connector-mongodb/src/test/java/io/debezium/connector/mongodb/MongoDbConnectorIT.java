/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mongodb;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.apache.kafka.common.config.Config;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.bson.Document;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertOneOptions;

import static org.fest.assertions.Assertions.assertThat;

import io.debezium.config.Configuration;
import io.debezium.config.Field.Recommender;
import io.debezium.connector.mongodb.ConnectionContext.MongoPrimary;
import io.debezium.data.Envelope;
import io.debezium.data.Envelope.Operation;
import io.debezium.embedded.AbstractConnectorTest;
import io.debezium.util.IoUtil;
import io.debezium.util.Testing;

/**
 * @author Randall Hauch
 *
 */
public class MongoDbConnectorIT extends AbstractConnectorTest {

    private Configuration config;
    private ReplicationContext context;

    @Before
    public void beforeEach() {
        Testing.Debug.disable();
        Testing.Print.disable();
        stopConnector();
        initializeConnectorTestFramework();
    }

    @After
    public void afterEach() {
        try {
            stopConnector();
        } finally {
            if (context != null) context.shutdown();
        }
    }

    /**
     * Verifies that the connector doesn't run with an invalid configuration. This does not actually connect to the MySQL server.
     */
    @Test
    public void shouldNotStartWithInvalidConfiguration() {
        config = Configuration.create()
                              .with(MongoDbConnectorConfig.AUTO_DISCOVER_MEMBERS, "true")
                              .build();

        // we expect the engine will log at least one error, so preface it ...
        logger.info("Attempting to start the connector with an INVALID configuration, so MULTIPLE error messages & one exceptions will appear in the log");
        start(MongoDbConnector.class, config, (success, msg, error) -> {
            assertThat(success).isFalse();
            assertThat(error).isNotNull();
        });
        assertConnectorNotRunning();
    }

    @Test
    public void shouldFailToValidateInvalidConfiguration() {
        Configuration config = Configuration.create().build();
        MongoDbConnector connector = new MongoDbConnector();
        Config result = connector.validate(config.asMap());

        assertConfigurationErrors(result, MongoDbConnectorConfig.HOSTS, 1);
        assertConfigurationErrors(result, MongoDbConnectorConfig.LOGICAL_NAME, 1);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.USER);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.PASSWORD);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.AUTO_DISCOVER_MEMBERS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.DATABASE_LIST);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.COLLECTION_WHITELIST);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.COLLECTION_BLACKLIST);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.MAX_COPY_THREADS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.MAX_QUEUE_SIZE);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.MAX_BATCH_SIZE);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.POLL_INTERVAL_MS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.CONNECT_BACKOFF_INITIAL_DELAY_MS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.CONNECT_BACKOFF_MAX_DELAY_MS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.MAX_FAILED_CONNECTIONS);
    }

    @Test
    public void shouldValidateAcceptableConfiguration() {
        config = Configuration.create()
                              .with(MongoDbConnectorConfig.HOSTS, System.getProperty("connector.mongodb.hosts"))
                              .with(MongoDbConnectorConfig.AUTO_DISCOVER_MEMBERS, System.getProperty("connector.mongodb.members.auto.discover"))
                              .with(MongoDbConnectorConfig.LOGICAL_NAME, System.getProperty("connector.mongodb.name"))
                              .build();
        
        // Add data to the databases so that the databases will be listed ...
        context = new ReplicationContext(config);
        storeDocuments("dbval", "validationColl1", "simple_objects.json");
        storeDocuments("dbval2", "validationColl2", "restaurants1.json");
        
        MongoDbConnector connector = new MongoDbConnector();
        Config result = connector.validate(config.asMap());

        assertNoConfigurationErrors(result, MongoDbConnectorConfig.HOSTS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.LOGICAL_NAME);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.USER);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.PASSWORD);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.AUTO_DISCOVER_MEMBERS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.DATABASE_LIST);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.COLLECTION_WHITELIST);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.COLLECTION_BLACKLIST);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.MAX_COPY_THREADS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.MAX_QUEUE_SIZE);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.MAX_BATCH_SIZE);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.POLL_INTERVAL_MS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.CONNECT_BACKOFF_INITIAL_DELAY_MS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.CONNECT_BACKOFF_MAX_DELAY_MS);
        assertNoConfigurationErrors(result, MongoDbConnectorConfig.MAX_FAILED_CONNECTIONS);

        // Testing.Debug.enable();

        Recommender dbNameRecommender = MongoDbConnectorConfig.DATABASE_LIST.recommender();
        List<Object> dbNames = dbNameRecommender.validValues(MongoDbConnectorConfig.DATABASE_LIST, config);
        Testing.debug("List of dbNames: " + dbNames);
        assertThat(dbNames).contains("dbval", "dbval2");    // may have more depending upon order

        Recommender collectionNameRecommender = MongoDbConnectorConfig.COLLECTION_WHITELIST.recommender();
        List<Object> collectionNames = collectionNameRecommender.validValues(MongoDbConnectorConfig.COLLECTION_WHITELIST, config);
        Testing.debug("List of collection names: " + collectionNames);
        assertThat(collectionNames).isEmpty();

        // Now set the whitelist to two databases ...
        Configuration config2 = config.edit()
                                      .with(MongoDbConnectorConfig.DATABASE_LIST, "dbval")
                                      .build();

        List<Object> collectionNames2 = collectionNameRecommender.validValues(MongoDbConnectorConfig.COLLECTION_WHITELIST, config2);
        assertThat(collectionNames2).containsOnly("dbval.validationColl1");
        Testing.debug("List of collection names: " + collectionNames2);
    }

    @Test
    public void shouldConsumeAllEventsFromDatabase() throws InterruptedException, IOException {

        // Use the DB configuration to define the connector's configuration ...
        config = Configuration.create()
                              .with(MongoDbConnectorConfig.HOSTS, System.getProperty("connector.mongodb.hosts"))
                              .with(MongoDbConnectorConfig.AUTO_DISCOVER_MEMBERS,
                                    System.getProperty("connector.mongodb.members.auto.discover"))
                              .with(MongoDbConnectorConfig.LOGICAL_NAME, System.getProperty("connector.mongodb.name"))
                              .with(MongoDbConnectorConfig.POLL_INTERVAL_MS, 10)
                              .with(MongoDbConnectorConfig.COLLECTION_WHITELIST, "dbit.*")
                              .with(MongoDbConnectorConfig.LOGICAL_NAME, "mongo")
                              .build();

        // Set up the replication context for connections ...
        context = new ReplicationContext(config);

        // Before starting the connector, add data to the databases ...
        storeDocuments("dbit", "simpletons", "simple_objects.json");
        storeDocuments("dbit", "restaurants", "restaurants1.json");

        // Start the connector ...
        start(MongoDbConnector.class, config);

        // ---------------------------------------------------------------------------------------------------------------
        // Consume all of the events due to startup and initialization of the database
        // ---------------------------------------------------------------------------------------------------------------
        SourceRecords records = consumeRecordsByTopic(12);
        assertThat(records.recordsForTopic("mongo.dbit.simpletons").size()).isEqualTo(6);
        assertThat(records.recordsForTopic("mongo.dbit.restaurants").size()).isEqualTo(6);
        assertThat(records.topics().size()).isEqualTo(2);
        records.forEach(record -> {
            // Check that all records are valid, and can be serialized and deserialized ...
            validate(record);
            verifyFromInitialSync(record);
            verifyReadOperation(record);
        });

        // At this point, the connector has performed the initial sync and awaits changes ...

        // ---------------------------------------------------------------------------------------------------------------
        // Store more documents while the connector is still running
        // ---------------------------------------------------------------------------------------------------------------
        storeDocuments("dbit", "restaurants", "restaurants2.json");

        // Wait until we can consume the 4 documents we just added ...
        SourceRecords records2 = consumeRecordsByTopic(4);
        assertThat(records2.recordsForTopic("mongo.dbit.restaurants").size()).isEqualTo(4);
        assertThat(records2.topics().size()).isEqualTo(1);
        records2.forEach(record -> {
            // Check that all records are valid, and can be serialized and deserialized ...
            validate(record);
            verifyNotFromInitialSync(record);
            verifyCreateOperation(record);
        });

        // ---------------------------------------------------------------------------------------------------------------
        // Stop the connector
        // ---------------------------------------------------------------------------------------------------------------
        stopConnector();

        // ---------------------------------------------------------------------------------------------------------------
        // Store more documents while the connector is NOT running
        // ---------------------------------------------------------------------------------------------------------------
        storeDocuments("dbit", "restaurants", "restaurants3.json");

        // ---------------------------------------------------------------------------------------------------------------
        // Start the connector and we should only see the documents added since it was stopped
        // ---------------------------------------------------------------------------------------------------------------
        start(MongoDbConnector.class, config);

        // Wait until we can consume the 4 documents we just added ...
        SourceRecords records3 = consumeRecordsByTopic(5);
        assertThat(records3.recordsForTopic("mongo.dbit.restaurants").size()).isEqualTo(5);
        assertThat(records3.topics().size()).isEqualTo(1);
        records3.forEach(record -> {
            // Check that all records are valid, and can be serialized and deserialized ...
            validate(record);
            verifyNotFromInitialSync(record);
            verifyCreateOperation(record);
        });

        // ---------------------------------------------------------------------------------------------------------------
        // Store more documents while the connector is still running
        // ---------------------------------------------------------------------------------------------------------------
        storeDocuments("dbit", "restaurants", "restaurants4.json");

        // Wait until we can consume the 4 documents we just added ...
        SourceRecords records4 = consumeRecordsByTopic(8);
        assertThat(records4.recordsForTopic("mongo.dbit.restaurants").size()).isEqualTo(8);
        assertThat(records4.topics().size()).isEqualTo(1);
        records4.forEach(record -> {
            // Check that all records are valid, and can be serialized and deserialized ...
            validate(record);
            verifyNotFromInitialSync(record);
            verifyCreateOperation(record);
        });

    }

    protected void verifyFromInitialSync(SourceRecord record) {
        assertThat(record.sourceOffset().containsKey(SourceInfo.INITIAL_SYNC)).isTrue();
        Struct value = (Struct) record.value();
        assertThat(value.getStruct(Envelope.FieldName.SOURCE).getBoolean(SourceInfo.INITIAL_SYNC)).isTrue();
    }

    protected void verifyNotFromInitialSync(SourceRecord record) {
        assertThat(record.sourceOffset().containsKey(SourceInfo.INITIAL_SYNC)).isFalse();
        Struct value = (Struct) record.value();
        assertThat(value.getStruct(Envelope.FieldName.SOURCE).getBoolean(SourceInfo.INITIAL_SYNC)).isNull();
    }

    protected void verifyCreateOperation(SourceRecord record) {
        verifyOperation(record, Operation.CREATE);
    }

    protected void verifyReadOperation(SourceRecord record) {
        verifyOperation(record, Operation.READ);
    }

    protected void verifyUpdateOperation(SourceRecord record) {
        verifyOperation(record, Operation.UPDATE);
    }

    protected void verifyDeleteOperation(SourceRecord record) {
        verifyOperation(record, Operation.DELETE);
    }

    protected void verifyOperation(SourceRecord record, Operation expected) {
        Struct value = (Struct) record.value();
        assertThat(value.getString(Envelope.FieldName.OPERATION)).isEqualTo(expected.code());
    }

    protected MongoPrimary primary() {
        ReplicaSet replicaSet = ReplicaSet.parse(context.hosts());
        return context.primaryFor(replicaSet, connectionErrorHandler(3));
    }

    protected void storeDocuments(String dbName, String collectionName, String pathOnClasspath) {
        primary().execute("storing documents", mongo -> {
            Testing.debug("Storing in '" + dbName + "." + collectionName + "' documents loaded from from '" + pathOnClasspath + "'");
            MongoDatabase db1 = mongo.getDatabase(dbName);
            MongoCollection<Document> coll = db1.getCollection(collectionName);
            coll.drop();
            storeDocuments(coll, pathOnClasspath);
        });
    }

    protected void storeDocuments(MongoCollection<Document> collection, String pathOnClasspath) {
        InsertOneOptions insertOptions = new InsertOneOptions().bypassDocumentValidation(true);
        loadTestDocuments(pathOnClasspath).forEach(doc -> {
            assertThat(doc).isNotNull();
            assertThat(doc.size()).isGreaterThan(0);
            collection.insertOne(doc, insertOptions);
        });
    }

    protected List<Document> loadTestDocuments(String pathOnClasspath) {
        List<Document> results = new ArrayList<>();
        try (InputStream stream = Testing.Files.readResourceAsStream(pathOnClasspath);) {
            assertThat(stream).isNotNull();
            IoUtil.readLines(stream, line -> {
                Document doc = Document.parse(line);
                assertThat(doc.size()).isGreaterThan(0);
                results.add(doc);
            });
        } catch (IOException e) {
            fail("Unable to find or read file '" + pathOnClasspath + "': " + e.getMessage());
        }
        return results;
    }

    protected BiConsumer<String, Throwable> connectionErrorHandler(int numErrorsBeforeFailing) {
        AtomicInteger attempts = new AtomicInteger();
        return (desc, error) -> {
            if (attempts.incrementAndGet() > numErrorsBeforeFailing) {
                fail("Unable to connect to primary after " + numErrorsBeforeFailing + " errors trying to " + desc + ": " + error);
            }
            logger.error("Error while attempting to {}: {}", desc, error.getMessage(), error);
        };
    }
}