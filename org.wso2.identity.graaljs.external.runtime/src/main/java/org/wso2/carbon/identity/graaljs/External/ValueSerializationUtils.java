/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.graaljs.External;

import com.google.protobuf.ByteString;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.SerializedArray;
import org.wso2.carbon.identity.graaljs.proto.SerializedFunction;
import org.wso2.carbon.identity.graaljs.proto.SerializedMap;
import org.wso2.carbon.identity.graaljs.proto.SerializedProxyObject;
import org.wso2.carbon.identity.graaljs.proto.SerializedValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized serialization/deserialization for protobuf, Java, and GraalVM Value.
 * Eliminates scattered duplication across DynamicContextProxy,
 * HostFunctionStub, and HostCallbackClient.
 */
public final class ValueSerializationUtils {

    private static final Logger log = LoggerFactory.getLogger(ValueSerializationUtils.class);

    private ValueSerializationUtils() {
        // Utility class — no instances.
    }

    // ─────────── Protobuf → Java ───────────

    /**
     * Wire → Java. The path used when something Java-side needs to read a
     * SerializedValue back, mainly host-function-return decoding inside
     * HostFunctionStub.execute and property reads from
     * DynamicContextProxy.getMember.
     *
     * Proxy markers come out as tagged maps (IS_HOST_REF=true). Building a
     * real DynamicContextProxy from one of those is the caller's job, not
     * this method's — keeps the deserializer free of session/callback-client
     * coupling.
     */
    public static Object deserialize(SerializedValue sv) {
        if (sv == null) {
            return null;
        }
        switch (sv.getValueCase()) {
            case STRING_VALUE:
                return sv.getStringValue();
            case INT_VALUE:
                return sv.getIntValue();
            case DOUBLE_VALUE:
                return sv.getDoubleValue();
            case BOOL_VALUE:
                return sv.getBoolValue();
            case NULL_VALUE:
                return null;
            case ARRAY_VALUE:
                List<Object> list = new ArrayList<>();
                for (SerializedValue element : sv.getArrayValue().getElementsList()) {
                    list.add(deserialize(element));
                }
                return list;
            case MAP_VALUE:
                Map<String, Object> map = new HashMap<>();
                for (Map.Entry<String, SerializedValue> entry :
                        sv.getMapValue().getEntriesMap().entrySet()) {
                    map.put(entry.getKey(), deserialize(entry.getValue()));
                }
                return map;
            case PROXY_OBJECT:
                return deserializeProxyMarker(sv.getProxyObject());
            default:
                throw new IllegalStateException(
                        "Unrecognized SerializedValue case: " + sv.getValueCase() +
                                ". Wire schema may have evolved beyond this consumer's version.");
        }
    }

    /**
     * Deserialize a protobuf proxy object into a marker map.
     */
    private static Map<String, Object> deserializeProxyMarker(SerializedProxyObject proxy) {
        Map<String, Object> marker = new HashMap<>();
        marker.put(ExternalConstants.IS_HOST_REF, true);
        marker.put(ExternalConstants.PROXY_TYPE_FIELD, proxy.getType());
        marker.put(ExternalConstants.REFERENCE_ID_FIELD, proxy.getReferenceId());
        if (log.isDebugEnabled()) {
            log.debug("Deserialized proxy object: type={}, refId={}",
                    proxy.getType(), proxy.getReferenceId());
        }
        return marker;
    }

    // ─────────── Java → Protobuf ──────────

    /**
     * Java → wire. For callers that already have a Java-typed value —
     * primitives, Java containers, or a Polyglot Value that's been unwrapped
     * to Object. Containers are walked element by element, and a Value falls
     * through to serializeGraalValue so we don't duplicate the Polyglot
     * dispatch ladder. Anything else is a programmer error and throws.
     */
    public static SerializedValue serialize(Object val) {
        if (val == null) {
            return nullValue();
        }
        if (val instanceof Value) {
            return serializeGraalValue((Value) val);
        }
        if (val instanceof String) {
            return SerializedValue.newBuilder()
                    .setStringValue((String) val).build();
        }
        if (val instanceof Integer) {
            return SerializedValue.newBuilder()
                    .setIntValue(((Integer) val).longValue()).build();
        }
        if (val instanceof Long) {
            return SerializedValue.newBuilder()
                    .setIntValue((Long) val).build();
        }
        if (val instanceof Double) {
            return SerializedValue.newBuilder()
                    .setDoubleValue((Double) val).build();
        }
        if (val instanceof Float) {
            return SerializedValue.newBuilder()
                    .setDoubleValue(((Float) val).doubleValue()).build();
        }
        if (val instanceof Boolean) {
            return SerializedValue.newBuilder()
                    .setBoolValue((Boolean) val).build();
        }
        if (val instanceof Object[]) {
            return serializeArray((Object[]) val);
        }
        if (val instanceof List) {
            return serializeList((List<?>) val);
        }
        if (val instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapVal = (Map<String, Object>) val;
            return serializeMap(mapVal);
        }
        throw new IllegalArgumentException(
                "Unsupported serialization type: " + val.getClass().getName());
    }

