package kafka.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.core.conf.Configuration;
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

	/**
	 * There are 2 ways of configuring SSL parameters for Kafka in Mendix: 
	 * 	The way using Kafka.KeyStore entities that reference file documents
	 *  The way using runtime-managed certificates exposed via the Runtime API. 
	 *    When runtime-managed certificates are explicitly enabled on the server, 
	 *    they take precedence over any configured Kafka.KeyStore entities.
	 * @param props
	 * @param server
	 * @param context
	 * @throws CoreException
	 */
	private static void setSSLParameters(Properties props, Server server, IContext context)
			throws CoreException {
		// Runtime-managed certificates take precedence when explicitly enabled on the server.
		if (Boolean.TRUE.equals(server.getUseRuntimeCertificates(context))) {
			setRuntimeSSLParameters(props);
		} else {
			setEntitySSLParameters(props, server, context);
		}
	}

	private static void setRuntimeSSLParameters(Properties props) throws CoreException {
		Configuration configuration = Core.getConfiguration();

		try {
			setRuntimeTrustStore(props, configuration);
		} catch (Exception e) {
			throw new CoreException("Unable to get runtime CA certificates: " + e.getMessage(), e);
		}

		try {
			setRuntimeKeyStore(props, configuration);
		} catch (Exception e) {
			throw new CoreException("Unable to get runtime client certificates: " + e.getMessage(), e);
		}
	}

	private static void setRuntimeTrustStore(Properties props, Configuration configuration) throws Exception {
		List<InputStream> caCertificates = configuration.getCACertificates();
		if (caCertificates == null || caCertificates.isEmpty()) {
			return;
		}

		// Kafka expects a truststore file, so we materialize Runtime API CA cert streams into a temp JKS.
		java.security.KeyStore trustStore = java.security.KeyStore.getInstance("JKS");
		String trustStorePassword = UUID.randomUUID().toString();
		char[] trustStorePasswordChars = trustStorePassword.toCharArray();
		trustStore.load(null, trustStorePasswordChars);

		CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
		int index = 0;
		for (InputStream certificateStream : caCertificates) {
			if (certificateStream == null) {
				continue;
			}

			try (InputStream stream = certificateStream) {
				Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(stream);
				for (Certificate certificate : certificates) {
					trustStore.setCertificateEntry("mx-runtime-ca-" + index, certificate);
					index++;
				}
			}
		}

		if (index == 0) {
			return;
		}

		File tmpTrustStoreFile = File.createTempFile("mx-runtime-truststore-", ".jks");
		tmpTrustStoreFile.deleteOnExit();
		try (FileOutputStream output = new FileOutputStream(tmpTrustStoreFile)) {
			trustStore.store(output, trustStorePasswordChars);
		}

		props.put("ssl.truststore.password", trustStorePassword);
		props.put("ssl.truststore.location", tmpTrustStoreFile.getPath());
		props.put("ssl.truststore.type", "JKS");
	}

	private static void setRuntimeKeyStore(Properties props, Configuration configuration) throws Exception {
		List<InputStream> clientCertificates = configuration.getClientCertificates();
		if (clientCertificates == null || clientCertificates.isEmpty() || clientCertificates.get(0) == null) {
			return;
		}

		List<String> clientCertificatePasswords = configuration.getClientCertificatePasswords();
		String keyStorePassword = "";
		if (clientCertificatePasswords != null && !clientCertificatePasswords.isEmpty()
				&& clientCertificatePasswords.get(0) != null) {
			keyStorePassword = clientCertificatePasswords.get(0);
		}

		File tmpKeyStoreFile = File.createTempFile("mx-runtime-keystore-", ".p12");
		tmpKeyStoreFile.deleteOnExit();
		// Runtime client certificates are exposed as PKCS12 streams and copied to a temp file for Kafka.
		try (InputStream certificateStream = clientCertificates.get(0);
				FileOutputStream output = new FileOutputStream(tmpKeyStoreFile)) {
			IOUtils.copy(certificateStream, output);
		}

		props.put("ssl.keystore.password", keyStorePassword);
		props.put("ssl.key.password", keyStorePassword);
		props.put("ssl.keystore.location", tmpKeyStoreFile.getPath());
		props.put("ssl.keystore.type", "PKCS12");
	}

	private static void setEntitySSLParameters(Properties props, Server server, IContext context)
			throws CoreException {
		// Path: resolve truststore from associated Kafka.KeyStore file document entity.
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

		// Path: resolve client keystore from associated Kafka.KeyStore file document entity.
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
