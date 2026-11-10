package kafka.impl;

import java.util.*;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class KafkaProcessor extends KafkaConfigurable {
	protected KafkaStreams streams; 
	protected String fromTopic;
	protected String toTopic;
	protected String onProcessMicroflow;
	
	public KafkaProcessor(IMendixObject config, IContext context, String fromTopic, String toTopic, String onProcessMicroflow) {
		super(context);
		props = KafkaPropertiesFactory.getKafkaProperties(context, config);
		props.put(StreamsConfig.STATE_DIR_CONFIG, Core.getConfiguration().getTempPath().getAbsolutePath());
		props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
		props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
		this.fromTopic = fromTopic;
		this.toTopic = toTopic;
		this.onProcessMicroflow = onProcessMicroflow; 
	}
	
	public void start() throws CoreException {
		StreamsConfig config = new StreamsConfig(props);
		StreamsBuilder builder = new StreamsBuilder();
		KStream<Object, Object> stream = builder.stream(fromTopic);
		stream.flatMap((key, value) -> apply(key.toString(), value.toString(), onProcessMicroflow));
		if (toTopic != null && !toTopic.isEmpty()) {
			stream.to(toTopic);
		}
		streams = new KafkaStreams(builder.build(), config);
		streams.start();
	}
	
	public Iterable<KeyValue<String, String>> apply(String key, String value, String onProcessMicroflow)
	{
		Map<String, Object> microflowParams = new HashMap<String, Object>();
		microflowParams.put("key", key);
		microflowParams.put("value", value);
		Object microflowResult;
		List<KeyValue<String, String>> result = new ArrayList<KeyValue<String, String>>();
		try
		{
			LOGGER.trace("executing " + onProcessMicroflow + "(" + key + "," + value + ")");
			microflowResult = Core.microflowCall(onProcessMicroflow).withParams(microflowParams).execute(context); 
		}
		catch (Exception ex)
		{
			LOGGER.error("An error occurred while processing from topic " + fromTopic + ": " + ex.toString());
			return result;
		}

		if (toTopic != null && !toTopic.isEmpty()) {
			return result;
		}
		
		if (microflowResult instanceof List<?>)
		{
			List<IMendixObject> objects = (List<IMendixObject>)microflowResult;
			for (IMendixObject object : objects) {
				result.add(new KeyValue<String, String>((String)object.getValue(context, "key"), (String)object.getValue(context,  "value")));
			}
		} else if (microflowResult instanceof IMendixObject) {
			IMendixObject object = (IMendixObject)microflowResult;
			result.add(new KeyValue<String, String>((String)object.getValue(context, "key"), (String)object.getValue(context,  "value")));		
		} else if (microflowResult instanceof String) {
			String s = (String)microflowResult;
			result.add(new KeyValue<String, String>(key, s));		
		}
		
		return result;
	}
	
	public void close() {
		if (streams != null) {
			streams.close();
		}
	}
}
