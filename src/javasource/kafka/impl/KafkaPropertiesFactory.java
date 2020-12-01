package kafka.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Properties;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import encryption.proxies.microflows.Microflows;
import kafka.proxies.Config;
import kafka.proxies.Consumer;
import kafka.proxies.ConsumerConfig;
import kafka.proxies.KeyStore;
import kafka.proxies.Producer;
import kafka.proxies.ProducerConfig;
import kafka.proxies.Server;
import org.apache.commons.io.IOUtils;

public class KafkaPropertiesFactory {

	public static Properties getKafkaProperties(IContext context, Consumer consumer) throws CoreException {
		Properties result = new Properties();
		Server server = consumer.getConsumer_Server();
		Config config = server.getServer_Config();
		ConsumerConfig consumerConfig = consumer.getConsumer_ConsumerConfig();
		appendProperties(result, config.getMendixObject(), context);
		appendProperties(result, consumerConfig.getMendixObject(), context);
		setSSLParameters(result, server, context);
		return result;
	}

	public static Properties getKafkaProperties(IContext context, Producer producer) throws CoreException {
		Properties result = new Properties();
		Server server = producer.getProducer_Server();
		Config config = server.getServer_Config();
		ProducerConfig producerConfig = producer.getProducer_ProducerConfig();
		appendProperties(result, config.getMendixObject(), context);
		appendProperties(result, producerConfig.getMendixObject(), context);
		setSSLParameters(result, server, context);
		return result;
	}

	@Deprecated
	public static Properties getKafkaProperties(IContext context, IMendixObject object) {
		Properties result = new Properties();
		appendProperties(result, object, context);
		return result;
	}


	private static void appendProperties(Properties props, IMendixObject config, IContext context) {
		for (IMendixObjectMember<?> primitive : config.getPrimitives(context)) {
			String key = primitive.getName().replace('_', '.');
			Object value = primitive.getValue(context);
			if (value != null) {
				props.put(key, value);
			}
		}
	}

	private static void setSSLParameters(Properties props, Server server, IContext context)
			throws CoreException {
		try {
			KeyStore trustStore = server.getServer_TrustStore();

			if (trustStore != null && trustStore.getHasContents()) {
				File tmpTrustStoreFile = File.createTempFile("tmp", trustStore.getName());
				try (InputStream fileDocumentContent = Core.getFileDocumentContent(context, trustStore.getMendixObject());
						FileOutputStream output = new FileOutputStream(tmpTrustStoreFile)) {
					IOUtils.copy(fileDocumentContent,
							output);
					props.put("ssl.truststore.password", Microflows.decrypt(context, trustStore.getPassword()));
					props.put("ssl.truststore.location", tmpTrustStoreFile.getPath());
				}
			}
		} catch (Exception e) {
			throw new CoreException("Unable to get truststore: " + e.getMessage(), e);
		}

		try {
			KeyStore keyStore = server.getServer_KeyStore();

			if (keyStore != null && keyStore.getHasContents()) {
				File tmpKeyStoreFile = File.createTempFile("tmp", keyStore.getName());
				try (InputStream fileDocumentContent = Core.getFileDocumentContent(context, keyStore.getMendixObject());
						FileOutputStream output = new FileOutputStream(tmpKeyStoreFile)) {
					IOUtils.copy(fileDocumentContent,
							output);
					props.put("ssl.keystore.password", Microflows.decrypt(context, keyStore.getPassword()));
					props.put("ssl.key.password", Microflows.decrypt(context, keyStore.getPrivateKeyPassword()));
					props.put("ssl.keystore.location", tmpKeyStoreFile.getPath());
				}
			}
		} catch (Exception e) {
			throw new CoreException("Unable to get keystore: " + e.getMessage(), e);
		}
	}
}
