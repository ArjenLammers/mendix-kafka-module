package kafka.impl;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.KafkaProducer;

public class KafkaProducerRepository {
	private static final Map<Long, KafkaProducer<String, String>> producers = new HashMap<>();

	public static void put(Long id, KafkaProducer<String, String> producer) {
		producers.put(id, producer);
	}

	public static KafkaProducer<String, String> get(Long id)
	{
		return producers.get(id);
	}

	public static void close(Long id) {
		KafkaProducer<String, String> producer = producers.remove(id);
		if (producer != null) {
			producer.close();
		}
	}

	public static void closeAll()
	{
		for (KafkaProducer<String, String> producer : producers.values()) {
			producer.close();
		}
		producers.clear();
	}
}
