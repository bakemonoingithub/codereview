package com.codereview.agent.invocation;

import java.time.Duration;
import java.util.Map;

/** 模型传输边界：只负责一次 HTTP 尝试，不解释业务错误。 */
public interface ModelTransport {

    TransportResponse post(String endpoint, String credential, String body, Duration timeout);

    record TransportResponse(int status, String body, Map<String, String> headers, long latencyMillis) {
    }

    /** 连接失败或超时；是否重试由调用服务决定。 */
    class TransportFailure extends RuntimeException {

        private final boolean timeout;

        public TransportFailure(boolean timeout, String message, Throwable cause) {
            super(message, cause);
            this.timeout = timeout;
        }

        public boolean timeout() {
            return timeout;
        }
    }
}