    // ─────────── GraalVM Value → Protobuf ───────

    /**
     * Polyglot → wire. The hot path is DynamicContextProxy.putMember when a
     * script does context.foo = something — we get a Polyglot Value and need
     * to encode it for the proto.
     *
     * Trait order matters here: isNull first, then primitives, then canExecute
     * (so a function routes through source extraction before hasMembers picks
     * it up by mistake), then arrays, then the DynamicContextProxy escape
     * hatch (otherwise hasMembers would walk it and trigger cascading
     * callbacks for every property), then plain objects. Anything that falls
     * past hasMembers throws with a full trait fingerprint — no toString
     * fallback, no silent string corruption sneaking onto the wire.
     */
    public static SerializedValue serializeGraalValue(Value val) {
        if (val == null || val.isNull()) {
            return nullValue();
        }
        if (val.isString()) {
            return SerializedValue.newBuilder()
                    .setStringValue(val.asString()).build();
        }
        if (val.isNumber()) {
            if (val.fitsInLong()) {
                return SerializedValue.newBuilder()
                        .setIntValue(val.asLong()).build();
            }
            return SerializedValue.newBuilder()
                    .setDoubleValue(val.asDouble()).build();
        }
        if (val.isBoolean()) {
            return SerializedValue.newBuilder()
                    .setBoolValue(val.asBoolean()).build();
        }
        // Check canExecute() BEFORE hasMembers() because JS functions also have members.
        if (val.canExecute()) {
            return serializeFunction(val);
        }
        if (val.hasArrayElements()) {
            SerializedArray.Builder ab = SerializedArray.newBuilder();
            for (long i = 0; i < val.getArraySize(); i++) {
                ab.addElements(serializeGraalValue(val.getArrayElement(i)));
            }
            return SerializedValue.newBuilder()
                    .setArrayValue(ab.build()).build();
        }
        // DynamicContextProxy guard: if the Value wraps a DynamicContextProxy,
        // emit a marker map instead of iterating members (which would trigger
        // cascading gRPC callbacks back to IS for every property).
        // Same pattern as EngineValueSerializer lines 128-141.
        if (val.isProxyObject()) {
            Object proxyObj = val.asProxyObject();
            if (proxyObj instanceof DynamicContextProxy) {
                DynamicContextProxy proxy = (DynamicContextProxy) proxyObj;
                SerializedMap.Builder marker = SerializedMap.newBuilder();
                marker.putEntries(ExternalConstants.IS_CONTEXT_PROXY,
                        SerializedValue.newBuilder().setBoolValue(true).build());
                marker.putEntries(ExternalConstants.PROXY_TYPE_FIELD,
                        SerializedValue.newBuilder().setStringValue(proxy.getProxyType()).build());
                marker.putEntries(ExternalConstants.BASE_PATH_FIELD,
                        SerializedValue.newBuilder().setStringValue(proxy.getBasePath()).build());
                return SerializedValue.newBuilder().setMapValue(marker).build();
            }
        }
        if (val.hasMembers()) {
            SerializedMap.Builder mb = SerializedMap.newBuilder();
            for (String key : val.getMemberKeys()) {
                mb.putEntries(key, serializeGraalValue(val.getMember(key)));
            }
            return SerializedValue.newBuilder()
                    .setMapValue(mb.build()).build();
        }
        throw new IllegalStateException(
                "Cannot serialize Polyglot Value to wire: unmatched shape. " +
                        "isNull=" + val.isNull() +
                        ", isString=" + val.isString() +
                        ", isNumber=" + val.isNumber() +
                        ", isBoolean=" + val.isBoolean() +
                        ", canExecute=" + val.canExecute() +
                        ", hasArrayElements=" + val.hasArrayElements() +
                        ", isProxyObject=" + val.isProxyObject() +
                        ", hasMembers=" + val.hasMembers() +
                        ", toString=" + val);
    }

