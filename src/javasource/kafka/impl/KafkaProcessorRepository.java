package kafka.impl;

import java.util.HashMap;
import java.util.Map;

public class KafkaProcessorRepository {
	private static Map<String, KafkaProcessor> processors = new HashMap<String, KafkaProcessor>(); 	
	
	public static void put(String name, KafkaProcessor processor) {
		processors.put(name, processor);
	}
	
	public static KafkaProcessor get(String name)
	{
		return processors.get(name);
	}
	
	public static void close(String name) {
		KafkaProcessor processor = processors.get(name);
		if (processor != null) {
			processor.close();
		}	
	}
	
	public static void closeAll()
	{
		for (KafkaProcessor processor : processors.values()) {
			processor.close();
		}
		processors.clear();
	}
}
