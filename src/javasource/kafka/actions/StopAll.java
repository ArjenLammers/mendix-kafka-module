// This file was generated by Mendix Modeler.
//
// WARNING: Only the following code will be retained when actions are regenerated:
// - the import list
// - the code between BEGIN USER CODE and END USER CODE
// - the code between BEGIN EXTRA CODE and END EXTRA CODE
// Other code you write will be lost the next time you deploy the project.
// Special characters, e.g., é, ö, à, etc. are supported in comments.

package kafka.actions;

import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.webui.CustomJavaAction;
import kafka.impl.KafkaConsumerRepository;
import kafka.impl.KafkaProcessorRepository;
import kafka.impl.KafkaProducerRepository;
import kafka.impl.KafkaModule;

/**
 * Stops all Kafka Consumers, Producers and Processors.
 * 
 * This action will always return true.
 */
public class StopAll extends CustomJavaAction<java.lang.Boolean>
{
	public StopAll(IContext context)
	{
		super(context);
	}

	@Override
	public java.lang.Boolean executeAction() throws Exception
	{
		// BEGIN USER CODE
		boolean isOk = true;
		try {	
			KafkaProducerRepository.closeAll();
		} catch (Exception e) {
			KafkaModule.LOGGER.error("Failed to close KafkaProducerRepository " + e);
			isOk = false;
		}
		try {	
			KafkaConsumerRepository.stopAll();
		} catch (Exception e) {
			KafkaModule.LOGGER.error("Failed to close KafkaConsumerRepository " + e);
			isOk = false;
		}
		try {	
			KafkaProcessorRepository.closeAll();
		} catch (Exception e) {
			KafkaModule.LOGGER.error("Failed to close KafkaProcessorRepository " + e);
			isOk = false;
		}
		return isOk;
		// END USER CODE
	}

	/**
	 * Returns a string representation of this action
	 */
	@Override
	public java.lang.String toString()
	{
		return "StopAll";
	}

	// BEGIN EXTRA CODE
	// END EXTRA CODE
}