    // ─────────── GraalVM Value → Java (for caching/in-process use) ────────

    /**
     * Convert a GraalVM Value to a Java primitive for caching.
     * Lightweight — only handles primitives. Complex objects return toString().
     *
     * @param value The GraalVM Value to convert.
     * @return The Java primitive representation.
     */
    public static Object toJavaPrimitive(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.fitsInLong() ? value.asLong() : value.asDouble();
        }
        return value.toString();
    }

    // ─────────── Function Source Extraction ──────────────────────────────

    /**
     * Recovers the source text of a GraalJS-parsed function so it can be shipped
     * over the wire and recompiled on IS. The first stop is getSourceLocation();
     * for inline-defined functions that's reliable. If that's null we fall to
     * toString() — but only accept it if it actually looks function-shaped
     * (contains "function" or "=>").
     *
     * Anything else is almost certainly someone capturing a host-function stub
     * (e.g. context.x = Log) — those have no script-side source. We used to
     * silently emit "function(){}" and ship a no-op across the wire; now we
     * throw so the broken capture surfaces at write time instead of as a
     * mysteriously inert callback later.
     */
    public static String extractFunctionSource(Value val) {
        String source = null;
        try {
            if (val.getSourceLocation() != null &&
                    val.getSourceLocation().getCharacters() != null) {
                source = val.getSourceLocation().getCharacters().toString();
                if (log.isDebugEnabled()) {
                    log.debug("Extracted function source via getSourceLocation: {}...",
                            source.substring(0, Math.min(80, source.length())));
                }
            }
        } catch (Exception e) {
            log.debug("Could not get source location: {}", e.getMessage());
        }
        if (source == null || source.isEmpty()) {
            try {
                source = val.toString();
                if (log.isDebugEnabled()) {
                    log.debug("Using toString() for function: {}...",
                            source.substring(0, Math.min(80, source.length())));
                }
            } catch (Exception e) {
                log.warn("Could not get function toString(): {}", e.getMessage());
            }
        }
        if (source != null && !source.isEmpty() &&
                (source.contains("function") || source.contains("=>"))) {
            return source;
        }
        throw new IllegalStateException(
                "Cannot extract function source for serialization. Likely a host-function stub " +
                        "or other non-source-bearing executable was captured into a binding and is " +
                        "being serialized — these have no script-side source to ship to IS. " +
                        "isProxyObject=" + val.isProxyObject() +
                        ", hasSourceLocation=" + (val.getSourceLocation() != null) +
                        ", toString=" + (source != null ? source : "<null>"));
    }

    // ─────────── Private helpers ──────────────────────────────────────────

    private static SerializedValue nullValue() {
        return SerializedValue.newBuilder()
                .setNullValue(ByteString.EMPTY).build();
    }

    /**
     * Serialize a GraalVM function value by extracting its source.
     * Uses FUNCTION_VALUE protobuf oneof case (matching EngineValueSerializer)
     * so IS-side Serializer.fromProto() correctly reconstructs a GraalSerializableJsFunction.
     */
    private static SerializedValue serializeFunction(Value val) {
        String source = extractFunctionSource(val);
        return SerializedValue.newBuilder()
                .setFunctionValue(SerializedFunction.newBuilder().setSource(source))
                .build();
    }

    private static SerializedValue serializeArray(Object[] arr) {
        SerializedArray.Builder arrayBuilder = SerializedArray.newBuilder();
        for (Object element : arr) {
            arrayBuilder.addElements(serialize(element));
        }
        return SerializedValue.newBuilder().setArrayValue(arrayBuilder.build()).build();
    }

    private static SerializedValue serializeList(List<?> list) {
        SerializedArray.Builder arrayBuilder = SerializedArray.newBuilder();
        for (Object element : list) {
            arrayBuilder.addElements(serialize(element));
        }
        return SerializedValue.newBuilder().setArrayValue(arrayBuilder.build()).build();
    }

    private static SerializedValue serializeMap(Map<String, Object> map) {
        SerializedMap.Builder mapBuilder = SerializedMap.newBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            mapBuilder.putEntries(entry.getKey(), serialize(entry.getValue()));
        }
        return SerializedValue.newBuilder().setMapValue(mapBuilder.build()).build();
    }
}
