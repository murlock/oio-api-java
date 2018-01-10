package io.openio.sds.http;

import static io.openio.sds.common.JsonUtils.gson;
import static io.openio.sds.common.OioConstants.CONTENT_LENGTH_HEADER;
import static io.openio.sds.common.OioConstants.CONTENT_TYPE_HEADER;
import static io.openio.sds.common.OioConstants.DELETE_METHOD;
import static io.openio.sds.common.OioConstants.GET_METHOD;
import static io.openio.sds.common.OioConstants.OIO_CHARSET;
import static io.openio.sds.common.OioConstants.OIO_TIMEOUT_HEADER;
import static io.openio.sds.common.OioConstants.POST_METHOD;
import static io.openio.sds.common.OioConstants.PUT_METHOD;
import static io.openio.sds.common.Strings.nullOrEmpty;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gson.stream.JsonReader;

import io.openio.sds.common.Check;
import io.openio.sds.common.SocketProvider;
import io.openio.sds.common.DeadlineManager;
import io.openio.sds.exceptions.OioException;
import io.openio.sds.exceptions.OioSystemException;
import io.openio.sds.logging.SdsLogger;
import io.openio.sds.logging.SdsLoggerFactory;

/**
 * Simple HTTP client
 * 
 * @author Christopher Dedeurwaerder
 * @author Florent Vennetier
 */
public class OioHttp {

    private static final SdsLogger logger = SdsLoggerFactory.getLogger(SdsLogger.class);

    private static final String CRLF = "\r\n";

    private static final byte[] CRLF_BYTES = { '\r', '\n' };

    private OioHttpSettings settings;

    private SocketProvider socketProvider;

    private OioHttp(OioHttpSettings settings, SocketProvider socketProvider) {
        this.settings = settings;
        this.socketProvider = socketProvider;
    }

    public static OioHttp http(OioHttpSettings settings, SocketProvider socketProvider) {
        Check.checkArgument(null != settings);
        Check.checkArgument(null != socketProvider);
        return new OioHttp(settings, socketProvider);
    }

    public RequestBuilder post(String uri) {
        Check.checkArgument(!nullOrEmpty(uri));
        return new RequestBuilder().req(POST_METHOD, uri);
    }

    public RequestBuilder put(String uri) {
        Check.checkArgument(!nullOrEmpty(uri));
        return new RequestBuilder().req(PUT_METHOD, uri);
    }

    public RequestBuilder get(String uri) {
        Check.checkArgument(!nullOrEmpty(uri));
        return new RequestBuilder().req(GET_METHOD, uri);
    }

    public RequestBuilder delete(String uri) {
        Check.checkArgument(!nullOrEmpty(uri));
        return new RequestBuilder().req(DELETE_METHOD, uri);
    }

    public class RequestBuilder {

        private String method;
        private HashMap<String, String> headers = new HashMap<String, String>();
        private HashMap<String, String> query = new HashMap<String, String>();
        private String body;
        private InputStream data;
        private Long len;
        private URI uri;
        private OioHttpResponseVerifier verifier = null;
        private boolean chunked;
        private List<InetSocketAddress> hosts = null;
        private Integer deadline = null;

        public RequestBuilder req(String method, String url) {
            this.method = method;
            this.uri = URI.create(url);
            return this;
        }

        public RequestBuilder header(String name, String value) {
            if (!nullOrEmpty(name) && !nullOrEmpty(value))
                headers.put(name, value);
            return this;
        }

        public RequestBuilder headers(Map<String, String> headers) {
            if (null != headers)
                this.headers.putAll(headers);
            return this;
        }

        public RequestBuilder query(String name, String value) {
            if (!nullOrEmpty(name) && !nullOrEmpty(value))
                this.query.put(name, value);
            return this;
        }

        public RequestBuilder chunked() {
            this.headers.put("Transfer-Encoding", "chunked");
            this.chunked = true;
            return this;
        }

        public RequestBuilder body(String body) {
            if (nullOrEmpty(body))
                return this;
            headers.put(CONTENT_LENGTH_HEADER, String.valueOf(body.length()));
            headers.put(CONTENT_TYPE_HEADER, "application/json");
            this.body = body;
            return this;
        }

