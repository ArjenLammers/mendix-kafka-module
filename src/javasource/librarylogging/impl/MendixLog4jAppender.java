package librarylogging.impl;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;

@Plugin(name = "Mendix", category = "Core", elementType = "appender", printObject = true)
public class MendixLog4jAppender extends AbstractAppender {

	private ILogNode logNode;
	private static volatile MendixLog4jAppender instance;
	
	public MendixLog4jAppender(String name, Filter filter, String mendixLogNode) {
		super(name, filter, null); 
		logNode = Core.getLogger(mendixLogNode);
	}

	@PluginFactory
	  public static MendixLog4jAppender createAppender(@PluginAttribute("name") String name,
	  @PluginAttribute("ignoreExceptions") boolean ignoreExceptions,
	  @PluginElement("Layout") Layout layout,
	  @PluginElement("Filters") Filter filter,
	  @PluginAttribute("logNode") String logNode) {
	    if (layout == null) {
	      layout = PatternLayout.createDefaultLayout();
	  }

	    instance = new MendixLog4jAppender(name, filter, logNode);
	 return instance;
	 }
	
	public MendixLog4jAppender getInstance() {
		return instance;
	}
	
	@Override
	public void append(LogEvent event) {
		String formattedMessage = event.getMessage().getFormattedMessage();
		String message;
		
		if (event.getMarker() != null) {
			String marker = event.getMarker().getName();
			message = event.getLoggerName() + " - " + marker + " " + formattedMessage;
		} else {
			message = event.getLoggerName() + " - " + formattedMessage;
		}

		if (event.getLevel() == Level.TRACE) {
			if (logNode.isTraceEnabled()) {
				logNode.trace(message, event.getThrown());
			}
			return;
		}
		if (event.getLevel() == Level.DEBUG) {
			if (logNode.isDebugEnabled()) {
				logNode.debug(message, event.getThrown());
			}
			return;
		}
		
		if (event.getLevel() == Level.INFO) {
			logNode.info(message, event.getThrown());
			return;
		}
		
		if (event.getLevel() == Level.WARN) {
			logNode.warn(message, event.getThrown());
			return;
		}
		
		if (event.getLevel() == Level.ERROR) {
			logNode.error(message, event.getThrown());
			return;
		}
		
		if (event.getLevel() == Level.FATAL) {
			logNode.critical(message, event.getThrown());
			return;
		}
	}
}
