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

package org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import static org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote.RemoteEngineConfigConstants.DEFAULT_GRPC_TARGET;
import static org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote.RemoteEngineConfigConstants.DEFAULT_REMOTE_ENGINE_TRACING;
import static org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote.RemoteEngineConfigConstants.GRAALJS_GRPC_TARGET;
import static org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote.RemoteEngineConfigConstants.GRAALJS_REMOTE_ENGINE_TRACING;

/**
 * Holder for remote-engine deployment-time configuration consumed inside this bundle.
 * <p>
 * Owns the gRPC target and tracing toggle read from {@code IdentityUtil}; both are
 * needed by remote transport code (e.g. {@code RemoteJsGraalGraphBuilder}) and by
 * verbose-log guards in {@code HostFunctionRegistry}. Engine-mode dispatch (LOCAL /
 * REMOTE / HYBRID) and the per-request HYBRID decision now live in the framework
 * (consumed via {@code ScriptEngineModeResolver}); this class is intentionally
 * scoped to remote-engine specifics so the framework never needs to read this
 * bundle's settings.
 * <p>
 * The {@code Router} naming is retained for source-history continuity even though
 * the per-request routing role moved to the framework.
 */
public class JsGraalGraphEngineModeRouter {

    private static final Log log = LogFactory.getLog(JsGraalGraphEngineModeRouter.class);

    private String grpcTarget = DEFAULT_GRPC_TARGET;
    private boolean tracingEnabled = DEFAULT_REMOTE_ENGINE_TRACING;

    private static volatile JsGraalGraphEngineModeRouter instance;

    private JsGraalGraphEngineModeRouter() {

        initializeFromConfig();
    }

    /**
     * Get the singleton instance. Lazy-initialised so {@code IdentityUtil} config is
     * available by the first call from a runtime path.
     *
     * @return the singleton {@code JsGraalGraphEngineModeRouter}.
     */
    public static JsGraalGraphEngineModeRouter getInstance() {

        if (instance == null) {
            synchronized (JsGraalGraphEngineModeRouter.class) {
                if (instance == null) {
                    instance = new JsGraalGraphEngineModeRouter();
                }
            }
        }
        return instance;
    }

    /**
     * @return gRPC target string ({@code host:port}) for the remote engine.
     */
    public static String getGrpcTarget() {

        return getInstance().grpcTarget;
    }

    /**
     * @return {@code true} if remote-engine tracing is enabled. When {@code false},
     *         debug/perf statements in the remote package short-circuit regardless
     *         of log level.
     */
    public static boolean isTracingEnabled() {

        return getInstance().tracingEnabled;
    }

    private void initializeFromConfig() {

        String target = IdentityUtil.getProperty(GRAALJS_GRPC_TARGET);
        if (target != null && !target.isEmpty()) {
            grpcTarget = target.trim();
        }

        String tracingStr = IdentityUtil.getProperty(GRAALJS_REMOTE_ENGINE_TRACING);
        if (tracingStr != null && !tracingStr.isEmpty()) {
            tracingEnabled = Boolean.parseBoolean(tracingStr.trim());
        }

        log.info("JsGraalGraphEngineModeRouter initialized. gRPC Target: " + grpcTarget +
                ", RemoteEngineTracing: " + tracingEnabled);
    }
}