        public RequestBuilder body(InputStream data, Long size) {
            if (null == data)
                return this;
            headers.put(CONTENT_LENGTH_HEADER, String.valueOf(size));
            if (!headers.containsKey(CONTENT_TYPE_HEADER)) {
                headers.put(CONTENT_TYPE_HEADER, "application/octet-stream");
            }
            this.data = data;
            this.len = size;
            return this;
        }

        public RequestBuilder verifier(OioHttpResponseVerifier verifier) {
            this.verifier = verifier;
            return this;
        }

        /**
         * Set an execution deadline on the request.
         *
         * The deadline applies on the overall request treatment (first request and retries).
         * It is reduced by 1% before being transferred to the server, so the server should
         * reach its deadline before us.
         *
         * @param deadline the deadline for the request, in monotonic milliseconds
         * @return this
         */
        public RequestBuilder withDeadline(int deadline) {
            this.deadline = Integer.valueOf(deadline);
            return this;
        }

        /**
         * @param hosts
         *            An optional list of hosts to connect to.
         * @return this
         */
        public RequestBuilder hosts(List<InetSocketAddress> hosts) {
            this.hosts = hosts;
            return this;
        }

        public OioHttpResponse execute() throws OioException {
            if (this.hosts == null || this.hosts.isEmpty()) {
                return execute(new InetSocketAddress(uri.getHost(), uri.getPort()));
            } else {
                OioException lastExc = null;
                // TODO: implement better fallback mechanism, with randomization
                for (InetSocketAddress addr : this.hosts) {
                    try {
                        if (lastExc != null)
                            logger.info("Retrying on " + addr.toString());
                        return execute(addr);
                    } catch (OioException oioe) {
                        // Retry only if the cause is network
                        if (oioe.getCause() instanceof IOException) {
                            logger.warn("Failed to perform request", oioe);
                            lastExc = oioe;
                        } else {
                            throw oioe;
                        }
                    }
                }
                throw new OioSystemException("HTTP request execution error", lastExc);
            }
        }

        /**
         * If a deadline has been set on the request:
         * - check it;
         * - set the socket timeout accordingly;
         * - add a header so the server will check it too (99% of the actual deadline).
         */
        private void applyDeadline(Socket sock) throws SocketException {
            if (this.deadline != null) {
                DeadlineManager dlm = DeadlineManager.instance();
                int now = dlm.now();
                dlm.checkDeadline(this.deadline, now);
                int timeout = dlm.deadlineToTimeout(this.deadline, now);
                sock.setSoTimeout(timeout);
                // oio-proxy wants microseconds, and we remove 1% for the parsing overhead.
                headers.put(OIO_TIMEOUT_HEADER, String.valueOf(timeout * 990));
            }
        }

        private OioHttpResponse execute(InetSocketAddress addr) throws OioException {
            Socket sock = null;
            try {
                sock = socketProvider.getSocket(addr);
                applyDeadline(sock);
                if (chunked)
                    sendRequestChunked(sock);
                else
                    sendRequest(sock);
                OioHttpResponse resp = readResponse(sock);
                try {
                    if (null != verifier)
                        verifier.verify(resp);
                    return resp;
                } catch (OioException e) {
                    resp.close(true);
                    throw e;
                }
            } catch (IOException e) {
                if (null != sock)
                    try {
                        try {
                            sock.shutdownInput();
                        } catch (SocketException se) {
                            logger.debug("Socket input already shutdown");
                        }
                        sock.close();
                    } catch (IOException ioe) {
                        logger.warn("Failed to close socket, possible leak", ioe);
                    }
                throw new OioSystemException("HTTP request execution error", e);
            }
        }

        public <T> T execute(Class<T> c) {
            OioHttpResponse resp = execute();
            boolean success = false;
            try {
                T t = gson().fromJson(
                        new JsonReader(new InputStreamReader(resp.body(), OIO_CHARSET)), c);
                success = true;
                return t;
            } finally {
                resp.close(success);
            }
        }

