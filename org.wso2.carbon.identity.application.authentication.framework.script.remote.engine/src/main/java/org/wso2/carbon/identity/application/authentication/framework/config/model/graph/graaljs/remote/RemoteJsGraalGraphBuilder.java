/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com).
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

package org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.graalvm.polyglot.HostAccess;
import org.wso2.carbon.identity.application.authentication.framework.AsyncProcess;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticationDecisionEvaluator;
import org.wso2.carbon.identity.application.authentication.framework.JsFunctionRegistry;
import org.wso2.carbon.identity.application.authentication.framework.config.model.StepConfig;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.AuthGraphNode;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.AuthenticationGraph;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.BaseSerializableJsFunction;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.DynamicDecisionNode;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.FailNode;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.GenericSerializableJsFunction;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.JSExecutionMonitorData;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.JSExecutionSupervisor;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.JsGraphBuilder;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.LongWaitNode;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.ShowPromptNode;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.StepConfigGraphNode;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.GraalSerializableJsFunction;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.GraalSerializer;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote.JsGraalGraphEngineModeRouter;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote.server.GrpcTransportProvider;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.js.JsLogger;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.handler.sequence.impl.GraalSelectAcrFromFunction;
import org.wso2.carbon.identity.application.authentication.framework.internal.FrameworkServiceDataHolder;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.JsGraalGraphBuilder;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.AdaptiveAuthentication.PROP_EXECUTION_SUPERVISOR_RESULT;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.AUTHENTICATION_OPTIONS;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.AUTHENTICATOR_PARAMS;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_AUTH_FAILURE;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_FUNC_EXECUTE_STEP;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_FUNC_GET_SECRET_BY_NAME;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_FUNC_LOAD_FUNC_LIB;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_FUNC_ON_LOGIN_REQUEST;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_FUNC_SELECT_ACR_FROM;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_FUNC_SEND_ERROR;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_FUNC_SHOW_PROMPT;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.JS_LOG;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.PROP_CURRENT_NODE;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.JSAttributes.STEP_OPTIONS;

/**
 * Remote execution graph builder for adaptive authentication.
 * This builder handles script evaluation and callback execution via the external GraalJS sidecar
 * process over gRPC, while providing its own graph-building logic (executeStep, sendError, showPrompt,
 * addEventListeners, infuse, etc.) independently from the local-mode JsGraalGraphBuilder.
 * <p>
 * Both this class and JsGraalGraphBuilder are siblings extending JsGraphBuilder directly.
 * Common infrastructure (ThreadLocals, attachToLeaf, infuse, build, etc.) comes from JsGraphBuilder.
 * <p>
 * Instances are created by JsGraalGraphBuilderFactory when execution mode is REMOTE.
 * Each login session gets its own builder instance (not thread safe, discarded after each build).
 */
public class RemoteJsGraalGraphBuilder extends JsGraalGraphBuilder {

    private static final Log log = LogFactory.getLog(RemoteJsGraalGraphBuilder.class);

