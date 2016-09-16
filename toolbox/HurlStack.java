/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley.toolbox;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Request.Method;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * An {@link HttpStack} based on {@link HttpURLConnection}.
 */

/**
 * 当os version 版本在2.3以上，也就是sdk >= 9 的时候
 * 选用这个接口作为HttpStack， 用到了HttpURLConnection
 * 关于HttpURLConnection,官方解释为：
 * An URLConnection for HTTP (RFC 2616) used to send and receive data over the web.
 * Data may be of any type and length. 
 * This class may be used to send and receive streaming data whose length is not known in advance.
 * 用来发送和接受数据，数据可以为任意的形式及长度
 * 这个类常用来发送和接受数据流里面长度不定的数据.
 */
public class HurlStack implements HttpStack {

    /*
     * 请求header中的一个关键字
     * content-type代表着被发送的请求中主体内容
     * 可以设置application/json等格式
     */
    private static final String HEADER_CONTENT_TYPE = "Content-Type";

    /**
     * An interface for transforming URLs before use.
     * 一个用来在使用url之前，将url处理的接口工具
     * 可能是用来规范url格式的一个工具= =
     */ 
    public interface UrlRewriter {
        /**
         * Returns a URL to use instead of the provided one, or null to indicate
         * this URL should not be used at all.
         */
        public String rewriteUrl(String originalUrl);
    }

    private final UrlRewriter mUrlRewriter;

    /**
     * The abstract factory implementation to create SSLSockets.
     * 是一个抽象工厂类，用来创建SSLSockets（还是不懂是个什么鬼
     * 
     * 对于SSLSocket，官方的解释是这样的：
     * The extension of Socket providing secure protocols like SSL (Secure Sockets Layer) or TLS (Transport Layer Security).
     * 是Socket的子类，并在之基础上新增了类似于SSL或者TLS等等的安全协议.
     */
    private final SSLSocketFactory mSslSocketFactory;

    public HurlStack() {
        this(null);
    }

    /**
     * @param urlRewriter Rewriter to use for request URLs
     */
    public HurlStack(UrlRewriter urlRewriter) {
        this(urlRewriter, null);
    }

    /**
     * @param urlRewriter Rewriter to use for request URLs
     * @param sslSocketFactory SSL factory to use for HTTPS connections
     */
    public HurlStack(UrlRewriter urlRewriter, SSLSocketFactory sslSocketFactory) {
        mUrlRewriter = urlRewriter;
        mSslSocketFactory = sslSocketFactory;
    }


    /**
     * 该函数为HttpStack的接口
     */
    @Override
    public HttpResponse performRequest(Request<?> request, Map<String, String> additionalHeaders)
            throws IOException, AuthFailureError {
        /**
         * 得到请求的url
         */
        String url = request.getUrl();

        /**
         * 创建一个新的HashMap
         * 用来存放请求的header的信息
         */
        HashMap<String, String> map = new HashMap<String, String>();
        
        /**
         * 将原request(volley自己封装的一个request类)中的header
         * 和另外需要添加入header的信息都整合起来
         */
        map.putAll(request.getHeaders());
        map.putAll(additionalHeaders);

        
        if (mUrlRewriter != null) {
            String rewritten = mUrlRewriter.rewriteUrl(url);
            if (rewritten == null) {
                throw new IOException("URL blocked by rewriter: " + url);
            }
            url = rewritten;
        }

        /**
         * 将url字符串形式规范成一个URL的类对象
         */
        URL parsedUrl = new URL(url);

        /**
         * HurlStack类是在sdk>=2.3的android版本上使用的
         * 这里面用到了HttpURLConnection类
         * 在函数里面打开了并返回了一个HttpURLConnection
         * 设置了HttpURLConnection的响应超时阀值
         */
        HttpURLConnection connection = openConnection(parsedUrl, request);

        /**
         * 开始给HttpURLConnection添加header的信息
         * 用addRequestProperty()函数将header以键值对的形式填入
         */
        for (String headerName : map.keySet()) {
            connection.addRequestProperty(headerName, map.get(headerName));
        }

        /**
         * 根据request种类的不同
         * 分别用不同的方式来处理其中的参数
         */
        setConnectionParametersForRequest(connection, request);

        // Initialize HttpResponse with data from the HttpURLConnection.
        ProtocolVersion protocolVersion = new ProtocolVersion("HTTP", 1, 1);

        int responseCode = connection.getResponseCode();
        if (responseCode == -1) {
            // -1 is returned by getResponseCode() if the response code could not be retrieved.
            // Signal to the caller that something was wrong with the connection.
            throw new IOException("Could not retrieve response code from HttpUrlConnection.");
        }
        StatusLine responseStatus = new BasicStatusLine(protocolVersion,
                connection.getResponseCode(), connection.getResponseMessage());

        BasicHttpResponse response = new BasicHttpResponse(responseStatus);

        response.setEntity(entityFromConnection(connection));

        for (Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if (header.getKey() != null) {
                Header h = new BasicHeader(header.getKey(), header.getValue().get(0));
                response.addHeader(h);
            }
        }
        return response;
    }

