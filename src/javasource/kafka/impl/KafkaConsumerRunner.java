package kafka.impl;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import kafka.proxies.CommitControl;
import kafka.proxies.Consumer;
import kafka.proxies.constants.Constants;
import system.proxies.FileDocument;

public class KafkaConsumerRunner extends KafkaConfigurable implements Runnable {
	private final String name;
	private final AtomicBoolean stopped = new AtomicBoolean(false);

	private KafkaConsumer<String, ?> consumer;
	private final String onReceiveMicroflow;
	private final Map<String, IDataType> onReceiveInputParameters;
	private final String getAvroSchemaMicroflow;
	private final Map<String, IDataType> getAvroSchemaInputParameters;
	private final CommitControl commitControl;

	// Domain model object
	private final Consumer consumerDom;
	private final boolean isByteArray;

    public KafkaConsumerRunner(Consumer consumer, IContext context) throws CoreException {
		super(context);
		this.consumerDom = consumer;
		this.name = consumer.getName();
		this.props = KafkaPropertiesFactory.getKafkaProperties(context, consumer);
		this.commitControl = consumerDom.getCommitControl();
		switch (commitControl) {
		case CONSUMER:
			props.put("enable.auto.commit", "false");
			break;
		case SERVER:
			props.put("enable.auto.commit", "true");
			break;
		default:
			LOGGER.critical("Consumer " + name + " contains an invalid commit control.");
		}
		
		this.isByteArray = this.props.get("value.deserializer").equals("org.apache.kafka.common.serialization.ByteArrayDeserializer");
		
		this.consumer = isByteArray ? new KafkaConsumer<String, byte[]>(props) : new KafkaConsumer<String, String>(props);
		this.context = context;
		this.onReceiveMicroflow = consumer.getOnReceiveMicroflow();
		this.onReceiveInputParameters = Core.getInputParameters(this.onReceiveMicroflow);
		
		this.getAvroSchemaMicroflow = consumer.getGetAvroSchemaMicroflow();
		this.getAvroSchemaInputParameters = Core.getInputParameters(this.getAvroSchemaMicroflow);
		
		if (isByteArray && !consumerDom.getUseAvro()) {
			if (this.onReceiveInputParameters.containsKey("Value")) {
				IDataType dataType = this.onReceiveInputParameters.get("Value");
				if (!Core.getMetaObject(dataType.getObjectType()).isFileDocument()) {
					throw new CoreException("The 'Value' input parameter of microflow " + onReceiveMicroflow
							+ " must be of type 'FileDocument' when the consumer " + this.name
							+ " is configured to use ByteArrayDeserializer.");
				}
			}
		}
	}