        private OioHttpResponse readResponse(Socket sock) throws IOException {
            return OioHttpResponse.build(sock);
        }

        private void sendRequest(Socket sock) throws IOException {
            headers.put("Host",
                    sock.getLocalAddress().toString().substring(1) + ":" + sock.getLocalPort());
            headers.put("Connection", socketProvider.reusableSocket() ? "keep-alive" : "close");
            headers.put("Accept", "*/*");
            headers.put("Accept-Encoding", "gzip, deflate");
            headers.put("User-Agent", "oio-http");

            if (!headers.containsKey("Content-Length"))
                headers.put(CONTENT_LENGTH_HEADER, "0");

            BufferedOutputStream bos = new BufferedOutputStream(sock.getOutputStream(),
                    settings.sendBufferSize());
            bos.write(requestHead());
            if (null != data) {
                stream(bos);
            } else if (null != body) {
                bos.write(body.getBytes(OIO_CHARSET));
            }
            bos.flush();
        }

        private void sendRequestChunked(Socket sock) throws IOException {
            headers.put("Host",
                    sock.getLocalAddress().toString().substring(1) + ":" + sock.getLocalPort());
            headers.put("Connection", socketProvider.reusableSocket() ? "keep-alive" : "close");
            headers.put("Accept", "*/*");
            headers.put("Accept-Encoding", "gzip, deflate");
            headers.put("User-Agent", "oio-http");

            // ensure no content-length
            headers.remove("Content-Length");
            BufferedOutputStream bos = new BufferedOutputStream(sock.getOutputStream(),
                    settings.sendBufferSize());
            byte[] requestHead = requestHead();
            bos.write(requestHead);
            streamChunked(bos);
            bos.flush();
        }

        private void streamChunked(OutputStream os) throws IOException {
            byte[] b = new byte[settings.sendBufferSize()];
            int remaining = len.intValue();
            while (remaining > 0) {
                int read = data.read(b, 0, Math.min(remaining, b.length));
                if (-1 == read)
                    throw new EOFException("Unexpected end of source stream");
                remaining -= read;
                if (read > 0) {
                    os.write((Integer.toHexString(read) + CRLF).getBytes(OIO_CHARSET));
                    os.write(b, 0, read);
                    os.write(CRLF_BYTES);
                }
            }
            os.write(("0" + CRLF + CRLF).getBytes(OIO_CHARSET));
        }

        private void stream(OutputStream sos) throws IOException {
            byte[] b = new byte[settings.sendBufferSize()];
            int remaining = len.intValue();

            while (remaining > 0) {
                int read = data.read(b, 0, Math.min(remaining, b.length));
                if (-1 == read)
                    throw new EOFException("Unexpected end of source stream");
                remaining -= read;
                sos.write(b, 0, read);
            }
        }

        private byte[] requestHead() throws UnsupportedEncodingException {
            StringBuilder qbuilder = new StringBuilder(null == uri.getRawQuery() ? ""
                    : uri.getRawQuery());
            boolean removeTrailindAnd = 0 == qbuilder.length();
            for (Entry<String, String> h : query.entrySet()) {
                qbuilder.append("&").append(URLEncoder.encode(h.getKey(), "utf-8")).append("=")
                        .append(URLEncoder.encode(h.getValue(), "utf-8"));
            }
            String uriPath = uri.getRawPath();
            StringBuilder req = new StringBuilder(method)
                    .append(" ")
                    .append(uriPath != null && !uriPath.isEmpty() ? uriPath : "/")
                    .append(qbuilder.length() > 0 ? "?" : "")
                    .append(qbuilder.length() > 0 ? (removeTrailindAnd ? qbuilder.substring(1)
                            : qbuilder.toString()) : "").append(" ").append("HTTP/1.1")
                    .append(CRLF);
            for (Entry<String, String> h : headers.entrySet()) {
                req.append(h.getKey()).append(": ").append(h.getValue()).append(CRLF);
            }
            byte[] res = req.append(CRLF).toString().getBytes(OIO_CHARSET);
            return res;
        }
    }
}
