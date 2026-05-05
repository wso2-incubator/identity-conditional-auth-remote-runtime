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

/**
 * Configuration keys and defaults specific to the remote GraalJS engine bundle.
 * <p>
 * Kept separate from {@link RemoteEngineConstants} (which holds wire-protocol
 * markers exchanged with the External runtime) so that operator-facing config
 * surface and on-the-wire protocol surface evolve independently.
 * <p>
 * Only {@code AdaptiveAuth.GraalJS.EngineMode} crosses the framework boundary
 * (read by {@code JsGraalGraphBuilderFactory} to decide whether to route to the
 * remote provider) and therefore lives in {@code FrameworkConstants}; every other
 * key here is consumed only by classes inside this bundle.
 */
public final class RemoteEngineConfigConstants {

    private RemoteEngineConfigConstants() {
        // Utility class — no instances
    }

    /** gRPC target for the remote engine (host:port). */
    public static final String GRAALJS_GRPC_TARGET = "AdaptiveAuth.GraalJS.GrpcTarget";

    /** Toggle for verbose tracing/perf logs in the remote engine package. */
    public static final String GRAALJS_REMOTE_ENGINE_TRACING = "AdaptiveAuth.GraalJS.RemoteEngineTracing";

    /** Default engine mode applied when {@code AdaptiveAuth.GraalJS.EngineMode} is unset or invalid. */
    public static final String DEFAULT_ENGINE_MODE = "LOCAL";

    /** Default gRPC target used when {@code AdaptiveAuth.GraalJS.GrpcTarget} is unset. */
    public static final String DEFAULT_GRPC_TARGET = "localhost:50051";

    /** Default tracing toggle when {@code AdaptiveAuth.GraalJS.RemoteEngineTracing} is unset. */
    public static final boolean DEFAULT_REMOTE_ENGINE_TRACING = false;
}
