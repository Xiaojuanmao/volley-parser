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

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An HttpStack that performs request over an {@link HttpClient}.
 * 在sdk小于2.3的时候
 * 选用HttpClient来实现网络请求
 */
public class HttpClientStack implements HttpStack {

    /**
     * 官方文档
     * Interface for an HTTP client. 
     * HTTP clients encapsulate a smorgasbord of objects required to execute HTTP requests while handling cookies, 
     * authentication, connection management, and other features. 
     * HTTP Clients将发送http请求需要需要做出的信息
     * Thread safety of HTTP clients depends on the implementation and configuration of the specific client. 
     * 
     */
    protected final HttpClient mClient;

    //Http请求头里面的固定格式
    private final static String HEADER_CONTENT_TYPE = "Content-Type";

    public HttpClientStack(HttpClient client) {
        mClient = client;
    }

    //在组合出一个请求的过程中，向请求体中添加Header的方法，Header是以键值对的形式存在的
    private static void addHeaders(HttpUriRequest httpRequest, Map<String, String> headers) {
        for (String key : headers.keySet()) {
            httpRequest.setHeader(key, headers.get(key));
        }
    }

    /**
     * NameValuePair 官方文档
     * A simple class encapsulating an attribute/value pair. 
     * 
     * 该函数将传入的Map里面存放的值进一步转化成由NameValuePair子类组成的数组中
     */
    @SuppressWarnings("unused")
    private static List<NameValuePair> getPostParameterPairs(Map<String, String> postParams) {
        List<NameValuePair> result = new ArrayList<NameValuePair>(postParams.size());
        for (String key : postParams.keySet()) {
            result.add(new BasicNameValuePair(key, postParams.get(key)));
        }
        return result;
    }

    
     //该函数也就是实现HttpStack接口需要实现的方法，用来执行Request的方法
    @Override
    public HttpResponse performRequest(Request<?> request, Map<String, String> additionalHeaders)
            throws IOException, AuthFailureError {

        /**
         * 传入请求体和额外需要添加入的头部
         * 生成并返回一个HttpUriRequest
         */
        HttpUriRequest httpRequest = createHttpRequest(request, additionalHeaders);

        /**
         * 这个方法在前面实现了，将这些传入的键值对全部添加到httpRequest里面去
         */
        addHeaders(httpRequest, additionalHeaders);
        addHeaders(httpRequest, request.getHeaders());

        /**
         * 一个protected方法，留给子类可以实现的方法(本类中并没有什么东西)，在这里会调用。
         */
        onPrepareRequest(httpRequest);

        /**
         * HttpParams 官方文档
         * Represents a collection of HTTP protocol and framework parameters. 
         * 说白了就是Http协议和框架的相关参数
         */
        HttpParams httpParams = httpRequest.getParams();
        int timeoutMs = request.getTimeoutMs();

        /**
         * HttpConnectionParams 官方文档
         * An adaptor for accessing connection parameters in HttpParams. 
         * 一个用来访问请求参数的适配器
         * Note that the implements relation to CoreConnectionPNames is for compatibility with existing application code only. 
         * References to the parameter names should use the interface, not this class. 
         */

        /* Sets the timeout until a connection is established.
         * 该方法用来设置时间限制，
         * A value of zero means the timeout is not used. The default value is zero. 
         * 如果timeout设置为0则表示该限时没有启用，默认为0
         */
        HttpConnectionParams.setConnectionTimeout(httpParams, 5000);

        /**
         * Sets the default socket timeout (SO_TIMEOUT) in milliseconds which is the timeout for waiting for data. 
         * 设置请求发出后等待网络响应并返回数据的限时
         * A timeout value of zero is interpreted as an infinite timeout. 
         * 如果timeout值为0则意味着无限等待，没有等待限时，同时也是默认的值
         * This value is used when no socket timeout is set in the method parameters. 
         */
        HttpConnectionParams.setSoTimeout(httpParams, timeoutMs);

        /**
         * 执行了HttpClient类中的execute方法
         * 方法描述为 Executes a request using the default context.
         * 方法结束后将返回一个HttpResponse，也就是请求的结果类
         */ 
        return mClient.execute(httpRequest);
    }

