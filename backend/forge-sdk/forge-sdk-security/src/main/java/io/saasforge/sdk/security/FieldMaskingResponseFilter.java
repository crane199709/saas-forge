package io.saasforge.sdk.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** 仅在 MVC 选中 JSON 转换器后缓冲其真实输出，文件与流式响应继续直接写出。 */
final class FieldMaskingResponseFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var selective = new SelectiveResponse(response);
        chain.doFilter(request, selective);
        if (!isAsyncStarted(request)) {
            selective.complete();
        }
    }

    static final class SelectiveResponse extends HttpServletResponseWrapper {
        private ContentCachingResponseWrapper buffer;

        SelectiveResponse(HttpServletResponse response) {
            super(response);
        }

        void enable() {
            if (buffer == null) {
                buffer = new ContentCachingResponseWrapper((HttpServletResponse) getResponse());
                setResponse(buffer);
            }
        }

        boolean enabled() {
            return buffer != null;
        }

        void complete() throws IOException {
            if (buffer != null) {
                buffer.copyBodyToResponse();
            }
        }

        @Override
        public void sendError(int status) throws IOException {
            if (buffer != null) {
                buffer.resetBuffer();
            }
            super.sendError(status);
        }

        @Override
        public void sendError(int status, String message) throws IOException {
            if (buffer != null) {
                buffer.resetBuffer();
            }
            super.sendError(status, message);
        }
    }
}
