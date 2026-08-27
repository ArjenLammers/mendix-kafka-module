package kafka.impl;

import java.util.Date;
import java.util.concurrent.Future;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import kafka.proxies.Header;
import kafka.proxies.RecordMetaData;

//Probalby obsolete, use KAfkaSendHelper instead
public class ProducerUtils {

	public static <V> IMendixObject sendSynchronous(V content, boolean useCachedProducer, kafka.proxies.Producer producer,
			IContext context, java.util.List<kafka.proxies.Header> headers, String topic, String key) throws Exception
	{
		KafkaProducer<String, V>  kafkaProducer;
		
		if (useCachedProducer) {
			kafkaProducer = (KafkaProducer<String, V>) KafkaProducerRepository.get(producer.getMendixObject().getId().toLong());
		} else {
			kafkaProducer = new KafkaProducer<String, V>(
					KafkaPropertiesFactory.getKafkaProperties(context, producer));
		}

		ProducerRecord<String, V> producerRecord;
		if (key == null || key.isEmpty()) {
			producerRecord = new ProducerRecord<String, V>(topic, content);
		} else {
			producerRecord = new ProducerRecord<String, V>(topic, key, content);
		}
		
		for (Header header : headers) {
			producerRecord.headers().add(header.getKey(), header.getValue().getBytes());
		}
		
		Future<RecordMetadata> future = kafkaProducer.send(producerRecord);
		RecordMetadata record = future.get();
		RecordMetaData result = new RecordMetaData(context);
		result.setHasOffset(record.hasOffset());
		if (record.hasOffset())
			result.setOffset(record.offset());
		result.setPartition(record.partition());
		if (record.hasTimestamp())
			result.setTimestamp(new Date(record.timestamp()));
		
		if (!useCachedProducer) {
			// if the cache is not used, Producers are created every time we call this JA
			// and they must be closed; unclosed Producers communicate with the broker every 60s 
			// to re-authenticate; for more information see sasl.kerberos.min.time.before.relogin
			kafkaProducer.close();
		}
		
		return result.getMendixObject();
	}
	
	public static <V> boolean sendAsynchronous(V content, boolean useCachedProducer, kafka.proxies.Producer producer,
			IContext context, java.util.List<kafka.proxies.Header> headers, String topic, String key) throws Exception {
		KafkaProducer<String, V>  kafkaProducer;
		
		if (useCachedProducer) {
			kafkaProducer = (KafkaProducer<String, V>) KafkaProducerRepository.get(producer.getMendixObject().getId().toLong());
		} else {
			kafkaProducer = new KafkaProducer<String, V>(
					KafkaPropertiesFactory.getKafkaProperties(context, producer));
		}

		ProducerRecord<String, V> record;
		if (key == null || key.isEmpty()) {
			record = new ProducerRecord<String, V>(topic, content);
		} else {
			record = new ProducerRecord<String, V>(topic, key, content);
		}
		
		for (Header header : headers) {
			record.headers().add(header.getKey(), header.getValue().getBytes());
		}
		
		kafkaProducer.send(record);
		
		if (!useCachedProducer) {
			// if the cache is not used, Producers are created every time we call this JA
			// and they must be closed; unclosed Producers communicate with the broker every 60s 
			// to re-authenticate; for more information see sasl.kerberos.min.time.before.relogin
			kafkaProducer.close();
		}
		
		return true;
	}
	
}
