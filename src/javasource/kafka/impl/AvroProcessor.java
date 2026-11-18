package kafka.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mendix.core.Core;
import com.mendix.logging.ILogNode;

import kafka.proxies.SchemaIdFormat;

/**
 * Utility class for converting between JSON-encoded data and the binary Avro
 * format using the Apache Avro library (org.apache.avro).
 */
public class AvroProcessor {
	
	protected final static ILogNode LOGGER = Core.getLogger("Kafka");	
	
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
	private static final int SCHEMA_CACHE_SIZE = 100;

	// Parsed schemas by schema text, shared by all producer and consumer threads; the least recently used schema is
	// dropped beyond the limit.
	private static final Map<String, Schema> schemaCache = Collections.synchronizedMap(
			new LinkedHashMap<String, Schema>(16, 0.75f, true) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Schema> eldest) {
					return size() > SCHEMA_CACHE_SIZE;
				}
			});

	/**
	 * Parses an Avro schema, or returns it from the cache when the same schema text was parsed before.
	 */
	public static Schema parseSchemaCached(String schema) {
        Schema avroSchema = schemaCache.get(schema);
        if(avroSchema==null) {
        	// Two threads may parse the same schema at the same time; both results are equal.
        	LOGGER.info("Initializing AVRO schema ...");
        	avroSchema = new Schema.Parser().parse(schema);
        	schemaCache.put(schema, avroSchema);
        }
		return avroSchema;
	}

	/**
	 * An Avro message split into the schema ID it refers to and its Avro data.
	 */
	public static class AvroMessage {
		/** The schema ID, or null when the message has none (schema ID format None) */
		public final Integer schemaId;
		public final byte[] data;

		AvroMessage(Integer schemaId, byte[] data) {
			this.schemaId = schemaId;
			this.data = data;
		}
	}

	/**
	 * A decoded Avro message: its JSON and the full name of the record it holds.
	 */
	public static class DecodedMessage {
		public final String json;
		/** The full name of the decoded schema (e.g. shop.Order); for a union, of the type this message holds */
		public final String recordName;

		DecodedMessage(String json, String recordName) {
			this.json = json;
			this.recordName = recordName;
		}
	}

	/**
	 * Splits a message into its schema ID and Avro data according to the Avro configuration's schema ID format.
	 *
	 * @param payload the message value
	 * @param format  how the message refers to its schema; null is treated as Confluent
	 * @throws IOException when the format is Confluent and the message does not start with byte 0 and a schema ID
	 */
	public static AvroMessage unframe(byte[] payload, SchemaIdFormat format) throws IOException {
		if (format == SchemaIdFormat.NONE) {
			return new AvroMessage(null, payload);
		}
		if (payload == null || payload.length < 5 || payload[0] != 0) {
			throw new IOException("The message does not start with byte 0 and a 4-byte schema ID (Confluent format)."
					+ " Set the Avro configuration's schema ID format to None for plain Avro messages.");
		}
		int schemaId = ByteBuffer.wrap(payload, 1, 4).getInt();
		return new AvroMessage(schemaId, Arrays.copyOfRange(payload, 5, payload.length));
	}
	
    /**
     * Encodes JSON data into the binary Avro format according to the given schema and, when a schema ID is given,
     * prepends it according to Confluent's wire format (magic byte 0, then the schema ID in bytes 1-4).
     *
     * @param avroSchema the parsed Avro schema
     * @param content    a String containing the JSON-encoded data to encode
     * @param schemaId   the schema ID to prepend, or null to write the Avro data only
     * @return the binary Avro representation of the data as a byte array
     * @throws IOException if the content cannot be encoded
     */
    public static byte[] encodeAvro(Schema avroSchema, String content, Integer schemaId) throws IOException {
        // The Avro JsonDecoder is strict and requires every field defined by the
        // schema to be present in the JSON input. Omitting an optional field would
        // otherwise cause the decoder to raise an error. To honor the schema's
        // default values, the JSON is normalized first by inserting the declared
        // default for any field that is missing from the input.
        JsonNode normalized = fillDefaults(avroSchema, MAPPER.readTree(content));
        // Values of Avro's "decimal" logical type are backed by bytes/fixed and are
        // therefore rejected by the JSON decoder when supplied as plain numbers.
        // Rewrite any decimal field expressed as a floating point number into the
        // Avro JSON byte encoding the decoder expects. Optional string fields given
        // as plain JSON strings are wrapped into their Avro JSON branch form.
        JsonNode converted = convertValues(avroSchema, normalized, true);
        String normalizedContent = MAPPER.writeValueAsString(converted);

        DatumReader<Object> reader = new GenericDatumReader<>(avroSchema);
        try (InputStream contentStream =
                     new ByteArrayInputStream(normalizedContent.getBytes(StandardCharsets.UTF_8))) {
            Decoder jsonDecoder = DecoderFactory.get().jsonDecoder(avroSchema, contentStream);
            Object datum = reader.read(null, jsonDecoder);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder binaryEncoder = EncoderFactory.get().binaryEncoder(out, null);
            DatumWriter<Object> writer = new GenericDatumWriter<>(avroSchema);
            writer.write(datum, binaryEncoder);
            binaryEncoder.flush();
            
            byte[] rawPayload = out.toByteArray();
            if(schemaId!=null) {
            	ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + rawPayload.length);
            	buffer.put((byte) 0x00);
            	buffer.putInt(schemaId);
            	buffer.put(rawPayload);
            	return buffer.array();
            }else {
            	return rawPayload;
            }
        }
    }

    /**
     * Decodes binary Avro data into a JSON-encoded String according to the given parsed schema.
     *
     * @param avroSchema the parsed Avro schema
     * @param content    the binary Avro data to decode
     * @return a String containing the JSON representation of the decoded data
     * @throws IOException if the content cannot be decoded
     */
    public static String decodeAvro(Schema avroSchema, byte[] content) throws IOException {
        return decode(avroSchema, null, content).json;
    }

    /**
     * Decodes binary Avro data written with the writer schema into JSON shaped by the reader schema. Avro's schema
     * resolution translates between the two: fields only the writer has are skipped, fields only the reader has get
     * the reader's default, and aliases in the reader schema match renamed fields. The caller checks first that the
     * two schemas are compatible.
     *
     * @param writerSchema the schema the data was written with
     * @param readerSchema the schema to decode to, or null to decode exactly as written
     * @param content      the binary Avro data to decode
     * @throws IOException if the content cannot be decoded
     */
    public static DecodedMessage decode(Schema writerSchema, Schema readerSchema, byte[] content) throws IOException {
        Schema schema = readerSchema != null ? readerSchema : writerSchema;
        DatumReader<Object> reader = new GenericDatumReader<>(writerSchema, schema);
        Decoder binaryDecoder = DecoderFactory.get().binaryDecoder(content, null);
        Object datum = reader.read(null, binaryDecoder);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder jsonEncoder = EncoderFactory.get().jsonEncoder(schema, out);
        DatumWriter<Object> writer = new GenericDatumWriter<>(schema);
        writer.write(datum, jsonEncoder);
        jsonEncoder.flush();

        // The Avro JSON encoder emits "decimal" logical values as byte strings and
        // wraps union values into branch-tagged objects. Rewrite decimals into plain
        // floating point numbers and optional strings into plain JSON strings.
        JsonNode tree = MAPPER.readTree(out.toString(StandardCharsets.UTF_8.name()));
        JsonNode converted = convertValues(schema, tree, false);
        String recordName = schema.getType() == Schema.Type.UNION
                ? schema.getTypes().get(GenericData.get().resolveUnion(schema, datum)).getFullName()
                : schema.getFullName();
        return new DecodedMessage(MAPPER.writeValueAsString(converted), recordName);
    }
    
    
    /**
     * Recursively normalizes a JSON node against the given Avro schema so that any
     * field omitted from the input is populated with the default value declared by
     * the schema. Nested records, arrays, maps and unions are traversed as well.
     *
     * @param schema the Avro schema describing the expected shape of {@code node}
     * @param node   the JSON node to normalize (may be {@code null})
     * @return a JSON node with schema defaults filled in for omitted fields
     */
    private static JsonNode fillDefaults(Schema schema, JsonNode node) {
        switch (schema.getType()) {
            case RECORD:
                return fillRecordDefaults(schema, node);
            case UNION:
                return fillUnionDefaults(schema, node);
            case ARRAY:
                if (node != null && node.isArray()) {
                    ArrayNode result = MAPPER.createArrayNode();
                    for (JsonNode element : node) {
                        result.add(fillDefaults(schema.getElementType(), element));
                    }
                    return result;
                }
                return node;
            case MAP:
                if (node != null && node.isObject()) {
                    ObjectNode result = MAPPER.createObjectNode();
                    for (Map.Entry<String, JsonNode> entry : iterable(node)) {
                        result.set(entry.getKey(),
                                fillDefaults(schema.getValueType(), entry.getValue()));
                    }
                    return result;
                }
                return node;
            default:
                return node;
        }
    }

    /**
     * Normalizes a JSON object against a record schema, inserting declared default
     * values for any field that is not present in the input.
     */
    private static JsonNode fillRecordDefaults(Schema record, JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        ObjectNode result = MAPPER.createObjectNode();
        for (Schema.Field field : record.getFields()) {
            if (node.has(field.name())) {
                result.set(field.name(), fillDefaults(field.schema(), node.get(field.name())));
            } else if (field.hasDefaultValue()) {
                result.set(field.name(), encodeDefault(field));
            }
            // A required field without a default is intentionally left out so that
            // the Avro decoder still reports it as a genuine error.
        }
        return result;
    }

    /**
     * Normalizes a JSON value that belongs to a union type, recursing into the
     * matching branch so that nested defaults are honored.
     */
    private static JsonNode fillUnionDefaults(Schema union, JsonNode node) {
        if (node == null || node.isNull()) {
            // Encodes the null branch of the union; nothing to fill.
            return node;
        }
        if (node.isObject() && node.size() == 1) {
            String branchName = node.fieldNames().next();
            Schema branch = findUnionBranch(union, branchName);
            if (branch != null) {
                ObjectNode wrapped = MAPPER.createObjectNode();
                wrapped.set(branchName, fillDefaults(branch, node.get(branchName)));
                return wrapped;
            }
        }
        return node;
    }

    /**
     * Builds the Avro JSON encoding of a field's declared default value. Union
     * defaults are wrapped with their branch type name as required by the Avro
     * JSON encoding (except for the {@code null} branch).
     */
    private static JsonNode encodeDefault(Schema.Field field) {
        Object defaultValue = field.defaultVal();
        Schema fieldSchema = field.schema();
        if (fieldSchema.getType() == Schema.Type.UNION) {
            // The declared default corresponds to the first branch of the union.
            Schema firstBranch = fieldSchema.getTypes().get(0);
            if (firstBranch.getType() == Schema.Type.NULL || isNullDefault(defaultValue)) {
                // The null branch is JSON-encoded as a bare null, not wrapped.
                return MAPPER.getNodeFactory().nullNode();
            }
            ObjectNode wrapped = MAPPER.createObjectNode();
            wrapped.set(firstBranch.getFullName(),
                    fillDefaults(firstBranch, MAPPER.valueToTree(defaultValue)));
            return wrapped;
        }
        if (isNullDefault(defaultValue)) {
            return MAPPER.getNodeFactory().nullNode();
        }
        return fillDefaults(fieldSchema, MAPPER.valueToTree(defaultValue));
    }

    /**
     * Returns whether the given default value represents an Avro {@code null}
     * default (either a Java {@code null} or Avro's {@code NULL_VALUE} sentinel).
     */
    private static boolean isNullDefault(Object defaultValue) {
        return defaultValue == null
                || defaultValue == org.apache.avro.JsonProperties.NULL_VALUE;
    }

    /**
     * Finds the union branch schema whose name matches the given Avro JSON type
     * label, or {@code null} if no branch matches.
     */
    private static Schema findUnionBranch(Schema union, String branchName) {
        for (Schema branch : union.getTypes()) {
            if (branch.getFullName().equals(branchName)) {
                return branch;
            }
        }
        return null;
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode node) {
        return node::fields;
    }

    /**
     * Recursively walks a JSON node against its Avro schema and converts values
     * between the external JSON representation and the Avro JSON representation.
     *
     * <p>Values of the {@code decimal} logical type are converted between plain
     * floating point numbers (external) and the Avro JSON byte-string encoding
     * (Avro). Values of a union consisting only of {@code string} and {@code null}
     * are converted between a plain JSON string (external) and the branch-tagged
     * object {@code {"string": ...}} (Avro).</p>
     *
     * @param schema the Avro schema describing {@code node}
     * @param node   the JSON node to convert (may be {@code null})
     * @param toAvro {@code true} to convert into the Avro JSON representation,
     *               {@code false} to convert back into the external one
     * @return a JSON node converted in the requested direction
     */
    private static JsonNode convertValues(Schema schema, JsonNode node, boolean toAvro) {
        if (node == null) {
            return node;
        }
        LogicalType logicalType = schema.getLogicalType();
        if (logicalType instanceof LogicalTypes.Decimal) {
            LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) logicalType;
            return toAvro
                    ? decimalToAvroBytes(schema, decimal, node)
                    : avroBytesToDecimal(decimal, node);
        }
        switch (schema.getType()) {
            case RECORD:
                if (node.isObject()) {
                    ObjectNode result = MAPPER.createObjectNode();
                    for (Schema.Field field : schema.getFields()) {
                        if (node.has(field.name())) {
                            result.set(field.name(),
                                    convertValues(field.schema(), node.get(field.name()), toAvro));
                        }
                    }
                    return result;
                }
                return node;
            case UNION:
                return convertUnionValues(schema, node, toAvro);
            case ARRAY:
                if (node.isArray()) {
                    ArrayNode result = MAPPER.createArrayNode();
                    for (JsonNode element : node) {
                        result.add(convertValues(schema.getElementType(), element, toAvro));
                    }
                    return result;
                }
                return node;
            case MAP:
                if (node.isObject()) {
                    ObjectNode result = MAPPER.createObjectNode();
                    for (Map.Entry<String, JsonNode> entry : iterable(node)) {
                        result.set(entry.getKey(),
                                convertValues(schema.getValueType(), entry.getValue(), toAvro));
                    }
                    return result;
                }
                return node;
            default:
                return node;
        }
    }

    /**
     * Converts the values contained in a union-typed JSON node, recursing into the
     * wrapped branch that carries the value. A union consisting only of
     * {@code string} and {@code null} is represented as a plain JSON string in the
     * external representation and is therefore wrapped or unwrapped here.
     */
    private static JsonNode convertUnionValues(Schema union, JsonNode node, boolean toAvro) {
        if (node.isNull()) {
            return node;
        }
        Schema stringBranch = nullableStringBranch(union);
        if (stringBranch != null) {
            String branchName = stringBranch.getFullName();
            if (toAvro) {
                if (node.isTextual()) {
                    ObjectNode wrapped = MAPPER.createObjectNode();
                    wrapped.set(branchName, node);
                    return wrapped;
                }
            } else if (node.isObject() && node.size() == 1 && node.has(branchName)) {
                return node.get(branchName);
            }
            return node;
        }
        if (node.isObject() && node.size() == 1) {
            String branchName = node.fieldNames().next();
            Schema branch = findUnionBranch(union, branchName);
            if (branch != null) {
                ObjectNode wrapped = MAPPER.createObjectNode();
                wrapped.set(branchName, convertValues(branch, node.get(branchName), toAvro));
                return wrapped;
            }
        }
        return node;
    }

    /**
     * Returns the {@code string} branch of a union that consists solely of a
     * {@code string} and a {@code null} branch, or {@code null} if the given union
     * has a different shape.
     */
    private static Schema nullableStringBranch(Schema union) {
        if (union.getTypes().size() != 2) {
            return null;
        }
        Schema stringBranch = null;
        boolean hasNull = false;
        for (Schema branch : union.getTypes()) {
            if (branch.getType() == Schema.Type.STRING) {
                stringBranch = branch;
            } else if (branch.getType() == Schema.Type.NULL) {
                hasNull = true;
            }
        }
        return hasNull ? stringBranch : null;
    }

    /**
     * Encodes a floating point (or textual) JSON number into the Avro JSON byte
     * encoding of a decimal value. The number is scaled to the schema's declared
     * scale and its two's-complement big-endian byte representation is mapped, one
     * byte per character, into an ISO-8859-1 string as required by the Avro JSON
     * encoding of {@code bytes} and {@code fixed}.
     */
    private static JsonNode decimalToAvroBytes(Schema schema, LogicalTypes.Decimal decimal, JsonNode node) {
        if (!node.isNumber() && !node.isTextual()) {
            // Not a numeric value (e.g. already a byte string); leave it untouched.
            return node;
        }
        BigDecimal value = node.isNumber()
                ? node.decimalValue()
                : new BigDecimal(node.asText());
        BigDecimal scaled = value.setScale(decimal.getScale(), RoundingMode.HALF_UP);
        byte[] unscaled = scaled.unscaledValue().toByteArray();
        byte[] bytes;
        if (schema.getType() == Schema.Type.FIXED) {
            int size = schema.getFixedSize();
            bytes = new byte[size];
            // Sign-extend the value across the full fixed width.
            Arrays.fill(bytes, (byte) (scaled.signum() < 0 ? 0xFF : 0x00));
            System.arraycopy(unscaled, 0, bytes, size - unscaled.length, unscaled.length);
        } else {
            bytes = unscaled;
        }
        return MAPPER.getNodeFactory().textNode(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    /**
     * Decodes the Avro JSON byte encoding of a decimal value back into a plain
     * floating point JSON number using the schema's declared scale.
     */
    private static JsonNode avroBytesToDecimal(LogicalTypes.Decimal decimal, JsonNode node) {
        if (!node.isTextual()) {
            return node;
        }
        byte[] bytes = node.asText().getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length == 0) {
            return node;
        }
        BigDecimal value = new BigDecimal(new BigInteger(bytes), decimal.getScale());
        return MAPPER.getNodeFactory().numberNode(value);
    }
    
}
