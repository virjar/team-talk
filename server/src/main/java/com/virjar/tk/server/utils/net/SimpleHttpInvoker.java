package com.virjar.tk.server.utils.net;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Sets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 基于java原生的http访问，
 */
public class SimpleHttpInvoker {

    // 请求参数
    // 请求头部
    private static final ThreadLocal<LinkedHashMap<String, String>> threadLocalRequestHeader = new ThreadLocal<>();
    // 127.0.0.1:8888
    private static final ThreadLocal<String> threadLocalProxy = new ThreadLocal<>();
    private static final ThreadLocal<Proxy.Type> threadLocalProxyType = new ThreadLocal<>();

    private static final ThreadLocal<SimpleHttpInvoker.DefaultHttpPropertyBuilder> threadLocalDefaultHttpProperty = new ThreadLocal<>();

    private static final ThreadLocal<Integer> threadLocalConnectionTimeout = new ThreadLocal<>();

    private static final ThreadLocal<Integer> threadLocalReadTimeout = new ThreadLocal<>();

    // 响应参数
    private static final ThreadLocal<Integer> threadLocalResponseStatus = new ThreadLocal<>();
    private static final ThreadLocal<LinkedHashMap<String, String>> threadLocalResponseHeader = new ThreadLocal<>();
    private static final ThreadLocal<IOException> threadLocalResponseIOException = new ThreadLocal<>();

    public static String get(String url) {
        return asString(execute("GET", url, null));
    }

    public static byte[] getEntity(String url) {
        return execute("GET", url, null);
    }

    public static String post(String url, JSONObject body) {
        addHeader("Content-Type", "application/json; charset=UTF-8");
        return asString(execute("POST", url, body.toJSONString().getBytes(StandardCharsets.UTF_8)));
    }

    public static String post(String url, String body) {
        return asString(execute("POST", url, body.getBytes(StandardCharsets.UTF_8)));
    }

    public static String post(String url, Map<String, String> body) {
        addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        return asString(execute("POST", url, encodeURLParam(body).getBytes(StandardCharsets.UTF_8)));
    }

    public static String encodeURLParam(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append("=");
            String value = entry.getValue();
            if (value != null) {
                sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
            sb.append("&");
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    public static void setProxy(String ip, Integer port) {
        threadLocalProxy.set(ip + ":" + port);
    }

    public static void setProxyAuth(String userName, String password) {
        GlobalAuthentication.setProxyAuth(userName, password);
    }

    public static void setProxyType(Proxy.Type proxyType) {
        threadLocalProxyType.set(proxyType);
    }

    public static void setTimout(int connectTimout, int readTimeout) {
        threadLocalConnectionTimeout.set(connectTimout);
        threadLocalReadTimeout.set(readTimeout);
    }

    public static void addHeader(Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            addHeader(entry.getKey(), entry.getValue());
        }
    }

    public static void addHeader(String key, String value) {
        LinkedHashMap<String, String> map = threadLocalRequestHeader.get();
        if (map == null) {
            map = new LinkedHashMap<>();
            threadLocalRequestHeader.set(map);
        }
        map.put(key.toLowerCase(), value);
    }

    public static IOException getIoException() {
        return threadLocalResponseIOException.get();
    }

    public static void withIOException(IOException ioException) {
        threadLocalResponseIOException.set(ioException);
    }

    public static int getResponseStatus() {
        return threadLocalResponseStatus.get();
    }

    public static LinkedHashMap<String, String> getResponseHeader() {
        return threadLocalResponseHeader.get();
    }

    public static String getResponseHeader(String key) {
        return getResponseHeader().get(key.toLowerCase());
    }

    public static void setupDefaultHttpProperty(SimpleHttpInvoker.DefaultHttpPropertyBuilder defaultHttpPropertyBuilder) {
        threadLocalDefaultHttpProperty.set(defaultHttpPropertyBuilder);
        defaultHttpPropertyBuilder.build(newBuilder());
    }

    public static byte[] execute(String method, String url, byte[] body) {
        // reset response
        threadLocalResponseStatus.remove();
        threadLocalResponseHeader.remove();
        threadLocalResponseIOException.remove();

        // prepare proxy
        String proxyConfig = threadLocalProxy.get();
        HttpURLConnection connection;
        try {
            URL urlMode = new URL(url);
            if (proxyConfig != null && !proxyConfig.trim().isEmpty()) {
                // 有代理配置
                Proxy.Type type = threadLocalProxyType.get();
                if (type == null) {
                    type = Proxy.Type.HTTP;
                }
                String[] ipAndPort = proxyConfig.trim().split(":");
                Proxy proxy = new Proxy(type, new InetSocketAddress(
                        ipAndPort[0].trim(), Integer.parseInt(ipAndPort[1].trim())
                ));
                connection = (HttpURLConnection) urlMode.openConnection(proxy);
            } else {
                // 没有代理
                connection = (HttpURLConnection) urlMode.openConnection();
            }

            connection.setRequestMethod(method);

            Integer connectionTimeout = threadLocalConnectionTimeout.get();
            if (connectionTimeout == null) {
                connectionTimeout = 30000;
            }
            connection.setConnectTimeout(connectionTimeout);

            Integer readTimeout = threadLocalReadTimeout.get();
            if (readTimeout == null) {
                readTimeout = 30000;
            }
            // 一定要设置超时时间，在有代理的情况下，java的默认超时时间简直太长了
            connection.setReadTimeout(readTimeout);

            // fill http header
            LinkedHashMap<String, String> requestHeaders = threadLocalRequestHeader.get();
            if (requestHeaders != null) {
                for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }
            }
            return readResponse(connection);
        } catch (IOException e) {
            threadLocalResponseIOException.set(e);
            return null;
        } finally {
            // clear request
            threadLocalRequestHeader.remove();
            threadLocalProxy.remove();
            threadLocalProxyType.remove();

            SimpleHttpInvoker.DefaultHttpPropertyBuilder builder = threadLocalDefaultHttpProperty.get();
            if (builder != null) {
                builder.build(newBuilder());
            }
        }
    }


