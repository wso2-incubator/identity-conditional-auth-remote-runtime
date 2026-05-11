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

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * ProxyArray-shaped sibling of DynamicContextProxy. Used when the wire response
 * names a proxyType that IS knows is backed by a ProxyArray wrapper (today:
 * JsGraalSteps under "steps"). Lets scripts use Array.isArray(context.steps),
 * for-of, .forEach, .map, etc. — the array surface that a ProxyObject can't
 * expose.
 *
 * Plumbing piggybacks on the path-routing rules already on the IS side:
 * getSize() reads "<basePath>::length" (synthesised by PropertyPathNavigator
 * for ProxyArray) and get(i) reads "<basePath>::<i>" (which the same navigator
 * already routes to JsGraalSteps.get(i) via its numeric-segment branch). No
 * proto change, no new IS-side dispatch.
 *
 * Writes are deliberately silent no-ops: JsGraalSteps treats set(...) as a
 * no-op in LOCAL mode ("Steps can not be set with script"), so we mirror that
 * here. remove() falls through to the ProxyArray default, which throws
 * UnsupportedOperationException — same effective behaviour as LOCAL where no
 * remove path exists.
 */
class DynamicContextProxyArray implements ProxyArray {

    private static final Logger log = LoggerFactory.getLogger(DynamicContextProxyArray.class);
    private static final String LENGTH_SEGMENT = "length";

    private final String sessionId;
    private final HostCallbackClient callbackClient;
    private final String proxyType;
    private final String basePath;

    DynamicContextProxyArray(String sessionId, HostCallbackClient callbackClient,
                             String proxyType, String basePath) {
        this.sessionId = sessionId;
        this.callbackClient = callbackClient;
        this.proxyType = proxyType;
        this.basePath = basePath;
        if (log.isDebugEnabled()) {
            log.debug("[DynamicContextProxyArray] Created - type: {}, basePath: {}", proxyType, basePath);
        }
    }

    public String getProxyType() {
        return proxyType;
    }

    public String getBasePath() {
        return basePath;
    }

    public String getSessionId() {
        return sessionId;
    }

    @Override
    public long getSize() {
        try {
            String propertyPath = basePath.isEmpty() ? LENGTH_SEGMENT : basePath + "::" + LENGTH_SEGMENT;
            ContextPropertyResponse response = callbackClient.getContextProperty(propertyPath, proxyType);

            if (!response.getSuccess()) {
                // IS-side length resolution failed. Surface it instead of silently
                // returning zero — LOCAL ProxyArray.getSize would propagate the
                // underlying exception, and the silent-zero was masking real errors.
                throw new IllegalStateException(
                        "getSize failed for path '" + propertyPath + "' (proxyType=" + proxyType +
                                "): " + response.getErrorMessage());
            }

            Object value = ValueSerializationUtils.deserialize(response.getValue());
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            throw new IllegalStateException(
                    "getSize for path '" + propertyPath + "' returned non-numeric value: " +
                            (value != null ? value.getClass().getName() + "=" + value : "null"));

        } catch (IOException e) {
            log.error("[DynamicContextProxyArray] Error getting array length for '{}': {}",
                    basePath, e.getMessage());
            throw new UncheckedIOException(
                    "[DynamicContextProxyArray] Failed to get array length for path '" + basePath + "' from IS", e);
        }
    }

    @Override
    public Object get(long index) {
        try {
            String segment = Long.toString(index);
            String propertyPath = basePath.isEmpty() ? segment : basePath + "::" + segment;
            ContextPropertyResponse response = callbackClient.getContextProperty(propertyPath, proxyType);

            if (!response.getSuccess()) {
                log.debug("[DynamicContextProxyArray] index {} read failed for path '{}': {}",
                        index, propertyPath, response.getErrorMessage());
                return null;
            }

            if (response.getIsProxy()) {
                String[] elementMemberKeys = null;
                if (response.getMemberKeysCount() > 0) {
                    elementMemberKeys = response.getMemberKeysList().toArray(new String[0]);
                }
                return ProxyFactory.fromMarker(
                        sessionId, callbackClient,
                        response.getProxyType(), propertyPath, elementMemberKeys);
            }

            return ValueSerializationUtils.deserialize(response.getValue());

        } catch (IOException e) {
            log.error("[DynamicContextProxyArray] Error getting array element [{}] for '{}': {}",
                    index, basePath, e.getMessage());
            throw new UncheckedIOException(
                    "[DynamicContextProxyArray] Failed to get array element [" + index +
                            "] for path '" + basePath + "' from IS", e);
        }
    }

    @Override
    public void set(long index, Value value) {
        // Mirror LOCAL JsGraalSteps: writes to steps are silently ignored.
        // ("Steps can not be set with script.")
        if (log.isDebugEnabled()) {
            log.debug("[DynamicContextProxyArray] Ignored write at index {} on '{}'", index, basePath);
        }
    }

    // remove(long) intentionally not overridden — ProxyArray's default throws
    // UnsupportedOperationException, which is the right surface for a
    // wrapper that doesn't support deletion in LOCAL either.
}