    /**
     * Constructs the remote builder for initial script evaluation (createWith path).
     *
     * @param authenticationContext current authentication context.
     * @param stepConfigMap         The Step map from the service provider configuration.
     */
    public RemoteJsGraalGraphBuilder(AuthenticationContext authenticationContext,
                                     Map<Integer, StepConfig> stepConfigMap) {

        super(authenticationContext, stepConfigMap,null);
        this.authenticationContext = authenticationContext;
        this.stepNamedMap = stepConfigMap.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Constructs the remote builder for callback evaluation (getScriptEvaluator path).
     *
     * @param authenticationContext current authentication context.
     * @param stepConfigMap         The Step map from the service provider configuration.
     * @param currentNode           Current authentication graph node.
     */
    public RemoteJsGraalGraphBuilder(AuthenticationContext authenticationContext,
                                     Map<Integer, StepConfig> stepConfigMap,
                                     AuthGraphNode currentNode) {

        super(authenticationContext, stepConfigMap, null, currentNode);
        this.authenticationContext = authenticationContext;
        this.currentNode = currentNode;
        this.stepNamedMap = stepConfigMap.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Creates the graph with the given Script using remote External execution.
     * This method sends the script to the External sidecar for evaluation and processes
     * callback results including host function invocations (executeStep, sendError, etc.).
     *
     * @param script the Dynamic authentication script.
     * @return This builder.
     */
    @Override
    @SuppressWarnings("unchecked")
    public RemoteJsGraalGraphBuilder createWith(String script) {

        JsEngine jsEngine = new RemoteJsEngine(
                GrpcTransportProvider.getTransport(JsGraalGraphEngineModeRouter.getGrpcTarget()),
                authenticationContext);
        try {
            currentBuilder.set(this);

            if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                log.debug("[createWithRemote] Starting for SP: " + authenticationContext.getServiceProviderName() +
                        ", contextId: " + authenticationContext.getContextIdentifier());
            }

            // Register host functions that the External can call back.
            Map<String, Object> hostFunctions = new HashMap<>();
            hostFunctions.put(JS_FUNC_EXECUTE_STEP, new JsGraalStepExecuter());
            hostFunctions.put(JS_FUNC_SEND_ERROR, new SendErrorFunctionImpl());
            hostFunctions.put(JS_FUNC_SHOW_PROMPT, new JsGraalPromptExecutorImpl());
            hostFunctions.put(JS_FUNC_LOAD_FUNC_LIB, new JsGraalLoadExecutorImpl());
            hostFunctions.put(JS_FUNC_GET_SECRET_BY_NAME, new JsGraalGetSecretImpl());
            // Mirror the local-execution factory bindings so adaptive scripts that
            // use Log.info(...) / selectAcrFrom(...) work over the remote engine
            // too. JsLogger has multiple @HostAccess.Export methods — the registry
            // expands those into "Log.info" / "Log.debug" / ... entries; the
            // External's HostFunctionStub routes member access (Log.info) back as
            // the matching dotted host-function call.
            hostFunctions.put(JS_LOG, new JsLogger());
            hostFunctions.put(JS_FUNC_SELECT_ACR_FROM, new GraalSelectAcrFromFunction());

            JsFunctionRegistry jsFunctionRegistrar = FrameworkServiceDataHolder.getInstance().getJsFunctionRegistry();
            if (jsFunctionRegistrar != null) {
                hostFunctions.putAll(jsFunctionRegistrar.getSubsystemFunctionsMap(
                        JsFunctionRegistry.Subsystem.SEQUENCE_HANDLER));
            }
            jsEngine.registerHostFunctions(hostFunctions);
            if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                log.debug("[createWithRemote] Registered " + hostFunctions.size() + " host functions: " +
                        hostFunctions.keySet());
            }

            // Build the complete script including require function, secrets, and main script.
            String completeScript = FrameworkServiceDataHolder.getInstance().getCodeForRequireFunction() +
                    "\n" +
                    FrameworkServiceDataHolder.getInstance().getCodeForSecretsFunction() +
                    "\n" +
                    script +
                    "\n" +
                    JS_FUNC_ON_LOGIN_REQUEST + "(context);";

            if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                log.debug("[createWithRemote] Sending script (length: " + completeScript.length() +
                        ") to External for evaluation");
            }

            // Build initial bindings (the External will attach a DynamicContextProxy as "context").
            Map<String, Object> initialBindings = new HashMap<>();

            String identifier = UUID.randomUUID().toString();
            Optional<JSExecutionMonitorData> optionalScriptExecutionData = Optional.empty();

            try {
                startScriptExecutionMonitor(identifier, authenticationContext);

                // Evaluate script remotely.
                EvaluationResult evalResult = jsEngine.evaluate(
                        completeScript, "adaptive-script", initialBindings);

                if (!evalResult.isSuccess()) {
                    log.error("[createWithRemote] Script evaluation failed: " + evalResult.getErrorMessage());
                    result.setBuildSuccessful(false);
                    result.setErrorReason("Error in executing the Javascript. " + evalResult.getErrorMessage());
                    return this;
                }

                if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                    log.debug("[createWithRemote] Script evaluation successful, elapsed: " +
                            evalResult.getElapsedMs() + "ms");
                }

                // Update bindings from External response.
                if (evalResult.getUpdatedBindings() != null) {
                    if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                        log.debug("[createWithRemote] Updating bindings from External: " +
                                evalResult.getUpdatedBindings().keySet());
                    }
                    for (Map.Entry<String, Object> entry : evalResult.getUpdatedBindings().entrySet()) {
                        jsEngine.putBinding(entry.getKey(), entry.getValue());
                    }
                }

            } finally {
                optionalScriptExecutionData = Optional.ofNullable(endScriptExecutionMonitor(identifier));
            }

            optionalScriptExecutionData.ifPresent(
                    scriptExecutionData ->
                            storeAuthScriptExecutionMonitorData(authenticationContext,
                            scriptExecutionData));

            if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                log.debug("[createWithRemote] Script execution completed for SP: " +
                        authenticationContext.getServiceProviderName());
            }

            // Persist bindings for later callback execution.
            // Note: With remote execution, we persist the updated bindings from External.
            Map<String, Object> persistableBindings = jsEngine.getBindings();
            authenticationContext.setProperty("JS_BINDING_CURRENT_CONTEXT", persistableBindings);
            if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                log.debug("[createWithRemote] Persisted " + persistableBindings.size() + " bindings");
            }

        } catch (Exception e) {
            log.error("[createWithRemote] Error during remote script evaluation", e);
            result.setBuildSuccessful(false);
            result.setErrorReason("Error in remote JavaScript execution: " + e.getMessage());
        } finally {
            currentBuilder.remove();
        }

        return this;
    }

    @Override
    public AuthenticationDecisionEvaluator getScriptEvaluator(GenericSerializableJsFunction fn) {

        return new RemoteJsBasedEvaluator((GraalSerializableJsFunction) fn);
    }

    // =============================================================================================
    // Graph infusion helper
    // =============================================================================================

    private boolean canInfuse(AuthGraphNode executingNode) {

        return executingNode instanceof DynamicDecisionNode && dynamicallyBuiltBaseNode.get() != null;
    }

    // =============================================================================================
    // Script execution monitoring
    // =============================================================================================

    private JSExecutionSupervisor getJSExecutionSupervisor() {

        return FrameworkServiceDataHolder.getInstance().getJsExecutionSupervisor();
    }

    private void storeAuthScriptExecutionMonitorData(AuthenticationContext context,
            JSExecutionMonitorData jsExecutionMonitorData) {

        context.setProperty(PROP_EXECUTION_SUPERVISOR_RESULT, jsExecutionMonitorData);
    }

    private JSExecutionMonitorData retrieveAuthScriptExecutionMonitorData(AuthenticationContext context) {

        JSExecutionMonitorData jsExecutionMonitorData;
        Object storedResult = context.getProperty(PROP_EXECUTION_SUPERVISOR_RESULT);
        if (storedResult != null) {
            jsExecutionMonitorData = (JSExecutionMonitorData) storedResult;
        } else {
            jsExecutionMonitorData = new JSExecutionMonitorData(0L, 0L);
        }
        return jsExecutionMonitorData;
    }

    private void startScriptExecutionMonitor(String identifier, AuthenticationContext context,
                                             JSExecutionMonitorData previousExecutionResult) {

        JSExecutionSupervisor jsExecutionSupervisor = getJSExecutionSupervisor();
        if (jsExecutionSupervisor == null) {
            return;
        }
        getJSExecutionSupervisor().monitor(identifier, context.getServiceProviderName(), context.getTenantDomain(),
                previousExecutionResult.getElapsedTime(), previousExecutionResult.getConsumedMemory());
    }

    private void startScriptExecutionMonitor(String identifier, AuthenticationContext context) {

        startScriptExecutionMonitor(identifier, context, new JSExecutionMonitorData(0L, 0L));
    }

    private JSExecutionMonitorData endScriptExecutionMonitor(String identifier) {

        JSExecutionSupervisor executionSupervisor = getJSExecutionSupervisor();
        if (executionSupervisor == null) {
            return null;
        }
        return getJSExecutionSupervisor().completed(identifier);
    }

    // =============================================================================================
    // Remote JavaScript Decision Evaluator (callback execution)
    // =============================================================================================

    /**
     * Remote JavaScript Decision Evaluator implementation.
     * This handles callback execution (e.g., onSuccess/onFail after a step completes)
     * by sending the serialized function to the external sidecar for evaluation.
     * The graph is re-organized based on the execution result, exactly as the local evaluator does.
     */
    public class RemoteJsBasedEvaluator implements AuthenticationDecisionEvaluator {

        private static final long serialVersionUID = 6853505881096840345L;
        private final GraalSerializableJsFunction jsFunction;

        public RemoteJsBasedEvaluator(GraalSerializableJsFunction jsFunction) {

            this.jsFunction = jsFunction;
        }

        @Override
        @HostAccess.Export
        @SuppressWarnings("unchecked")
        public Object evaluate(AuthenticationContext authenticationContext, Object... params) {

            RemoteJsGraalGraphBuilder graphBuilder = RemoteJsGraalGraphBuilder.this;
            Object result = null;
            if (jsFunction == null) {
                return null;
            }
            if (!jsFunction.isFunction()) {
                return jsFunction.getSource();
            }

            JsEngine jsEngine = new RemoteJsEngine(
                    GrpcTransportProvider.getTransport(JsGraalGraphEngineModeRouter.getGrpcTarget()),
                    authenticationContext);
            try {
                currentBuilder.set(graphBuilder);
                contextForJs.set(authenticationContext);

                // Log context info for debugging.
                if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                    log.debug("[evaluateRemote] Starting for SP: " + authenticationContext.getServiceProviderName() +
                            ", contextId: " + authenticationContext.getContextIdentifier() +
                            ", step: " + authenticationContext.getCurrentStep() +
                            ", authContext hashCode: " + System.identityHashCode(authenticationContext));
                }

                // Get persisted bindings from authentication context (variables like
                // rolesToStepUp).
                Map<String, Object> persistedBindings = (Map<String, Object>) authenticationContext
                        .getProperty("JS_BINDING_CURRENT_CONTEXT");
                if (persistedBindings != null) {
                    if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                        log.debug("[evaluateRemote] Found " + persistedBindings.size() +
                                " persisted bindings: " + persistedBindings.keySet());
                    }
                } else {
                    if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                        log.debug("[evaluateRemote] No persisted bindings found in authContext. " +
                                "Property keys: " + authenticationContext.getProperties().keySet());
                    }
                    persistedBindings = new HashMap<>();
                }

                // Register host functions that the External can call back.
                Map<String, Object> hostFunctions = new HashMap<>();
                hostFunctions.put(JS_FUNC_EXECUTE_STEP, new JsGraalStepExecuterInAsyncEvent());
                hostFunctions.put(JS_FUNC_SEND_ERROR, new SendErrorAsyncFunctionImpl());
                hostFunctions.put(JS_AUTH_FAILURE, new FailAuthenticationFunctionImpl());
                hostFunctions.put(JS_FUNC_SHOW_PROMPT, new JsGraalPromptExecutorImpl());
                hostFunctions.put(JS_FUNC_LOAD_FUNC_LIB, new JsGraalLoadExecutorImpl());
                hostFunctions.put(JS_FUNC_GET_SECRET_BY_NAME, new JsGraalGetSecretImpl());
                // Mirror the local-execution factory bindings — see the matching
                // block in createWithRemote for the rationale.
                hostFunctions.put(JS_LOG, new JsLogger());
                hostFunctions.put(JS_FUNC_SELECT_ACR_FROM, new GraalSelectAcrFromFunction());

                JsFunctionRegistry jsFunctionRegistrar = FrameworkServiceDataHolder.getInstance()
                        .getJsFunctionRegistry();
                if (jsFunctionRegistrar != null) {
                    Map<String, Object> functionMap = jsFunctionRegistrar
                            .getSubsystemFunctionsMap(JsFunctionRegistry.Subsystem.SEQUENCE_HANDLER);
                    hostFunctions.putAll(functionMap);
                }
                jsEngine.registerHostFunctions(hostFunctions);

                String identifier = UUID.randomUUID().toString();
                Optional<JSExecutionMonitorData> optionalScriptExecutionData = Optional
                        .ofNullable(retrieveAuthScriptExecutionMonitorData(authenticationContext));
                try {
                    startScriptExecutionMonitor(identifier, authenticationContext,
                            optionalScriptExecutionData.orElse(null));

                    dynamicallyBuiltBaseNode.remove();

                    // Execute the callback function in the External with persisted bindings
                    EvaluationResult evalResult = jsEngine.executeCallback(
                            jsFunction.getSource(),
                            params,
                            persistedBindings,
                            authenticationContext);

                    if (evalResult.isSuccess()) {
                        result = evalResult.getResult();

                        // Re-persist updated bindings so next callback sees changes
                        Map<String, Object> updatedBindings = jsEngine.getBindings();
                        authenticationContext.setProperty("JS_BINDING_CURRENT_CONTEXT", updatedBindings);
                        if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                            log.debug("[evaluateRemote] Re-persisted " + updatedBindings.size() +
                                    " bindings after callback");
                        }

                        if (JsGraalGraphEngineModeRouter.isTracingEnabled() && log.isDebugEnabled()) {
                            log.debug("Remote JS execution succeeded for SP: " +
                                    authenticationContext.getServiceProviderName() +
                                    ", elapsed: " + evalResult.getElapsedMs() + "ms");
                        }
                    } else {
                        log.error("Remote JS execution failed for SP: " +
                                authenticationContext.getServiceProviderName() +
                                ", error: " + evalResult.getErrorMessage());
                        AuthGraphNode executingNode = (AuthGraphNode) authenticationContext
                                .getProperty(PROP_CURRENT_NODE);
                        FailNode failNode = new FailNode();
                        failNode.setShowErrorPage(true);
                        failNode.getFailureData().put("errorCode", "18013");
                        failNode.getFailureData().put("errorMessage",
                                "Script execution failed: " + evalResult.getErrorMessage());
                        failNode.getFailureData().put("errorType",
                                evalResult.getErrorType() != null ? evalResult.getErrorType() : "ScriptError");
                        attachToLeaf(executingNode, failNode);
                    }
                } finally {
                    optionalScriptExecutionData = Optional.ofNullable(endScriptExecutionMonitor(identifier));
                }
                optionalScriptExecutionData.ifPresent(
                        scriptExecutionData ->
                                storeAuthScriptExecutionMonitorData(authenticationContext,
                                scriptExecutionData));

                // dynamicallyBuiltBaseNode is already on Thread -- callbacks ran inline
                // via the message loop, so no cross-thread propagation is needed.
                // canInfuse/infuse read the ThreadLocal directly.
                AuthGraphNode executingNode = (AuthGraphNode) authenticationContext.getProperty(PROP_CURRENT_NODE);
                if (canInfuse(executingNode)) {
                    infuse(executingNode, dynamicallyBuiltBaseNode.get());
                }

            } catch (Throwable e) {
                log.error("Error in remote JavaScript execution for service provider : " +
                        authenticationContext.getServiceProviderName() + ", Javascript Fragment : \n" +
                        jsFunction.getSource(), e);
                AuthGraphNode executingNode = (AuthGraphNode) authenticationContext.getProperty(PROP_CURRENT_NODE);
                FailNode failNode = new FailNode();
                failNode.setShowErrorPage(true);
                failNode.getFailureData().put("errorCode", "18013");
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                failNode.getFailureData().put("errorMessage", "Script execution error: " + errorMessage);
                failNode.getFailureData().put("errorType", e.getClass().getSimpleName());
                attachToLeaf(executingNode, failNode);
            } finally {
                contextForJs.remove();
                dynamicallyBuiltBaseNode.remove();
                clearCurrentBuilder();
            }
            return result;
        }
    }
}
