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
import java.util.HashMap;
import java.util.Arrays;
import java.util.Map;

import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
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

/**
 * Utility class for converting between JSON-encoded data and the binary Avro
 * format using the Apache Avro library (org.apache.avro).
 */
public class AvroProcessor {
	
	protected final static ILogNode LOGGER = Core.getLogger("Kafka");	
	
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
	private static HashMap<String, Schema> schemaCache = new HashMap<String, Schema>();

	private static Schema getSchemaCached(String schemaHash, String schema) {
        Schema avroSchema = schemaCache.get(schemaHash);
        if(avroSchema==null) {	
        	LOGGER.info("Initializing AVRO schema ...");
        	avroSchema = new Schema.Parser().parse(schema);
        	schemaCache.put(schemaHash, avroSchema);
        }
		return avroSchema;
	}
	
    /**
     * Encodes JSON data into the binary Avro format according to the given schema. 
     * Same as encodeAvro(schemaHash, schema, content, 0).
     *
     * @param schemaHash  a hash value of the Avro schema string used to identify the schema
     * @param schema  a String containing the Avro schema (JSON schema definition)
     * @param content a String containing the JSON-encoded data to encode
     * @return the binary Avro representation of the data as a byte array
     * @throws IOException if the schema is invalid or the content cannot be encoded
     */
    public static byte[] encodeAvro(String schemaHash, String schema, String content) throws IOException {
    	return encodeAvro(schemaHash, schema, content, 0);
    }
	
    /**
     * Encodes JSON data into the binary Avro format according to the given schema and prepends the schema id 
     * according to Confluent's wire format (bytes 1-4, prefixed by magic byte).
     *
     * @param schemaHash  a hash value of the Avro schema string used to identify the schema
     * @param schema  a String containing the Avro schema (JSON schema definition)
     * @param content a String containing the JSON-encoded data to encode
     * @return the binary Avro representation of the data as a byte array
     * @throws IOException if the schema is invalid or the content cannot be encoded
     */
    public static byte[] encodeAvro(String schemaHash, String schema, String content, int schemaId) throws IOException {
        Schema avroSchema = getSchemaCached(schemaHash==null? schema : schemaHash, schema);
        
        // The Avro JsonDecoder is strict and requires every field defined by the
        // schema to be present in the JSON input. Omitting an optional field would
        // otherwise cause the decoder to raise an error. To honor the schema's
        // default values, the JSON is normalized first by inserting the declared
        // default for any field that is missing from the input.
        JsonNode normalized = fillDefaults(avroSchema, MAPPER.readTree(content));
        // Values of Avro's "decimal" logical type are backed by bytes/fixed and are
        // therefore rejected by the JSON decoder when supplied as plain numbers.
        // Rewrite any decimal field expressed as a floating point number into the
        // Avro JSON byte encoding the decoder expects.
        JsonNode withDecimals = convertDecimals(avroSchema, normalized, true);
        String normalizedContent = MAPPER.writeValueAsString(withDecimals);

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
            if(schemaId!=0) {
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
     * Decodes binary Avro data into a JSON-encoded String according to the given schema.
     *
     * @param schemaHash  a hash value of the Avro schema string used to identify the schema
     * @param schema  a String containing the Avro schema (JSON schema definition)
     * @param content the binary Avro data to decode
     * @return a String containing the JSON representation of the decoded data
     * @throws IOException if the schema is invalid or the content cannot be decoded
     */
    public static String decodeAvro(String schemaHash, String schema, byte[] content) throws IOException {
        Schema avroSchema = getSchemaCached(schemaHash==null? schema : schemaHash, schema);

        DatumReader<Object> reader = new GenericDatumReader<>(avroSchema);
        Decoder binaryDecoder = DecoderFactory.get().binaryDecoder(content, null);
        Object datum = reader.read(null, binaryDecoder);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder jsonEncoder = EncoderFactory.get().jsonEncoder(avroSchema, out);
        DatumWriter<Object> writer = new GenericDatumWriter<>(avroSchema);
        writer.write(datum, jsonEncoder);
        jsonEncoder.flush();

        // The Avro JSON encoder emits "decimal" logical values as byte strings.
        // Rewrite them into plain floating point numbers for the JSON output.
        JsonNode tree = MAPPER.readTree(out.toString(StandardCharsets.UTF_8.name()));
        JsonNode converted = convertDecimals(avroSchema, tree, false);
        return MAPPER.writeValueAsString(converted);
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
     * Recursively walks a JSON node against its Avro schema and converts every
     * value of the {@code decimal} logical type between two representations.
     *
     * <p>When {@code toAvro} is {@code true} the JSON floating point numbers used
     * in the external representation are rewritten into the Avro JSON byte-string
     * encoding expected by the Avro JSON decoder. When {@code toAvro} is
     * {@code false} the Avro byte-string encoding produced by the Avro JSON
     * encoder is rewritten back into a plain floating point number.</p>
     *
     * @param schema the Avro schema describing {@code node}
     * @param node   the JSON node to convert (may be {@code null})
     * @param toAvro {@code true} to encode floats into Avro bytes, {@code false}
     *               to decode Avro bytes into floats
     * @return a JSON node with all decimal values converted in the requested
     *         direction
     */
    private static JsonNode convertDecimals(Schema schema, JsonNode node, boolean toAvro) {
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
                                    convertDecimals(field.schema(), node.get(field.name()), toAvro));
                        }
                    }
                    return result;
                }
                return node;
            case UNION:
                return convertUnionDecimals(schema, node, toAvro);
            case ARRAY:
                if (node.isArray()) {
                    ArrayNode result = MAPPER.createArrayNode();
                    for (JsonNode element : node) {
                        result.add(convertDecimals(schema.getElementType(), element, toAvro));
                    }
                    return result;
                }
                return node;
            case MAP:
                if (node.isObject()) {
                    ObjectNode result = MAPPER.createObjectNode();
                    for (Map.Entry<String, JsonNode> entry : iterable(node)) {
                        result.set(entry.getKey(),
                                convertDecimals(schema.getValueType(), entry.getValue(), toAvro));
                    }
                    return result;
                }
                return node;
            default:
                return node;
        }
    }

    /**
     * Converts the decimal values contained in a union-typed JSON node, recursing
     * into the wrapped branch that carries the value.
     */
    private static JsonNode convertUnionDecimals(Schema union, JsonNode node, boolean toAvro) {
        if (node.isNull()) {
            return node;
        }
        if (node.isObject() && node.size() == 1) {
            String branchName = node.fieldNames().next();
            Schema branch = findUnionBranch(union, branchName);
            if (branch != null) {
                ObjectNode wrapped = MAPPER.createObjectNode();
                wrapped.set(branchName, convertDecimals(branch, node.get(branchName), toAvro));
                return wrapped;
            }
        }
        return node;
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