	public void run() {
		LOGGER.debug("Started consumer "+consumerDom.getName());
		consumer.subscribe(Arrays.asList(this.consumerDom.getTopics().split(";")));
		while (!stopped.get()) {
			try {
				ConsumerRecords<String, ?> records = consumer.poll(Duration.ofMillis(100));
				for (ConsumerRecord<String, ?> record : records) {
					IContext context = Core.createSystemContext();
					Map<String, Object> microflowParams = new HashMap<>();
					LOGGER.debug(consumerDom.getName()+" received message for partition "+record.partition()+
							", offset "+record.offset()+", topic "+record.topic());
					if (this.onReceiveInputParameters.containsKey("Offset")) {
						microflowParams.put("Offset", record.offset());
					}
					if (this.onReceiveInputParameters.containsKey("Partition")) {
						microflowParams.put("Partition", record.partition());
					}
					if (this.onReceiveInputParameters.containsKey("Consumer")) {
						microflowParams.put("Consumer", consumerDom.getMendixObject());
					}
					if (this.onReceiveInputParameters.containsKey("Topic")) {
						microflowParams.put("Topic", record.topic());
					}

					if (this.onReceiveInputParameters.containsKey("Key")) {
						microflowParams.put("Key", record.key());
					}

					if (this.onReceiveInputParameters.containsKey("Value")) {
						if (record.value() instanceof byte[]) {
							if(consumerDom.getUseAvro()) {
								byte[] payload = (byte[]) record.value();
								int schemaId = -1;
								if(payload!=null && payload.length>4) {
									ByteBuffer buffer = ByteBuffer.wrap(payload);
									byte magicByte = buffer.get(); 
							        if (magicByte == 0) {
							        	//TODO: trigger MF to determine schema
							        	schemaId = buffer.getInt(); 
							        	payload = Arrays.copyOfRange(payload, 5, payload.length);
							        }
								}
								String schema = null;
								if(getAvroSchemaMicroflow!=null) {
									Map<String, Object> getAvroSchemaMicroflowParams = new HashMap<>();
									if (this.getAvroSchemaInputParameters.containsKey("Offset")) {
										getAvroSchemaMicroflowParams.put("Offset", record.offset());
									}
									if (this.getAvroSchemaInputParameters.containsKey("Partition")) {
										getAvroSchemaMicroflowParams.put("Partition", record.partition());
									}
									if (this.getAvroSchemaInputParameters.containsKey("Consumer")) {
										getAvroSchemaMicroflowParams.put("Consumer", consumerDom.getMendixObject());
									}
									if (this.getAvroSchemaInputParameters.containsKey("Topic")) {
										getAvroSchemaMicroflowParams.put("Topic", record.topic());
									}

									if (this.getAvroSchemaInputParameters.containsKey("Key")) {
										getAvroSchemaMicroflowParams.put("Key", record.key());
									}
									if (this.getAvroSchemaInputParameters.containsKey("SchemaId")) {
										getAvroSchemaMicroflowParams.put("SchemaId", schemaId);
									}
									for (Header header : record.headers()) {
										try {
											if (this.getAvroSchemaInputParameters.containsKey(header.key())) {
												getAvroSchemaMicroflowParams.put(header.key(), new String(header.value(), "UTF-8"));
											}
										} catch (Exception e) {
											LOGGER.warn("Ignoring header " + header.key() + " for offset " + record.offset()
													+ " because it has an invalid value.");
										}
									}
									try {
										schema = Core.microflowCall(getAvroSchemaMicroflow).withParams(getAvroSchemaMicroflowParams).execute(context);	// throws CoreException
										while (context.isInTransaction())
											context.endTransaction();
									} catch (Throwable e) {
										LOGGER.error("An error occurred while executing the microflow for consumer " + name, e);
										try {
											while (context.isInTransaction())
												context.endTransaction();
										} catch (Exception ex) {};
									} 

								}
								if(schema==null) {
									microflowParams.put("Value", AvroProcessor.decodeAvro(
											consumerDom.getAvroSchemaHash(), consumerDom.getAvroSchema(), payload));									
								}else {
									microflowParams.put("Value", AvroProcessor.decodeAvro(
											schema, schema, payload));
								}


							}else {
								String entityType = this.onReceiveInputParameters.get("Value").getObjectType();
								microflowParams.put("Value", createFileDocumentFromByteArray(context, record.key(), (byte[]) record.value(), entityType));
							}
						} else if (record.value() instanceof String) {
							microflowParams.put("Value", (String) record.value());
						} else {
							LOGGER.warn("Ignoring value for offset " + record.offset() + " because it has an invalid value type.");
						}
					}

					for (Header header : record.headers()) {
						try {
							if (this.onReceiveInputParameters.containsKey(header.key())) {
								microflowParams.put(header.key(), new String(header.value(), "UTF-8"));
							}
						} catch (Exception e) {
							LOGGER.warn("Ignoring header " + header.key() + " for offset " + record.offset()
									+ " because it has an invalid value.");
						}
					}

					if (this.onReceiveInputParameters.containsKey("Headers")) {
						IDataType dataType = this.onReceiveInputParameters.get("Headers");
						if (dataType.isList() && kafka.proxies.Header.entityName.equals(dataType.getObjectType())) {
							List<IMendixObject> headerObjects = new ArrayList<>();
							for (Header header : record.headers()) {
								try {
									kafka.proxies.Header headerProxy = new kafka.proxies.Header(context);
									headerProxy.setKey(context, header.key());
									headerProxy.setValue(context, new String(header.value(), "UTF-8"));
									headerObjects.add(headerProxy.getMendixObject());
								} catch (Exception e) {
									LOGGER.warn("Ignoring header " + header.key() + " for offset " + record.offset()
											+ " because it has an invalid value.");
								}
							}
							microflowParams.put("Headers", headerObjects);
						}
					}
					
					try {
						Core.microflowCall(onReceiveMicroflow).withParams(microflowParams).execute(context);	// throws CoreException
						while (context.isInTransaction())
							context.endTransaction();
					} catch (Throwable e) {
						LOGGER.error("An error occurred while executing the microflow for consumer " + name, e);
						try {
							while (context.isInTransaction())
								context.endTransaction();
						} catch (Exception ex) {};
					} 
				}
				if (commitControl == CommitControl.CONSUMER) {
					consumer.commitSync();
				}
			} catch (WakeupException e) {
				// Ignore exception if closing
				if (!stopped.get()) throw e;
			} catch (Exception e) {
				String msg = "An uncatched exception occurred on Kafka consumer " + name + " expect to (temporary) have a consumer less.";
				if(Constants.getLogConsumerLostAsCritical()) {
					LOGGER.critical(msg, e);
				} else {
					LOGGER.error(msg, e);
				}
				
				try { Thread.sleep(30000); } catch (Exception ex) {}
			}
		}

	}

	// Shutdown hook which can be called from a separate thread
	public void stop() {
		stopped.set(true);
		consumer.wakeup();
	}
	
	private IMendixObject createFileDocumentFromByteArray(IContext context, String preferredName, byte[] payload, String entityType) 
		throws CoreException {

		IMendixObject mendixObject = Core.instantiate(context, entityType);
		FileDocument fileDocument = FileDocument.initialize(context, mendixObject);
		fileDocument.setName(preferredName != null ? preferredName : "kafka_message_" + System.currentTimeMillis());
		Core.storeFileDocumentContent(context, mendixObject, new ByteArrayInputStream(payload));
		return mendixObject;
	}
}
