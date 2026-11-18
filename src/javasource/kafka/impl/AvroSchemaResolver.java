package kafka.impl;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.Incompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.apache.avro.SchemaCompatibility.SchemaPairCompatibility;
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
 * For reading, the optional Get Reader Schema microflow adds the schema the consumer expects for the record, which is
 * cached per writer schema and topic together with the outcome of the compatibility check.
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
	private final String readerMicroflow;
	private final Map<String, IDataType> readerParameters;

	// Schemas from the microflow per schema ID; the least recently used schema is dropped beyond the limit
	private final Map<Integer, Schema> schemasById = Collections.synchronizedMap(
			new LinkedHashMap<Integer, Schema>(16, 0.75f, true) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<Integer, Schema> eldest) {
					return size() > SCHEMA_CACHE_SIZE;
				}
			});

	// Reader schemas per writer schema and topic, with the outcome of the compatibility check
	private final Map<ReaderKey, ReaderSchema> readerSchemas = Collections.synchronizedMap(
			new LinkedHashMap<ReaderKey, ReaderSchema>(16, 0.75f, true) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<ReaderKey, ReaderSchema> eldest) {
					return size() > SCHEMA_CACHE_SIZE;
				}
			});

	/**
	 * The schemas to decode a message with.
	 */
	public static class ReadSchemas {
		/** The schema the message was written with */
		public final Schema writer;
		/** The schema to decode to, or null to decode exactly as written */
		public final Schema reader;

		ReadSchemas(Schema writer, Schema reader) {
			this.writer = writer;
			this.reader = reader;
		}
	}

	// A writer schema is the parsed instance from AvroProcessor's cache, so it is compared by identity
	private static final class ReaderKey {
		private final Schema writer;
		private final String topic;

		ReaderKey(Schema writer, String topic) {
			this.writer = writer;
			this.topic = topic;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ReaderKey && ((ReaderKey) other).writer == writer
					&& Objects.equals(((ReaderKey) other).topic, topic);
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(writer) * 31 + Objects.hashCode(topic);
		}
	}

	// The reader schema for a writer schema (null: decode as written), or why the two are incompatible
	private static final class ReaderSchema {
		private final Schema schema;
		private final String incompatibility;

		ReaderSchema(Schema schema, String incompatibility) {
			this.schema = schema;
			this.incompatibility = incompatibility;
		}
	}

	/**
	 * @throws CoreException when the configuration has no Get Avro Schema microflow, or it or the Get Reader Schema
	 *                       microflow does not exist
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

		String reader = configuration.getGetReaderSchemaMicroflow();
		this.readerMicroflow = reader == null || reader.isEmpty() ? null : reader;
		Map<String, IDataType> readerInputParameters = null;
		if (readerMicroflow != null) {
			try {
				readerInputParameters = Core.getInputParameters(readerMicroflow);
			} catch (Exception e) {
				readerInputParameters = null;
			}
			if (readerInputParameters == null) {
				throw new CoreException("The Get Reader Schema microflow " + readerMicroflow + " of Avro configuration "
						+ configurationName + " does not exist.");
			}
		}
		this.readerParameters = readerInputParameters;
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
	 * Returns the schemas to decode a consumed message with: the writer schema from {@link #resolve}, and the reader
	 * schema from the Get Reader Schema microflow, if the configuration has one. The reader schema is resolved once per
	 * writer schema and topic, and checked against the writer schema; an incompatible pair fails every message written
	 * with that schema, with the reason. A writer schema that is a union of several types is decoded as written,
	 * because its record name is only known per message.
	 *
	 * @param context  see {@link #resolve}
	 * @param schemaId the schema ID, or null when the message has none
	 * @param topic    the topic of the message
	 * @param consumer the consumer (Kafka.Consumer) that reads the message
	 * @param values   see {@link #resolve}
	 */
	public ReadSchemas resolveForReading(IContext context, Integer schemaId, String topic, IMendixObject consumer,
			Supplier<Map<String, Object>> values) throws CoreException {
		Schema writer = resolve(context, schemaId, values);
		if (readerMicroflow == null || writer.getType() == Schema.Type.UNION) {
			return new ReadSchemas(writer, null);
		}
		ReaderKey key = new ReaderKey(writer, topic);
		ReaderSchema reader = readerSchemas.get(key);
		if (reader == null) {
			reader = resolveReader(context, writer, schemaId, topic, consumer);
			readerSchemas.put(key, reader);
		}
		if (reader.incompatibility != null) {
			throw new CoreException(reader.incompatibility);
		}
		return new ReadSchemas(writer, reader.schema);
	}

	private ReaderSchema resolveReader(IContext context, Schema writer, Integer schemaId, String topic,
			IMendixObject consumer) throws CoreException {
		Map<String, Object> values = new HashMap<>();
		values.put("RecordName", writer.getFullName());
		values.put("SchemaId", schemaId == null ? null : Long.valueOf(schemaId));
		values.put("Topic", topic);
		values.put("Consumer", consumer);
		Map<String, Object> microflowParams = new HashMap<>();
		for (Map.Entry<String, Object> value : values.entrySet()) {
			if (readerParameters.containsKey(value.getKey())) {
				microflowParams.put(value.getKey(), value.getValue());
			}
		}

		IContext microflowContext = context != null ? context : Core.createSystemContext();
		String schema;
		try {
			schema = Core.microflowCall(readerMicroflow).withParams(microflowParams).execute(microflowContext);	// throws CoreException
			endTransactions(microflowContext);
		} catch (Throwable e) {
			endTransactions(microflowContext);
			throw new CoreException("The Get Reader Schema microflow " + readerMicroflow + " of Avro configuration "
					+ configurationName + " failed for record " + writer.getFullName() + ".", e);
		}
		if (schema == null || schema.isEmpty()) {
			LOGGER.debug("Avro configuration " + configurationName + " decodes record " + writer.getFullName()
					+ " as written: the Get Reader Schema microflow returned no schema.");
			return new ReaderSchema(null, null);
		}
		Schema reader = AvroProcessor.parseSchemaCached(schema);
		SchemaPairCompatibility compatibility = SchemaCompatibility.checkReaderWriterCompatibility(reader, writer);
		if (compatibility.getType() != SchemaCompatibilityType.COMPATIBLE) {
			StringBuilder reasons = new StringBuilder();
			for (Incompatibility incompatibility : compatibility.getResult().getIncompatibilities()) {
				reasons.append(reasons.length() == 0 ? "" : "; ").append(incompatibility.getType()).append(" at ")
						.append(incompatibility.getLocation()).append(": ").append(incompatibility.getMessage());
			}
			String message = "Avro configuration " + configurationName + " cannot translate record "
					+ writer.getFullName() + (schemaId == null ? "" : " with schema ID " + schemaId)
					+ " to the reader schema " + reader.getFullName() + ": " + reasons;
			LOGGER.warn(message);
			return new ReaderSchema(reader, message);
		}
		LOGGER.debug("Avro configuration " + configurationName + " translates record " + writer.getFullName()
				+ (schemaId == null ? "" : " with schema ID " + schemaId) + " to its reader schema.");
		return new ReaderSchema(reader, null);
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
