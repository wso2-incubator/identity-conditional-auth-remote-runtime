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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Picks the right proxy shape for an is_proxy=true response coming back from IS.
 * The wire only carries a string proxyType, so we mirror IS's ProxyTypeResolver
 * registry just enough to know which strings represent ProxyArray-shaped wrappers
 * (today: only "steps" for JsGraalSteps). Anything else falls through to the
 * existing DynamicContextProxy path, so a missing entry in this set degrades
 * gracefully to today's behaviour rather than mis-shaping the proxy.
 *
 * Keep this set in sync with the IS-side ProxyTypeResolver.PROXY_TYPE_NAMES
 * entries whose Java class implements org.graalvm.polyglot.proxy.ProxyArray.
 * Adding too few entries: scripts lose Array.isArray / for-of on that surface
 * but keep working via direct index access. Adding too many: scripts get
 * Array.isArray=true on a non-array surface and for-of fails when the
 * synthesised "length" RPC comes back wrong. Be conservative.
 */
final class ProxyFactory {

    private static final Set<String> ARRAY_PROXY_TYPES;
    static {
        Set<String> s = new HashSet<>();
        s.add("steps"); // JsGraalSteps — the only ProxyArray-shaped wrapper today
        ARRAY_PROXY_TYPES = Collections.unmodifiableSet(s);
    }

    private ProxyFactory() {
        // Utility class
    }

    /**
     * Build the right proxy for a marker: ProxyArray-shaped if proxyType matches
     * a known IS-side ProxyArray wrapper, ProxyObject-shaped otherwise. The
     * memberKeys hint is forwarded to DynamicContextProxy for API parity but is
     * not used by the array variant — arrays don't have member keys.
     */
    static Object fromMarker(String sessionId, HostCallbackClient callbackClient,
                             String proxyType, String basePath, String[] memberKeys) {
        if (ARRAY_PROXY_TYPES.contains(proxyType)) {
            return new DynamicContextProxyArray(sessionId, callbackClient, proxyType, basePath);
        }
        return new DynamicContextProxy(sessionId, callbackClient, proxyType, basePath, memberKeys);
    }

    /**
     * Convenience overload for sites that have no memberKeys hint.
     */
    static Object fromMarker(String sessionId, HostCallbackClient callbackClient,
                             String proxyType, String basePath) {
        return fromMarker(sessionId, callbackClient, proxyType, basePath, null);
    }
}
