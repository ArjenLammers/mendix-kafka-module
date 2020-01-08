// This file was generated by Mendix Modeler 6.10.
//
// WARNING: Code you write here will be lost the next time you deploy the project.

package kafkamodule.proxies.microflows;

import java.util.HashMap;
import java.util.Map;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.MendixRuntimeException;
import com.mendix.systemwideinterfaces.core.IContext;

public class Microflows
{
	// These are the microflows for the KafkaModule module
	public static void aCr_KafkaConsumer(IContext context, kafkamodule.proxies.KafkaConsumer _kafkaConsumer)
	{
		try
		{
			Map<java.lang.String, Object> params = new HashMap<java.lang.String, Object>();
			params.put("KafkaConsumer", _kafkaConsumer == null ? null : _kafkaConsumer.getMendixObject());
			Core.execute(context, "KafkaModule.ACr_KafkaConsumer", params);
		}
		catch (CoreException e)
		{
			throw new MendixRuntimeException(e);
		}
	}
	public static void aCr_KafkaProducer(IContext context, kafkamodule.proxies.KafkaProducer _kafkaProducer)
	{
		try
		{
			Map<java.lang.String, Object> params = new HashMap<java.lang.String, Object>();
			params.put("KafkaProducer", _kafkaProducer == null ? null : _kafkaProducer.getMendixObject());
			Core.execute(context, "KafkaModule.ACr_KafkaProducer", params);
		}
		catch (CoreException e)
		{
			throw new MendixRuntimeException(e);
		}
	}
	public static void aCr_KafkaServer(IContext context, kafkamodule.proxies.KafkaServer _kafkaServer)
	{
		try
		{
			Map<java.lang.String, Object> params = new HashMap<java.lang.String, Object>();
			params.put("KafkaServer", _kafkaServer == null ? null : _kafkaServer.getMendixObject());
			Core.execute(context, "KafkaModule.ACr_KafkaServer", params);
		}
		catch (CoreException e)
		{
			throw new MendixRuntimeException(e);
		}
	}
	public static void iVK_SaveKafkaConsumer(IContext context, kafkamodule.proxies.KafkaConsumer _kafkaConsumer)
	{
		try
		{
			Map<java.lang.String, Object> params = new HashMap<java.lang.String, Object>();
			params.put("KafkaConsumer", _kafkaConsumer == null ? null : _kafkaConsumer.getMendixObject());
			Core.execute(context, "KafkaModule.IVK_SaveKafkaConsumer", params);
		}
		catch (CoreException e)
		{
			throw new MendixRuntimeException(e);
		}
	}
	public static void iVK_SaveKafkaProducer(IContext context, kafkamodule.proxies.KafkaProducer _kafkaProducer)
	{
		try
		{
			Map<java.lang.String, Object> params = new HashMap<java.lang.String, Object>();
			params.put("KafkaProducer", _kafkaProducer == null ? null : _kafkaProducer.getMendixObject());
			Core.execute(context, "KafkaModule.IVK_SaveKafkaProducer", params);
		}
		catch (CoreException e)
		{
			throw new MendixRuntimeException(e);
		}
	}
	public static void iVK_SaveKafkaServer(IContext context, kafkamodule.proxies.KafkaServer _kafkaServer)
	{
		try
		{
			Map<java.lang.String, Object> params = new HashMap<java.lang.String, Object>();
			params.put("KafkaServer", _kafkaServer == null ? null : _kafkaServer.getMendixObject());
			Core.execute(context, "KafkaModule.IVK_SaveKafkaServer", params);
		}
		catch (CoreException e)
		{
			throw new MendixRuntimeException(e);
		}
	}
	/**
	 * Stops all active Kafka connections. Use this as (part of) your 'Before shutdown' microflow.
	 */
	public static void shutdown(IContext context)
	{
		try
		{
			Map<java.lang.String, Object> params = new HashMap<java.lang.String, Object>();
			Core.execute(context, "KafkaModule.Shutdown", params);
		}
		catch (CoreException e)
		{
			throw new MendixRuntimeException(e);
		}
	}
}