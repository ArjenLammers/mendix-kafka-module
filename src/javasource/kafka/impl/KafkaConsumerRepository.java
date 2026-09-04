package kafka.impl;

import java.util.HashMap;
import java.util.Map;

public class KafkaConsumerRepository {
	private static final long STOP_TIMEOUT_MILLIS = 30000;

	private static Map<String, KafkaConsumerRunner> consumers = new HashMap<String, KafkaConsumerRunner>(); 	

	public static void put(String name, KafkaConsumerRunner consumer) {
		consumers.put(name, consumer);
	}
	
	public static void stop(String name)
	{
		consumers.get(name).stop();
	}

	/**
	 * Stops all consumers and waits until they have left their consumer group.
	 */
	public static void stopAll()
	{
		for (KafkaConsumerRunner consumer : consumers.values()) {
			consumer.stop();
		}
		for (Map.Entry<String, KafkaConsumerRunner> entry : consumers.entrySet()) {
			try {
				if (!entry.getValue().awaitStop(STOP_TIMEOUT_MILLIS)) {
					KafkaModule.LOGGER.warn("Kafka consumer " + entry.getKey() + " did not stop within "
							+ STOP_TIMEOUT_MILLIS + " ms.");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		consumers.clear();
	}
}
