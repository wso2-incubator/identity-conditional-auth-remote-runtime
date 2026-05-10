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

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.*;

import java.util.HashMap;
import java.util.Map;

/**
 * JavaScript engine service implementation for the External.
 * Orchestrates evaluate and callback execution requests: context creation,
 * host function registration, binding restore, JS evaluation, and response building.
 * <p>
 * Value serialization (GraalVM Value ↔ protobuf) is delegated to {@link EngineValueSerializer}.
 * Host function calls are forwarded back to IS via {@link HostCallbackClient}.
 * <p>
 * The handlers exchange protobuf message objects (not byte arrays) with the gRPC
 * transport — the bidi {@code StreamMessage} envelope is already a typed proto on
 * both sides of this call, so re-encoding the inner request to bytes only to
 * re-parse it here would be pure waste. gRPC is the only transport, fixed by
 * design, so aligning to its schema directly is safe.
 */
public class JsEngineServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(JsEngineServiceImpl.class);
    private static final String JS_LANG = "js";

    private final int defaultStatementLimit;
    private final EngineValueSerializer valueSerializer = new EngineValueSerializer();

    public JsEngineServiceImpl(int defaultStatementLimit) {
        this.defaultStatementLimit = defaultStatementLimit;
    }

    /**
     * Handle an evaluate request with a pre-created callback client.
     * Used by streaming transport where the callback client uses the bidi stream.
     *
     * @param request        Already-parsed {@link EvaluateRequest} from the bidi stream.
     * @param callbackClient Pre-created callback client for host function
     *                       callbacks.
     * @return The {@link EvaluateResponse} to ship back on the same stream.
     */
    public EvaluateResponse handleEvaluate(EvaluateRequest request, HostCallbackClient callbackClient) {
        if (log.isDebugEnabled()) {
            log.debug("[External] handleEvaluate (streaming) called");
        }
        long startTime = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[External] handleEvaluate (streaming) - session: {}, sourceId: {}",
                    request.getSessionId(), request.getSourceIdentifier());
        }
        if (log.isDebugEnabled()) {
            log.debug("[External] Script length: {}, bindings: {}, hostFunctions: {}",
                    request.getScript().length(), request.getBindingsCount(), request.getHostFunctionsCount());
        }

        try {
            // Reset callback timer for reused streaming clients
            if (callbackClient != null) {
                callbackClient.resetCallbackTimeMs();
            }

            try (Context context = createContext()) {
                Value bindings = context.getBindings(JS_LANG);

                // Phase B: Context setup (create context + register stubs)
                // Use dynamic list from the request, mirroring the executeCallback
                // registration logic. This ensures host functions like
                // getMaskedValue, httpGet, etc. are available during initial
                // script evaluation rather than only after the first callback.
                registerHostFunctionStubsFromRequest(bindings, callbackClient,
                        request.getHostFunctionsList());
                long tContextSetup = System.currentTimeMillis();

                // Phase C: Binding restore
                valueSerializer.setCallbackClient(callbackClient);
                try {
                    for (Map.Entry<String, SerializedValue> entry : request.getBindingsMap().entrySet()) {
                        bindings.putMember(entry.getKey(), valueSerializer.deserializeValue(entry.getValue(), context));
                    }
                } finally {
                    valueSerializer.clearCallbackClient();
                }
                long tBindingsRestored = System.currentTimeMillis();

                // Phase D: Proxy create
                if (callbackClient != null) {
                    Value contextProxy = createContextProxy(context, callbackClient);
                    bindings.putMember("context", contextProxy);
                    if (log.isDebugEnabled()) {
                        log.debug("[External] Bound DYNAMIC context proxy for session: {}", request.getSessionId());
                    }
                }
                long tProxyCreated = System.currentTimeMillis();

                // Phase E: JS evaluate
                if (log.isDebugEnabled()) {
                    log.debug("[External] Starting script evaluation (streaming)...");
                }
                Value result = context.eval(JS_LANG, request.getScript());
                long tJsEvaluated = System.currentTimeMillis();
                if (log.isDebugEnabled()) {
                    log.debug("[External] Script evaluation completed successfully (streaming)");
                }

                // Phase F: Binding extract
                Map<String, SerializedValue> updatedBindings = new HashMap<>();
                for (String key : bindings.getMemberKeys()) {
                    Value val = bindings.getMember(key);
                    // Skip "context" -- it is an unserializable JsGraalAuthenticationContext proxy.
                    // Context state is read live via DynamicContextProxy callbacks, so there is
                    // nothing meaningful to ship back. Serializing the proxy here would fall
                    // back to a Serializer toString() with WARN log.
                    // If context binding is ever needed here, implement a proper toProto()
                    // conversion for JsGraalAuthenticationContext first.
                    if (!ExternalConstants.CONTEXT_BINDING_KEY.equals(key) && !isHostFunction(key)) {
                        updatedBindings.put(key, valueSerializer.serializeValue(val));
                    }
                }
                long tBindingsExtracted = System.currentTimeMillis();

                // Phase G: Response build
                long callbackMs = callbackClient != null ? callbackClient.getCallbackTimeMs() : 0;
                long elapsed = System.currentTimeMillis() - startTime;
                long pureProcessingMs = elapsed - callbackMs;
                EvaluateResponse response = EvaluateResponse.newBuilder()
                        .setSuccess(true)
                        .setElapsedMs(elapsed)
                        .setResult(valueSerializer.serializeValue(result))
                        .putAllUpdatedBindings(updatedBindings)
                        .build();
                long tResponseBuilt = System.currentTimeMillis();
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[External] Time breakdown (streaming): totalElapsed={}ms, pureProcessing={}ms, callbackRoundTrips={}ms",
                            elapsed, pureProcessingMs, callbackMs);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[External] Phase timing (streaming): contextSetup={}ms, " +
                            "bindingRestore={}ms, proxyCreate={}ms, jsEvaluate={}ms, " +
                            "bindingExtract={}ms, responseBuild={}ms, total={}ms",
                            tContextSetup - startTime,
                            tBindingsRestored - tContextSetup,
                            tProxyCreated - tBindingsRestored,
                            tJsEvaluated - tProxyCreated,
                            tBindingsExtracted - tJsEvaluated,
                            tResponseBuilt - tBindingsExtracted,
                            elapsed);
                }
                return response;
            }

        } catch (PolyglotException e) {
            log.error("PolyglotException during evaluation (streaming)", e);
            return EvaluateResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.getMessage() != null ? e.getMessage() : "Unknown PolyglotException")
                    .setErrorType("PolyglotException")
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Throwable t) {
            log.error("FATAL: Throwable during evaluation (streaming)", t);
            String errorMessage = t.getClass().getName() + ": " +
                    (t.getMessage() != null ? t.getMessage() : "No error message");
            return EvaluateResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(errorMessage)
                    .setErrorType(t.getClass().getName())
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build();
        } finally {
            currentRegisteredFunctions.remove();
            // NOTE: Do NOT close callbackClient here - it's managed by the streaming
            // transport
        }
    }

    /**
     * Handle an execute callback request with a pre-created callback client.
     * Used by streaming transport where the callback client uses the bidi stream.
     *
     * @param request        Already-parsed {@link ExecuteCallbackRequest} from the bidi stream.
     * @param callbackClient Pre-created callback client for host function
     *                       callbacks.
     * @return The {@link ExecuteCallbackResponse} to ship back on the same stream.
     */
    public ExecuteCallbackResponse handleExecuteCallback(ExecuteCallbackRequest request,
                                                         HostCallbackClient callbackClient) {
        long startTime = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[External] handleExecuteCallback (streaming) - session: {}", request.getSessionId());
        }
        if (log.isDebugEnabled()) {
            log.debug("[External] Function source length: {}, args: {}, bindings: {}",
                    request.getFunctionSource().length(), request.getArgumentsCount(), request.getBindingsCount());
        }

        try {
            // Reset callback timer for reused streaming clients
            if (callbackClient != null) {
                callbackClient.resetCallbackTimeMs();
            }

            try (Context context = createContext()) {
                Value bindings = context.getBindings(JS_LANG);

                // Phase B: Context setup (create context + register stubs)
                registerHostFunctionStubsFromRequest(bindings, callbackClient, request.getHostFunctionsList());
                long tContextSetup = System.currentTimeMillis();

                // Declare variables outside try block so they're accessible later
                Value contextProxy = null;
                Object[] args = new Object[request.getArgumentsCount()];

                // Phase C: Binding restore
                valueSerializer.setCallbackClient(callbackClient);
                try {
                    for (Map.Entry<String, SerializedValue> entry : request.getBindingsMap().entrySet()) {
                        Object deserialized = valueSerializer.deserializeValue(entry.getValue(), context);
                        bindings.putMember(entry.getKey(), deserialized);
                    }

                    // Phase D: Proxy create + argument deserialization
                    if (callbackClient != null) {
                        contextProxy = createContextProxy(context, callbackClient);
                        bindings.putMember(ExternalConstants.CALLBACK_CONTEXT_KEY, contextProxy);
                    }

                    // Deserialize arguments
                    for (int i = 0; i < args.length; i++) {
                        SerializedValue sv = request.getArguments(i);
                        if (sv.getValueCase() == SerializedValue.ValueCase.STRING_VALUE &&
                                sv.getStringValue().contains(ExternalConstants.CONTEXT_PLACEHOLDER) &&
                                contextProxy != null) {
                            args[i] = contextProxy;
                        } else {
                            args[i] = valueSerializer.deserializeValue(sv, context);
                        }
                    }
                } finally {
                    valueSerializer.clearCallbackClient();
                }
                long tBindingsRestored = System.currentTimeMillis();
                long tProxyAndArgsReady = System.currentTimeMillis();

                // Phase E: JS evaluate (function execution)
                Value function = context.eval(JS_LANG, "(" + request.getFunctionSource() + ")");

                Value result;
                if (args.length > 0) {
                    result = function.execute(args);
                } else if (contextProxy != null) {
                    result = function.execute(contextProxy);
                } else {
                    result = function.execute();
                }
                long tJsEvaluated = System.currentTimeMillis();

                // Phase F: Binding extract
                Map<String, SerializedValue> updatedBindings = new HashMap<>();
                for (String key : bindings.getMemberKeys()) {
                    Value val = bindings.getMember(key);
                    // Skip "context" -- it is an unserializable JsGraalAuthenticationContext proxy.
                    // Context state is read live via DynamicContextProxy callbacks, so there is
                    // nothing meaningful to ship back. Serializing the proxy here would fall
                    // back to a Serializer toString() with WARN log.
                    // If context binding is ever needed here, implement a proper toProto()
                    // conversion for JsGraalAuthenticationContext first.
                    if (!ExternalConstants.CONTEXT_BINDING_KEY.equals(key) && !key.startsWith("__") && !isHostFunction(key)) {
                        updatedBindings.put(key, valueSerializer.serializeValue(val));
                    }
                }
                long tBindingsExtracted = System.currentTimeMillis();

                // Phase G: Response build
                long callbackMs = callbackClient != null ? callbackClient.getCallbackTimeMs() : 0;
                long elapsed = System.currentTimeMillis() - startTime;
                long pureProcessingMs = elapsed - callbackMs;
                ExecuteCallbackResponse response = ExecuteCallbackResponse.newBuilder()
                        .setSuccess(true)
                        .setElapsedMs(elapsed)
                        .setResult(valueSerializer.serializeValue(result))
                        .putAllUpdatedBindings(updatedBindings)
                        .build();
                long tResponseBuilt = System.currentTimeMillis();
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[External] Time breakdown (streaming): totalElapsed={}ms, pureProcessing={}ms, callbackRoundTrips={}ms",
                            elapsed, pureProcessingMs, callbackMs);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[External] Phase timing (streaming): contextSetup={}ms, " +
                            "bindingRestore={}ms, proxyAndArgs={}ms, jsEvaluate={}ms, " +
                            "bindingExtract={}ms, responseBuild={}ms, total={}ms",
                            tContextSetup - startTime,
                            tBindingsRestored - tContextSetup,
                            tProxyAndArgsReady - tBindingsRestored,
                            tJsEvaluated - tProxyAndArgsReady,
                            tBindingsExtracted - tJsEvaluated,
                            tResponseBuilt - tBindingsExtracted,
                            elapsed);
                }
                return response;
            }

        } catch (PolyglotException e) {
            log.error("PolyglotException during callback execution (streaming)", e);
            return ExecuteCallbackResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.getMessage())
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Throwable t) {
            log.error("FATAL: Throwable during callback execution (streaming)", t);
            String errorMessage = t.getClass().getName() + ": " +
                    (t.getMessage() != null ? t.getMessage() : "No error message");
            return ExecuteCallbackResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(errorMessage)
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build();
        } finally {
            currentRegisteredFunctions.remove();
            // NOTE: Do NOT close callbackClient here - it's managed by the streaming
            // transport
        }
    }

    private Context createContext() {
        ResourceLimits.Builder limitsBuilder = ResourceLimits.newBuilder();
        if (defaultStatementLimit > 0) {
            limitsBuilder.statementLimit(defaultStatementLimit, null);
        }

        return Context.newBuilder(JS_LANG)
                .allowHostAccess(HostAccess.ALL)
                .resourceLimits(limitsBuilder.build())
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /**
     * Walks the host-function list in the request and binds one HostFunctionStub
     * per name into the script bindings. IS controls what's registered — there
     * are no defaults synthesised here, and an empty list is treated as a
     * regression on IS rather than something to silently paper over.
     */
    private void registerHostFunctionStubsFromRequest(Value bindings, HostCallbackClient callbackClient,
            java.util.List<HostFunctionDefinition> hostFunctions) {
        // Track registered function names for isHostFunction check
        java.util.Set<String> registeredFunctions = new java.util.HashSet<>();

        // Every entry from the request becomes a HostFunctionStub. The stub
        // implements both ProxyExecutable (for direct calls like
        // selectAcrFrom(...)) and ProxyObject (for namespaced calls like
        // Log.info(...)) — member access on a stub yields a dotted sub-stub
        // that reaches IS as a single host-function call (e.g. "Log.info"),
        // where IS's HostFunctionRegistry routes to the matching
        // @HostAccess.Export method on the registered instance. No special
        // case for Log on this side; the runtime carries no logger of its own.
        if (hostFunctions == null || hostFunctions.isEmpty()) {
            throw new IllegalStateException(
                    "Empty hostFunctions list received from IS. The IS-side RemoteJsEngine must " +
                            "populate HostFunctionDefinition entries on every evaluate/callback request; " +
                            "an empty list indicates a producer-side regression or a version mismatch.");
        }
        for (HostFunctionDefinition funcDef : hostFunctions) {
            String funcName = funcDef.getName();
            if (log.isDebugEnabled()) {
                log.debug("[External] Registering host function stub: {}", funcName);
            }
            bindings.putMember(funcName, new HostFunctionStub(funcName, callbackClient));
            registeredFunctions.add(funcName);
        }

        // Store for isHostFunction checks
        currentRegisteredFunctions.set(registeredFunctions);
        if (log.isDebugEnabled()) {
            log.debug("[External] Registered {} host function stubs", registeredFunctions.size());
        }
    }

    // Thread-local to track registered functions for current request
    private static final ThreadLocal<java.util.Set<String>> currentRegisteredFunctions = ThreadLocal
            .withInitial(java.util.HashSet::new);

    private boolean isHostFunction(String name) {
        java.util.Set<String> registered = currentRegisteredFunctions.get();
        return registered != null && registered.contains(name);
    }

    /**
     * Create a JavaScript proxy object representing the authentication context.
     * Every property access on the proxy triggers a callback to IS, so context
     * state is always read live. The proxy is attached when a callback channel
     * exists ({@code callbackClient != null}); otherwise the binding is left
     * unset and the script will fail loudly if it touches {@code context}.
     *
     * @param context        The GraalJS context.
     * @param callbackClient The callback client for host function calls.
     * @return A JavaScript Value representing the context proxy.
     */
    private Value createContextProxy(Context context, HostCallbackClient callbackClient) {
        String sessionId = callbackClient != null ? callbackClient.getSessionId() : "unknown";
        if (log.isDebugEnabled()) {
            log.debug("[External] Creating DYNAMIC context proxy for session: {}", sessionId);
        }

        // Use DynamicContextProxy which calls back to IS for every property access
        // This ensures the External context behaves identically to the local
        // JsGraalAuthenticationContext
        DynamicContextProxy dynamicProxy = new DynamicContextProxy(
                sessionId,
                callbackClient,
                "context", // Root proxy type
                "" // Empty base path (root level)
        );

        return context.asValue(dynamicProxy);
    }
}
