package kafka.impl;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import kafka.proxies.AvroConfiguration;
import kafka.proxies.CommitControl;
import kafka.proxies.Consumer;
import kafka.proxies.FailureHandling;
import kafka.proxies.constants.Constants;
import system.proxies.FileDocument;

public class KafkaConsumerRunner extends KafkaConfigurable implements Runnable {
	private final String name;
	private final AtomicBoolean stopped = new AtomicBoolean(false);
	private final CountDownLatch stopLatch = new CountDownLatch(1);

	private KafkaConsumer<String, ?> consumer;
	private final String onReceiveMicroflow;
	private final Map<String, IDataType> onReceiveInputParameters;
	private final CommitControl commitControl;
	private final boolean retryOnFailure;
	private final long retryDelaySeconds;
	private final long maxRetryDelaySeconds;
	// Resolves the schemas of Avro messages, for this thread only; null when the consumer does not use Avro.
	// Saving any Kafka setting restarts the consumers, which empties its cache.
	private final AvroSchemaResolver avroSchemaResolver;

	// Domain model object
	private final Consumer consumerDom;
	private final boolean isByteArray;

	// Partitions that wait for a failed record to be retried
	private final Map<TopicPartition, RetryState> retries = new HashMap<>();

	private static class RetryState {
		final long offset;
		final int attempts;
		final long nextAttemptAt;

		RetryState(long offset, int attempts, long nextAttemptAt) {
			this.offset = offset;
			this.attempts = attempts;
			this.nextAttemptAt = nextAttemptAt;
		}
	}

