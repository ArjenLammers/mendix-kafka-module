package kafka.impl;

import java.util.List;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;

import kafka.proxies.Header;
import kafka.proxies.Producer;

public class KafkaSendHelper {

	public static void validateValue(String value, system.proxies.FileDocument binaryValue) throws CoreException {
		if (value == null && binaryValue == null) {
			throw new CoreException("Either value or binaryValue must be provided.");
		}
	}

	public static KafkaProducer<String, String> getOrCreateStringProducer(IContext context, Producer producer, boolean useCachedProducer) throws Exception {
		if (useCachedProducer) {
			KafkaProducer<String, String> kafkaProducer = KafkaProducerRepository.get(producer.getMendixObject().getId().toLong());
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
			KafkaProducer<String, byte[]> kafkaProducer = (KafkaProducer<String, byte[]>) (Object) KafkaProducerRepository.get(producer.getMendixObject().getId().toLong());
			if (kafkaProducer == null) {
				kafkaProducer = new KafkaProducer<>(KafkaPropertiesFactory.getKafkaProperties(context, producer));
				KafkaProducerRepository.put(producer.getMendixObject().getId().toLong(), (KafkaProducer<String, String>) (Object) kafkaProducer);
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
