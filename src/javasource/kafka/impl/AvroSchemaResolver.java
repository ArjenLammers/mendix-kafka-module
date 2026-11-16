package kafka.impl;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.avro.Schema;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import kafka.proxies.AvroConfiguration;
import kafka.proxies.Header;
import kafka.proxies.SchemaIdFormat;

/**
 * Resolves the Avro schema of a message with the Get Avro Schema microflow of an Avro configuration. The
 * microflow's result is cached per schema ID; a message without a schema ID calls the microflow every time.
 */
public class AvroSchemaResolver {
	private static final ILogNode LOGGER = Core.getLogger("Kafka");

	private static final int SCHEMA_CACHE_SIZE = 100;

	// Resolvers of the producers' Avro configurations by configuration ID, shared by all sending threads of this node.
	// Saving any Kafka setting restarts the producers on every node, which empties it (StopAll).
	private static final Map<Long, AvroSchemaResolver> producerResolvers = new ConcurrentHashMap<>();

	private final String configurationName;
	private final SchemaIdFormat format;
	private final String microflow;
	private final Map<String, IDataType> parameters;

	// Schemas from the microflow per schema ID; the least recently used schema is dropped beyond the limit
	private final Map<Integer, Schema> schemasById = Collections.synchronizedMap(
			new LinkedHashMap<Integer, Schema>(16, 0.75f, true) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<Integer, Schema> eldest) {
					return size() > SCHEMA_CACHE_SIZE;
				}
			});

	/**
	 * @throws CoreException when the configuration has no Get Avro Schema microflow, or it does not exist
	 */
	public AvroSchemaResolver(AvroConfiguration configuration) throws CoreException {
		this.configurationName = configuration.getName();
		this.format = configuration.getSchemaIdFormat() == null ? SchemaIdFormat.CONFLUENT : configuration.getSchemaIdFormat();
		this.microflow = configuration.getGetAvroSchemaMicroflow();
		if (microflow == null || microflow.isEmpty()) {
			throw new CoreException("Avro configuration " + configurationName + " has no Get Avro Schema microflow.");
		}
		Map<String, IDataType> inputParameters;
		try {
			inputParameters = Core.getInputParameters(microflow);
		} catch (Exception e) {
			inputParameters = null;
		}
		if (inputParameters == null) {
			throw new CoreException("The Get Avro Schema microflow " + microflow + " of Avro configuration "
					+ configurationName + " does not exist.");
		}
		this.parameters = inputParameters;
	}

	/**
	 * The resolver of a producer's Avro configuration, shared by all sending threads of this node.
	 */
	public static AvroSchemaResolver forProducer(IContext context, IMendixIdentifier avroConfigurationId) throws CoreException {
		AvroSchemaResolver resolver = producerResolvers.get(avroConfigurationId.toLong());
		if (resolver == null) {
			// Two threads may create one at the same time; both are equal, and the first one is kept.
			AvroSchemaResolver created = new AvroSchemaResolver(AvroConfiguration.load(context, avroConfigurationId));
			resolver = producerResolvers.putIfAbsent(avroConfigurationId.toLong(), created);
			if (resolver == null) {
				resolver = created;
			}
		}
		return resolver;
	}

	/**
	 * Forgets the producers' resolvers and their schemas; called when the producers stop.
	 */
	public static void clearProducerResolvers() {
		producerResolvers.clear();
	}

	public String getConfigurationName() {
		return configurationName;
	}

	public SchemaIdFormat getFormat() {
		return format;
	}

	/**
	 * Returns the schema for a message: from the cache for a known schema ID, otherwise from the Get Avro Schema
	 * microflow. A microflow that fails or returns no schema, or a schema that cannot be parsed, fails the message and
	 * is not cached.
	 *
	 * @param context  a context of its own to call the microflow in (its transactions are ended), or null for a new
	 *                 system context
	 * @param schemaId the schema ID, or null when the message has none
	 * @param values   values for the microflow's parameters by name (Topic, Key, header names, ...); only the
	 *                 parameters the microflow declares are passed. Only called when the microflow is called.
	 */
	public Schema resolve(IContext context, Integer schemaId, Supplier<Map<String, Object>> values) throws CoreException {
		if (schemaId != null) {
			Schema cached = schemasById.get(schemaId);
			if (cached != null) {
				return cached;
			}
		}
		Map<String, Object> microflowParams = new HashMap<>();
		for (Map.Entry<String, Object> value : values.get().entrySet()) {
			if (parameters.containsKey(value.getKey())) {
				microflowParams.put(value.getKey(), value.getValue());
			}
		}
		if (parameters.containsKey("SchemaId")) {
			microflowParams.put("SchemaId", schemaId == null ? null : Long.valueOf(schemaId));
		}

		IContext microflowContext = context != null ? context : Core.createSystemContext();
		String schema;
		try {
			schema = Core.microflowCall(microflow).withParams(microflowParams).execute(microflowContext);	// throws CoreException
			endTransactions(microflowContext);
		} catch (Throwable e) {
			endTransactions(microflowContext);
			throw new CoreException("The Get Avro Schema microflow " + microflow + " of Avro configuration "
					+ configurationName + " failed.", e);
		}
		if (schema == null || schema.isEmpty()) {
			throw new CoreException("The Get Avro Schema microflow " + microflow + " of Avro configuration "
					+ configurationName + " returned no schema" + (schemaId == null ? "." : " for schema ID " + schemaId + "."));
		}
		Schema parsed = AvroProcessor.parseSchemaCached(schema);
		if (schemaId != null) {
			schemasById.put(schemaId, parsed);
			LOGGER.debug("Avro configuration " + configurationName + " cached the schema for schema ID " + schemaId);
		}
		return parsed;
	}

	/**
	 * The values a consumed record offers the Get Avro Schema microflow: Topic, Key, Partition, Offset, Consumer and
	 * the headers by name. A header named like one of the others replaces it.
	 */
	public static Map<String, Object> recordValues(ConsumerRecord<String, ?> record, IMendixObject consumer) {
		Map<String, Object> values = new HashMap<>();
		values.put("Topic", record.topic());
		values.put("Key", record.key());
		values.put("Partition", record.partition());
		values.put("Offset", record.offset());
		values.put("Consumer", consumer);
		for (org.apache.kafka.common.header.Header header : record.headers()) {
			if (header.value() != null) {
				values.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
			}
		}
		return values;
	}

	/**
	 * The values a message to send offers the Get Avro Schema microflow: Topic, Key, Producer and the headers by name.
	 * A header named like one of the others replaces it.
	 */
	public static Map<String, Object> sendValues(String topic, String key, IMendixObject producer, List<Header> headers) {
		Map<String, Object> values = new HashMap<>();
		values.put("Topic", topic);
		values.put("Key", key);
		values.put("Producer", producer);
		for (Header header : headers) {
			values.put(header.getKey(), header.getValue());
		}
		return values;
	}

	private static void endTransactions(IContext context) {
		try {
			while (context.isInTransaction())
				context.endTransaction();
		} catch (Exception ex) {}
	}
}