    /**
     * Creates the appropriate subclass of HttpUriRequest for passed in request.
     * 根据传入的Request种类不同
     * 创建不同的HttpUriRequest子类(也就是下面的HttpGet等等)
     * 下面做的工作和HurlStack.java里面做的工作差不多
     * 设置header,以及是否需要传入请求携带的参数
     * 只是本类中用HttpClient实现，后者用的是HttpURLConnection实现的
     */
    @SuppressWarnings("deprecation")
    /* protected */ static HttpUriRequest createHttpRequest(Request<?> request,
            Map<String, String> additionalHeaders) throws AuthFailureError {
        switch (request.getMethod()) {
            case Method.DEPRECATED_GET_OR_POST: {
                // This is the deprecated way that needs to be handled for backwards compatibility.
                // If the request's post body is null, then the assumption is that the request is
                // GET.  Otherwise, it is assumed that the request is a POST.
                byte[] postBody = request.getPostBody();
                if (postBody != null) {
                    HttpPost postRequest = new HttpPost(request.getUrl());
                    postRequest.addHeader(HEADER_CONTENT_TYPE, request.getPostBodyContentType());
                    HttpEntity entity;
                    entity = new ByteArrayEntity(postBody);
                    postRequest.setEntity(entity);
                    return postRequest;
                } else {
                    return new HttpGet(request.getUrl());
                }
            }
            case Method.GET:
                return new HttpGet(request.getUrl());
            case Method.DELETE:
                return new HttpDelete(request.getUrl());
            case Method.POST: {
                HttpPost postRequest = new HttpPost(request.getUrl());
                postRequest.addHeader(HEADER_CONTENT_TYPE, request.getBodyContentType());
                setEntityIfNonEmptyBody(postRequest, request);
                return postRequest;
            }
            case Method.PUT: {
                HttpPut putRequest = new HttpPut(request.getUrl());
                putRequest.addHeader(HEADER_CONTENT_TYPE, request.getBodyContentType());
                setEntityIfNonEmptyBody(putRequest, request);
                return putRequest;
            }
            case Method.HEAD:
                return new HttpHead(request.getUrl());
            case Method.OPTIONS:
                return new HttpOptions(request.getUrl());
            case Method.TRACE:
                return new HttpTrace(request.getUrl());
            case Method.PATCH: {
                HttpPatch patchRequest = new HttpPatch(request.getUrl());
                patchRequest.addHeader(HEADER_CONTENT_TYPE, request.getBodyContentType());
                setEntityIfNonEmptyBody(patchRequest, request);
                return patchRequest;
            }
            default:
                throw new IllegalStateException("Unknown request method.");
        }
    }

    private static void setEntityIfNonEmptyBody(HttpEntityEnclosingRequestBase httpRequest,
            Request<?> request) throws AuthFailureError {
        byte[] body = request.getBody();
        if (body != null) {
            HttpEntity entity = new ByteArrayEntity(body);
            httpRequest.setEntity(entity);
        }
    }

    /**
     * Called before the request is executed using the underlying HttpClient.
     *
     * <p>Overwrite in subclasses to augment the request.</p>
     */
    protected void onPrepareRequest(HttpUriRequest request) throws IOException {
        // Nothing.
    }

    /**
     * The HttpPatch class does not exist in the Android framework, so this has been defined here.
     * = =在HttpUriClient的子类中没有支持Patch的请求方法
     * 在这里volley实现了= =
     */
    public static final class HttpPatch extends HttpEntityEnclosingRequestBase {

        public final static String METHOD_NAME = "PATCH";

        public HttpPatch() {
            super();
        }

        public HttpPatch(final URI uri) {
            super();
            setURI(uri);
        }

        /**
         * @throws IllegalArgumentException if the uri is invalid.
         */
        public HttpPatch(final String uri) {
            super();
            setURI(URI.create(uri));
        }

        @Override
        public String getMethod() {
            return METHOD_NAME;
        }

    }
}
