Connect your Mendix app to Apache Kafka.

# Usage
This module allows you to:

- Produce data in Kafka topic
- Consume data from Kafka topics
- Stream data between Kafka topics
- Produce data
- Kafka Produce allows you to write data to a topic.

To produce data, you need to configure a Producer. Within your microflow logic you can choose to publish data in a synchronous manner (blocks until delivery is guaranteed) and asynchronous manner (continue and trust the library to take care of it; which has no guarantee).

# Consume data
Kafka Consume allows you to read data from a topic.

To consume data, configure a consumer and a on-receive microflow.

The microflow takes the following parameters:

Offset (Long): The position of this record in the corresponding Kafka partition.
Key (String): The key (or null if no key is specified)
Value (String): The value
… : any parameters
An example microflow has been created for reference, see Example_OnReceiveMicroflow.

The consumer calls the microflow for each message in the topic. If you only want to start the microflow for certain messages, you can use a filtered processor.

To start the consumer, it is adviced to include this in the after startup microflow. An example has been created in Example_AfterStartupConsumer.

# Stream data
Kafka Streams allows you to read data from one topic, and write data to another.

To stream data between two topics, you need to start a processor. All configuration options are available. However, the only configuration information it needs is the Kafka server to connect to, the topic to read from, and a microflow.

The microflow takes the following parameters:

Offset (Long): The position of this record in the corresponding Kafka partition.
Key (String): The key (or null if no key is specified)
Value (String): The value
It may return one of the following:

List of KeyValuePair: All these messages will be sent to the output topic.
KeyValuePair: This message will be sent to the output topic.
String: A message with that value (and no key) will be sent to the output topic
Nothing: No messages will be sent to the output topic.
If you start a processor without an output topic, it behaves like a consumer in the sense that it only reads and does not write.

The consumer calls the microflow for each message in the input topic.

# Filtering
You can filter the messages that cause the microflow to execute by starting a filtered processor. This works for JSON messages only, and only when you want to filter on a specific part of the JSON message, such as a property value.

# Shut down
Make sure you call the 'Stop all Kafka connections' action before the app stops, for instance during the 'Before shutdown' microflow.

# Features and limitations
Features:
- Easy to setup Kafka consumers and producers.
- Full SSL support (using keystore and truststores).
- Explorer (discover topics and read messages).
- Support for headers (as consumer and producer).
- Logging from the Kafka library redirected to Mendix (so you can actually see errors and connectivity issues).
- Producing messages using asynchronous and synchronous mode.
- Publish messages through UI.
- Consumer commit control (to force at-least-once processing).

# Limitations

To apply configuration changes it is strongly adviced to restart the application. This because there's no (platform) way of informing all instances about changes of configuration.
In order for the logging to work, SLF4j logging has been redirected through log4j. If any other SLF4j outputs are present, this will probably fail.

# Dependencies
Library Logging module