    public static String asString(byte[] data) {
        if (data == null) {
            return null;
        }
        LinkedHashMap<String, String> headers = threadLocalResponseHeader.get();
        Charset charset = StandardCharsets.UTF_8;

        //content-type: application/json
        String contentType = headers.get("content-type");
        if (contentType != null && contentType.contains(":")) {
            String charsetStr = contentType.split(":")[1].trim();
            charset = Charset.forName(charsetStr);
        }
        return new String(data, charset);
    }

    private static byte[] readResponse(HttpURLConnection connection) throws IOException {
        threadLocalResponseStatus.set(connection.getResponseCode());
        LinkedHashMap<String, String> responseHeader = new LinkedHashMap<>();
        for (int i = 0; i < 128; i++) {
            String key = connection.getHeaderFieldKey(i);
            if (key == null) {
                if (i == 0) {
                    continue;
                } else {
                    break;
                }
            }
            responseHeader.put(key.toLowerCase(), connection.getHeaderField(key));
        }
        threadLocalResponseHeader.set(responseHeader);

        try (InputStream inputStream = connection.getInputStream()) {
            InputStream is = inputStream;
            String contentEncoding = responseHeader.get("Content-Encoding".toLowerCase());
            if (StringUtils.equalsIgnoreCase(contentEncoding, "gzip")) {
                is = new GZIPInputStream(inputStream);
            }
            return IOUtils.toByteArray(is);
        } finally {
            connection.disconnect();
        }
    }


    public static SimpleHttpInvoker.RequestBuilder newBuilder() {
        return new SimpleHttpInvoker.RequestBuilder();
    }

    public static class RequestBuilder {

        public SimpleHttpInvoker.RequestBuilder setProxy(String ip, Integer port) {
            SimpleHttpInvoker.setProxy(ip, port);
            return this;
        }

        public SimpleHttpInvoker.RequestBuilder setProxyType(Proxy.Type proxyType) {
            SimpleHttpInvoker.setProxyType(proxyType);
            return this;
        }

        public SimpleHttpInvoker.RequestBuilder addHeader(Map<String, String> headers) {
            SimpleHttpInvoker.addHeader(headers);
            return this;
        }

        public SimpleHttpInvoker.RequestBuilder addHeader(String key, String value) {
            SimpleHttpInvoker.addHeader(key, value);
            return this;
        }

        public SimpleHttpInvoker.RequestBuilder setProxyAuth(String userName, String password) {
            GlobalAuthentication.setProxyAuth(userName, password);
            return this;
        }

        public SimpleHttpInvoker.RequestBuilder setTimout(int connectTimout, int readTimeout) {
            SimpleHttpInvoker.setTimout(connectTimout, readTimeout);
            return this;
        }

    }

    public interface DefaultHttpPropertyBuilder {
        void build(SimpleHttpInvoker.RequestBuilder requestBuilder);
    }
}