    public KafkaConsumerRunner(Consumer consumer, IContext context) throws CoreException {
		super(context);
		this.consumerDom = consumer;
		this.name = consumer.getName();
		this.props = KafkaPropertiesFactory.getKafkaProperties(context, consumer);
		this.commitControl = consumerDom.getCommitControl();
		// Saving any Kafka setting restarts the consumers, so the failure settings can be read once.
		this.retryOnFailure = consumerDom.getOnFailure() == FailureHandling.RETRY;
		this.retryDelaySeconds = consumerDom.getRetryDelaySeconds() == null ? 5 : Math.max(1, consumerDom.getRetryDelaySeconds());
		this.maxRetryDelaySeconds = consumerDom.getMaxRetryDelaySeconds() == null ? 300
				: Math.max(this.retryDelaySeconds, consumerDom.getMaxRetryDelaySeconds());
		AvroConfiguration avroConfiguration = consumerDom.getConsumer_AvroConfiguration(context);
		this.avroSchemaResolver = avroConfiguration == null ? null : new AvroSchemaResolver(avroConfiguration);
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
		
		if (avroSchemaResolver != null && !isByteArray) {
			throw new CoreException("Consumer " + this.name + " uses Avro configuration " + avroSchemaResolver.getConfigurationName()
					+ ", but its value deserializer is not ByteArrayDeserializer. Save the consumer to set it.");
		}
		if (isByteArray && avroSchemaResolver == null) {
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
		try {
		LOGGER.debug("Started consumer "+consumerDom.getName());
		consumer.subscribe(Arrays.asList(this.consumerDom.getTopics().split(";")));
		while (!stopped.get()) {
			try {
				resumeDueRetries();
				ConsumerRecords<String, ?> records = consumer.poll(Duration.ofMillis(100));
				// Partitions that failed in this batch: their later records wait for the retry
				Set<TopicPartition> waiting = new HashSet<>();
				for (ConsumerRecord<String, ?> record : records) {
					TopicPartition partition = new TopicPartition(record.topic(), record.partition());
					if (waiting.contains(partition)) {
						continue;
					}
					try {
						processRecord(record);
						succeeded(partition, record);
					} catch (Throwable e) {
						if (handleFailure(partition, record, e)) {
							waiting.add(partition);
						}
					}
				}
				// A partition that waits for a retry was moved back to its failed record, so the commit stops there.
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
		} finally {
			// Closing sends a LeaveGroup request, so the group is left immediately instead of after the session timeout.
			try {
				consumer.close(Duration.ofSeconds(10));
			} catch (Exception e) {
				LOGGER.warn("Failed to close Kafka consumer " + name, e);
			}
			stopLatch.countDown();
		}
	}

	/**
	 * Decodes one record and passes it to the on-receive microflow.
	 * @throws Exception when the value cannot be decoded or one of the microflows fails
	 */
	private void processRecord(ConsumerRecord<String, ?> record) throws Exception {
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

		// Set for an Avro message; passed after the headers, so a header of the same name does not replace them
		AvroProcessor.DecodedMessage decoded = null;
		Integer schemaId = null;
		if (this.onReceiveInputParameters.containsKey("Value")) {
			if (record.value() instanceof byte[]) {
				if(avroSchemaResolver != null) {
					AvroProcessor.AvroMessage message = AvroProcessor.unframe((byte[]) record.value(), avroSchemaResolver.getFormat());
					AvroSchemaResolver.ReadSchemas schemas = avroSchemaResolver.resolveForReading(context, message.schemaId,
							record.topic(), consumerDom.getMendixObject(),
							() -> AvroSchemaResolver.recordValues(record, consumerDom.getMendixObject()));
					decoded = AvroProcessor.decode(schemas.writer, schemas.reader, message.data);
					schemaId = message.schemaId;
					microflowParams.put("Value", decoded.json);
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

		if (decoded != null) {
			if (this.onReceiveInputParameters.containsKey("RecordName")) {
				microflowParams.put("RecordName", decoded.recordName);
			}
			if (this.onReceiveInputParameters.containsKey("SchemaId")) {
				microflowParams.put("SchemaId", schemaId == null ? null : Long.valueOf(schemaId));
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
			endTransactions(context);
			throw new CoreException("The on-receive microflow " + onReceiveMicroflow + " failed.", e);
		} 
	}

	/**
	 * Logs a failed record. With retry, moves the partition back to the record and pauses it until the next
	 * attempt is due.
	 * @return true when the record will be retried
	 */
	private boolean handleFailure(TopicPartition partition, ConsumerRecord<String, ?> record, Throwable e) {
		String location = "topic " + record.topic() + ", partition " + record.partition() + ", offset " + record.offset();
		RetryState previous = retries.get(partition);
		int attempts = previous != null && previous.offset == record.offset() ? previous.attempts + 1 : 1;
		long delayMillis = retryDelayMillis(attempts);
		if (retryOnFailure) {
			retries.put(partition, new RetryState(record.offset(), attempts, System.currentTimeMillis() + delayMillis));
			consumer.seek(partition, record.offset());
			consumer.pause(Collections.singleton(partition));
		}

		String message = "Consumer " + name + " failed to process the message at " + location + "; "
				+ (retryOnFailure ? "attempt " + attempts + ", retrying in " + (delayMillis / 1000) + " s."
						: "continuing with the next message.");
		if (attempts == 1) {
			LOGGER.error(message, e);
		} else {
			// The stack trace was logged with the first attempt
			LOGGER.warn(message + " " + describe(e));
		}
		return retryOnFailure;
	}

	/**
	 * The delay before retry number {@code attempts}: the retry delay, doubled per attempt, up to the maximum.
	 */
	private long retryDelayMillis(int attempts) {
		long delay = retryDelaySeconds << Math.min(attempts - 1, 30);
		return Math.min(delay, maxRetryDelaySeconds) * 1000;
	}

	/**
	 * Resumes the partitions whose next attempt is due, and forgets partitions this consumer no longer owns.
	 */
	private void resumeDueRetries() {
		if (retries.isEmpty()) {
			return;
		}
		Set<TopicPartition> assigned = consumer.assignment();
		Set<TopicPartition> paused = consumer.paused();
		long now = System.currentTimeMillis();
		retries.entrySet().removeIf(entry -> !assigned.contains(entry.getKey()));
		for (Map.Entry<TopicPartition, RetryState> entry : retries.entrySet()) {
			if (entry.getValue().nextAttemptAt <= now && paused.contains(entry.getKey())) {
				consumer.resume(Collections.singleton(entry.getKey()));
			}
		}
	}

	/**
	 * Ends the retry of a partition once its failed record, or a later one, has been processed.
	 */
	private void succeeded(TopicPartition partition, ConsumerRecord<String, ?> record) {
		RetryState state = retries.get(partition);
		if (state == null || record.offset() < state.offset) {
			return;
		}
		retries.remove(partition);
		LOGGER.info("Consumer " + name + " processed the message at topic " + record.topic() + ", partition "
				+ record.partition() + ", offset " + state.offset + " after " + state.attempts + " failed attempt(s).");
	}

	private static String describe(Throwable e) {
		StringBuilder description = new StringBuilder(e.toString());
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			description.append(" | caused by: ").append(cause.toString());
		}
		return description.toString();
	}

	private static void endTransactions(IContext context) {
		try {
			while (context.isInTransaction())
				context.endTransaction();
		} catch (Exception ex) {};
	}

	// Shutdown hook which can be called from a separate thread
	public void stop() {
		stopped.set(true);
		consumer.wakeup();
	}

	/**
	 * Waits until this runner has left the consumer group and released its resources.
	 * @return false when the runner is still running after the timeout
	 */
	public boolean awaitStop(long timeoutMillis) throws InterruptedException {
		return stopLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
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
