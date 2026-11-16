package kafka.impl;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;

import org.apache.avro.Schema;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;

import kafka.proxies.Header;
import kafka.proxies.Producer;
import kafka.proxies.RecordMetaData;
import kafka.proxies.SchemaIdFormat;

public class KafkaSendHelper {

	/**
	 * Sends a message. With an Avro configuration on the producer, the value is JSON and is sent as Avro, encoded with
	 * the schema from the configuration's Get Avro Schema microflow; otherwise a binary value is sent as bytes and a
	 * value as a string.
	 *
	 * @param schemaId               the Avro schema ID, or null. Required with schema ID format Confluent, where it is
	 *                               written in front of the message; with format None it is only passed to the microflow.
	 * @param waitForAcknowledgement true to wait until the broker has acknowledged the message
	 * @return the metadata of the sent record, or null when not waiting for the acknowledgement
	 */
	public static RecordMetadata send(IContext context, Producer producer, String topic, String key, String value,
			system.proxies.FileDocument binaryValue, List<Header> headers, boolean useCachedProducer, Long schemaId,
			boolean waitForAcknowledgement) throws Exception {
		validateValue(value, binaryValue);
		// The reference is read from the producer object itself: no retrieve per message
		IMendixIdentifier avroConfigurationId = producer.getMendixObject().getValue(context,
				Producer.MemberNames.Producer_AvroConfiguration.toString());
		if (avroConfigurationId == null && schemaId != null) {
			throw new CoreException("Producer " + producer.getName() + " has no Avro configuration, so it cannot send with a schema ID.");
		}

		if (avroConfigurationId != null) {
			byte[] avro = encodeAvro(context, producer, avroConfigurationId, topic, key, value, binaryValue, headers, schemaId);
			KafkaProducer<String, byte[]> binaryProducer = getOrCreateBinaryProducer(context, producer, useCachedProducer);
			try {
				return waitFor(binaryProducer.send(buildBinaryRecord(context, topic, key, avro, headers)), waitForAcknowledgement);
			} finally {
				closeIfUncached(binaryProducer, useCachedProducer);
			}
		} else if (binaryValue != null) {
			KafkaProducer<String, byte[]> binaryProducer = getOrCreateBinaryProducer(context, producer, useCachedProducer);
			try {
				return waitFor(binaryProducer.send(buildBinaryRecord(context, topic, key, binaryValue, headers)), waitForAcknowledgement);
			} finally {
				closeIfUncached(binaryProducer, useCachedProducer);
			}
		} else {
			KafkaProducer<String, String> kafkaProducer = getOrCreateStringProducer(context, producer, useCachedProducer);
			try {
				return waitFor(kafkaProducer.send(buildStringRecord(topic, key, value, headers)), waitForAcknowledgement);
			} finally {
				closeIfUncached(kafkaProducer, useCachedProducer);
			}
		}
	}

	private static byte[] encodeAvro(IContext context, Producer producer, IMendixIdentifier avroConfigurationId, String topic,
			String key, String value, system.proxies.FileDocument binaryValue, List<Header> headers, Long schemaId) throws Exception {
		if (binaryValue != null || value == null) {
			throw new CoreException("Producer " + producer.getName() + " uses Avro: pass the message as JSON in value, not as binaryValue.");
		}
		if (schemaId != null && (schemaId < Integer.MIN_VALUE || schemaId > Integer.MAX_VALUE)) {
			throw new CoreException("Schema ID " + schemaId + " does not fit in 4 bytes.");
		}
		Integer id = schemaId == null ? null : schemaId.intValue();
		AvroSchemaResolver resolver = AvroSchemaResolver.forProducer(context, avroConfigurationId);
		boolean confluent = resolver.getFormat() == SchemaIdFormat.CONFLUENT;
		if (confluent && id == null) {
			throw new CoreException("Producer " + producer.getName() + " uses Avro configuration " + resolver.getConfigurationName()
					+ " with schema ID format Confluent, so a schema ID is required.");
		}
		// The microflow runs in a context of its own, so it never ends or commits the caller's transaction
		Schema schema = resolver.resolve(null, id,
				() -> AvroSchemaResolver.sendValues(topic, key, producer.getMendixObject(), headers));
		return AvroProcessor.encodeAvro(schema, value, confluent ? id : null);
	}

