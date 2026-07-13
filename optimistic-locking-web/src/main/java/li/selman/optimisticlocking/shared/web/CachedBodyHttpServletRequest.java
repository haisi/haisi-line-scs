package li.selman.optimisticlocking.shared.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the request body exactly once, eagerly, and serves it back as a fresh stream on every
 * subsequent {@link #getInputStream()}/{@link #getReader()} call -- unlike {@link
 * org.springframework.web.util.ContentCachingRequestWrapper}, whose caching only happens lazily as
 * a consumer reads, which would leave nothing behind for a second reader. {@code IdempotencyFilter}
 * needs the raw bytes itself (to fingerprint the request) while still leaving the body readable for
 * whatever {@code @RequestBody} binding happens further down the chain.
 */
class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    byte[] getCachedBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() {
                return byteArrayInputStream.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return byteArrayInputStream.read(b, off, len);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
