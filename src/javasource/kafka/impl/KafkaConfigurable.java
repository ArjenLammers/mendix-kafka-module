package kafka.impl;

import java.util.Properties;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;

abstract class KafkaConfigurable {
	protected final static ILogNode LOGGER = Core.getLogger("Kafka");	
	protected Properties props;
	protected IContext context;
	
	public KafkaConfigurable(IContext context) {
		this.context = context;
		props = new java.util.Properties();
	}
	
	
}