	private static RecordMetadata waitFor(Future<RecordMetadata> result, boolean waitForAcknowledgement) throws Exception {
		return waitForAcknowledgement ? result.get() : null;
	}

	public static RecordMetaData toRecordMetaData(IContext context, RecordMetadata metadata) {
		RecordMetaData result = new RecordMetaData(context);
		result.setHasOffset(metadata.hasOffset());
		if (metadata.hasOffset())
			result.setOffset(metadata.offset());
		result.setPartition(metadata.partition());
		if (metadata.hasTimestamp())
			result.setTimestamp(new Date(metadata.timestamp()));
		return result;
	}

	public static void validateValue(String value, system.proxies.FileDocument binaryValue) throws CoreException {
		if (value == null && binaryValue == null) {
			throw new CoreException("Either value or binaryValue must be provided.");
		}
	}

	@SuppressWarnings("unchecked")
	public static KafkaProducer<String, String> getOrCreateStringProducer(IContext context, Producer producer, boolean useCachedProducer) throws Exception {
		if (useCachedProducer) {
			KafkaProducer<String, String> kafkaProducer = (KafkaProducer<String, String>) KafkaProducerRepository.get(producer.getMendixObject().getId().toLong());
			if (kafkaProducer == null) {
				kafkaProducer = new KafkaProducer<>(KafkaPropertiesFactory.getKafkaProperties(context, producer));
				KafkaProducerRepository.put(producer.getMendixObject().getId().toLong(), kafkaProducer);
			}
			return kafkaProducer;
		} else {
			return new KafkaProducer<>(KafkaPropertiesFactory.getKafkaProperties(context, producer));
		}
	}

	@SuppressWarnings("unchecked")
	public static KafkaProducer<String, byte[]> getOrCreateBinaryProducer(IContext context, Producer producer, boolean useCachedProducer) throws Exception {
		if (useCachedProducer) {
			KafkaProducer<String, byte[]> kafkaProducer = (KafkaProducer<String, byte[]>) KafkaProducerRepository.get(producer.getMendixObject().getId().toLong());
			if (kafkaProducer == null) {
				kafkaProducer = new KafkaProducer<>(KafkaPropertiesFactory.getKafkaProperties(context, producer));
				KafkaProducerRepository.put(producer.getMendixObject().getId().toLong(), kafkaProducer);
			}
			return kafkaProducer;
		} else {
			return new KafkaProducer<>(KafkaPropertiesFactory.getKafkaProperties(context, producer));
		}
	}

	public static ProducerRecord<String, String> buildStringRecord(String topic, String key, String value, List<Header> headers) {
		ProducerRecord<String, String> record = key == null || key.isEmpty()
				? new ProducerRecord<>(topic, value)
				: new ProducerRecord<>(topic, key, value);
		addHeaders(record, headers);
		return record;
	}

	public static ProducerRecord<String, byte[]> buildBinaryRecord(IContext context, String topic, String key, system.proxies.FileDocument binaryValue, List<Header> headers) throws Exception {
		byte[] bytes;
		try (java.io.InputStream stream = Core.getFileDocumentContent(context, binaryValue.getMendixObject())) {
			bytes = stream.readAllBytes();
		}
		return buildBinaryRecord(context, topic, key, bytes, headers);
	}
	
	public static ProducerRecord<String, byte[]> buildBinaryRecord(IContext context, String topic, String key, byte[] bytes, List<Header> headers) throws Exception {
		ProducerRecord<String, byte[]> record = key == null || key.isEmpty()
				? new ProducerRecord<>(topic, bytes)
				: new ProducerRecord<>(topic, key, bytes);
		addHeaders(record, headers);
		return record;
	}

	// unclosed Producers re-authenticate with the broker every 60s; see sasl.kerberos.min.time.before.relogin
	public static void closeIfUncached(KafkaProducer<?, ?> producer, boolean useCachedProducer) {
		if (!useCachedProducer) {
			producer.close();
		}
	}

	private static void addHeaders(ProducerRecord<?, ?> record, List<Header> headers) {
		for (Header header : headers) {
			record.headers().add(header.getKey(), header.getValue().getBytes());
		}
	}
}