    /**
     * Initializes an {@link HttpEntity} from the given {@link HttpURLConnection}.
     * @param connection
     * @return an HttpEntity populated with data from <code>connection</code>.
     */
    private static HttpEntity entityFromConnection(HttpURLConnection connection) {
        BasicHttpEntity entity = new BasicHttpEntity();
        InputStream inputStream;
        try {
            inputStream = connection.getInputStream();
        } catch (IOException ioe) {
            inputStream = connection.getErrorStream();
        }
        entity.setContent(inputStream);
        entity.setContentLength(connection.getContentLength());
        entity.setContentEncoding(connection.getContentEncoding());
        entity.setContentType(connection.getContentType());
        return entity;
    }

    /**
     * Create an {@link HttpURLConnection} for the specified {@code url}.
     */
    protected HttpURLConnection createConnection(URL url) throws IOException {
        
        return (HttpURLConnection) url.openConnection();
    }

    /**
     * Opens an {@link HttpURLConnection} with parameters.
     * 通过给的url和参数，打开一个HttpURLConnection
     * @param url
     * @return an open connection
     * @throws IOException
     */
    private HttpURLConnection openConnection(URL url, Request<?> request) throws IOException {

        HttpURLConnection connection = createConnection(url);

        /**
         * 通过Request.java中的函数
         * 获取到该request上所设置的服务器最大响应时间阀值
         * 该阀值默认是2500ms，而且可能会随着retry的次数而增大
         */
        int timeoutMs = request.getTimeoutMs();

        /**
         * 给connection设置上请求超时时间
         */
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setUseCaches(false);
        connection.setDoInput(true);

        /**
         * use caller-provided custom SslSocketFactory, if any, for HTTPS
         * 请求方面的安全问题，暂时还不清清楚
         */
        if ("https".equals(url.getProtocol()) && mSslSocketFactory != null) {
            ((HttpsURLConnection)connection).setSSLSocketFactory(mSslSocketFactory);
        }

        return connection;
    }

    @SuppressWarnings("deprecation")
    /* package */
    /**
     * switch不同的请求方法
     * 来以不同的方式给HttpURLConnection添加请求参数
     */ 
    static void setConnectionParametersForRequest(HttpURLConnection connection,
            Request<?> request) throws IOException, AuthFailureError {
        switch (request.getMethod()) {

            /**
             * 在构造Request的时候如果没有指明请求方式
             * DEPRECATED_GET_OR_POST为其默认值
             * 通过postBody是否为Null来区别POST和GET
             * 这两种最常用的请求方式
             */
            case Method.DEPRECATED_GET_OR_POST:
                // This is the deprecated way that needs to be handled for backwards compatibility.
                // If the request's post body is null, then the assumption is that the request is
                // GET.  Otherwise, it is assumed that the request is a POST.
                /**
                 * 不要用这个参数了= =，因为不能处理什么DELETE之类的
                 * 该方法已经过时了。
                 */
                byte[] postBody = request.getPostBody();
                if (postBody != null) {
                    // Prepare output. There is no need to set Content-Length explicitly,
                    // since this is handled by HttpURLConnection using the size of the prepared
                    // output stream.

                    /**
                     * 设置是否输出
                     */
                    connection.setDoOutput(true);

                    /**
                     * 给connection设置请求的方式
                     */
                    connection.setRequestMethod("POST");

                    /**
                     * 设置http请求头中的content-type参数
                     */
                    connection.addRequestProperty(HEADER_CONTENT_TYPE,
                            request.getPostBodyContentType());
                    DataOutputStream out = new DataOutputStream(connection.getOutputStream());
                    out.write(0);
                    out.close();
                }
                break;
            case Method.GET:
                // Not necessary to set the request method because connection defaults to GET but
                // being explicit here.
                connection.setRequestMethod("GET");
                break;
            case Method.DELETE:
                connection.setRequestMethod("DELETE");
                break;
            case Method.POST:
                connection.setRequestMethod("POST");
                addBodyIfExists(connection, request);
                break;
            case Method.PUT:
                connection.setRequestMethod("PUT");
                addBodyIfExists(connection, request);
                break;
            case Method.HEAD:
                connection.setRequestMethod("HEAD");
                break;
            case Method.OPTIONS:
                connection.setRequestMethod("OPTIONS");
                break;
            case Method.TRACE:
                connection.setRequestMethod("TRACE");
                break;
            case Method.PATCH:
                connection.setRequestMethod("PATCH");
                addBodyIfExists(connection, request);
                break;
            default:
                throw new IllegalStateException("Unknown method type.");
        }
    }

    /**
     * 如果存在请求参数的话
     * 获取到connection的输出流对象
     * 并创建一个DataOutputStream对象
     * 用于向服务器写入需要传递的参数
     */
    private static void addBodyIfExists(HttpURLConnection connection, Request<?> request)
            throws IOException, AuthFailureError {
        byte[] body = request.getBody();
        if (body != null) {
            connection.setDoOutput(true);
            connection.addRequestProperty(HEADER_CONTENT_TYPE, request.getBodyContentType());
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            out.write(body);
            out.close();
        }
    }
}
