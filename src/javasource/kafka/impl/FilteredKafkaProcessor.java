package kafka.impl;

import java.io.IOException;

import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class FilteredKafkaProcessor extends KafkaProcessor {
	protected String jsonPointer;
	protected String filterValue;
	
	public FilteredKafkaProcessor(IMendixObject config, IContext context, String fromTopic, String jsonPointer, String filterValue, String toTopic, String onProcessMicroflow) {
		super(config, context, fromTopic, toTopic, onProcessMicroflow);
		this.jsonPointer = jsonPointer;
		this.filterValue = filterValue;
	}

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	public void start() throws CoreException {
		StreamsConfig config = new StreamsConfig(props);
		StreamsBuilder builder = new StreamsBuilder();

		KStream<String, String> stream = builder.stream(fromTopic);
		
		stream
			.filter((key, value) -> filter(key, value))
			.flatMap((key, value) -> apply(key, value, onProcessMicroflow));
		if (toTopic != null && !toTopic.isEmpty()) {
			stream.to(toTopic);
		}
		streams = new KafkaStreams(builder.build(), config);
		streams.start();
	}
	
	private Boolean filter(String key, String value) {
		JsonNode node;
		
		try
		{
			node = mapper.readTree(value);
		}
		catch (IOException ex)
		{
			return false;
		}
		
		return node.at(jsonPointer).toString().equals(filterValue);
	}
}
