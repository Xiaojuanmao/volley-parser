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

package com.android.volley;

import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import com.android.volley.VolleyLog.MarkerLog;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Map;

/**
 * Base class for all network requests.
 * 在volley中涉及到的所有request的基础类
 *
 * @param <T> The type of parsed response this request expects.
 * 泛型类T是请求端希望服务器能返回的数据类型
 *
 * 关于其实现的一个Comparable接口，从字面上来看就是"可比较的"
 * 官方的解释如下：
 * This interface should be implemented by all classes that wish to define 
 * a natural order of their instances. sort(List) and java.util.Arrays#sort 
 * can then be used to automatically sort lists of classes that implement this interface.
 * 意思就是说如果你希望你的类在一个list中能够使用sort等函数自动排序的话，就实现这个接口吧= =
 * (需要重写里面的方法compareTo(), 里面是比较了两个request的优先级)
 */
public abstract class Request<T> implements Comparable<Request<T>> {

    /**
     * Default encoding for POST or PUT parameters. See {@link #getParamsEncoding()}.
     * POST或者是PUT请求参数的默认编码格式 "UTF-8"
     */
    private static final String DEFAULT_PARAMS_ENCODING = "UTF-8";

    /**
     * Supported request methods.
     * 支持的请求方式，有各种,常用的GET, POST, PUT应该是比较熟悉
     */
    public interface Method {
        int DEPRECATED_GET_OR_POST = -1;
        int GET = 0;
        int POST = 1;
        int PUT = 2;
        int DELETE = 3;
        int HEAD = 4;
        int OPTIONS = 5;
        int TRACE = 6;
        int PATCH = 7;
    }

    /**
     * An event log tracing the lifetime of this request; for debugging. 
     * 为了debug方便，volley弄了一套VolleyLog
     * 在一个request整个生命周期内不停的打出log
     * 都可以方便从log监控该request现在的情况
     */
    private final MarkerLog mEventLog = MarkerLog.ENABLED ? new MarkerLog() : null;

    /**
     * Request method of this request.  Currently supports GET, POST, PUT, DELETE, HEAD, OPTIONS,
     * TRACE, and PATCH.
     * 当前request涉及到的请求方式
     * 目前所支持的有GET, POST, PUT等
     */
    private final int mMethod;

    /** 
     * URL of this request.
     * 原始Url
     */
    private final String mUrl;
    
    /**
     * The redirect url to use for 3xx http responses 
     * request重定向之后的url
     */
    private String mRedirectUrl;

    /** The unique identifier of the request 
     *  从后面可以看出在构造request的时候
     *  mIdentifier是通过createIdentifier()函数
     *  由传入的url和请求的method以及当前系统时间还有一个计数器counter构造出的
     *  独一无二的身份标识
     */
    private String mIdentifier;

    /** 
     * Default tag for {@link TrafficStats}. 
     * 
     */
    private final int mDefaultTrafficStatsTag;

    /** Listener interface for errors. */
    private final Response.ErrorListener mErrorListener;

    /** Sequence number of this request, used to enforce FIFO ordering. */
    private Integer mSequence;

    /** The request queue this request is associated with. */
    private RequestQueue mRequestQueue;

    /** Whether or not responses to this request should be cached. */
    private boolean mShouldCache = true;

    /** Whether or not this request has been canceled. */
    private boolean mCanceled = false;

    /** Whether or not a response has been delivered for this request yet. */
    private boolean mResponseDelivered = false;

    // A cheap variant of request tracing used to dump slow requests.
    private long mRequestBirthTime = 0;

    /** 
     * Threshold at which we should log the request (even when debug logging is not enabled). 
     * 用来判定是否打出将一个request打出slow_request的log的时间阀值
     * 如果request响应时间超过了这个阀值，则会打出log，说明一下情况
     */
    private static final long SLOW_REQUEST_THRESHOLD_MS = 3000;

    /**
     * The retry policy for this request. 
     * 在前面已经介绍到了，RetryPolicy.java及其默认实现类
     * 是用来处理request重新发送的一种策略，也就是重试方针
     * 里面记录着重试的最大次数以及当前重试了几次等
     */
    private RetryPolicy mRetryPolicy;

    /**
     * When a request can be retrieved from cache but must be refreshed from
     * the network, the cache entry will be stored here so that in the event of
     * a "Not Modified" response, we can be sure it hasn't been evicted from cache.
     *
     * 当一个请求的结果有缓存但是需要从服务器刷新一下的时候
     * 缓存的入口，在向服务器发送条件请求时，服务器返回304之后
     * 就可以从这个缓存的入口找到该请求在本地对应的缓存数据了。= =直接拿来用咯
     */
    private Cache.Entry mCacheEntry = null;

    /** An opaque token tagging this request; used for bulk cancellation. 
     *  一个关于该request的不公开透明的token，用于批量取消
     * 在RequestQueue.java中会用到这个mTag
     * 用mTag可以取消request
     */
    private Object mTag;

    /**
     * Creates a new request with the given URL and error listener.  Note that
     * the normal response listener is not provided here as delivery of responses
     * is provided by subclasses, who have a better idea of how to deliver an
     * already-parsed response.
     *
     * 根据给定的url和errorListener创建一个新的request
     * 需要注意的是这里并没有涉及到responseListener方面的设置
     * 因为将其放在request的子类去设置能更好的去传递一个已经解析好了的response
     *
     * 这个方法已经不推荐使用了，推荐使用下面的一个构造方法，因为这个方法存在默认的method
     * 没有很大的自由度去自定义request
     *
     * @deprecated Use {@link #Request(int, String, com.android.volley.Response.ErrorListener)}.
     */
    @Deprecated
    public Request(String url, Response.ErrorListener listener) {
        this(Method.DEPRECATED_GET_OR_POST, url, listener);
    }

    /**
     * Creates a new request with the given method (one of the values from {@link Method}),
     * URL, and error listener.  Note that the normal response listener is not provided here as
     * delivery of responses is provided by subclasses, who have a better idea of how to deliver
     * an already-parsed response.
     * 
     * setRetryPolicy()该方法设置了request所谓的“重试策略”。
     * 跳转到DefaultRetryPolicy.java(系列博客的第四篇---Volley框架解析(四))
     */
    public Request(int method, String url, Response.ErrorListener listener) {
        mMethod = method;
        mUrl = url;
        mIdentifier = createIdentifier(method, url);
        mErrorListener = listener;
        setRetryPolicy(new DefaultRetryPolicy());

        mDefaultTrafficStatsTag = findDefaultTrafficStatsTag(url);
    }

    /**
     * Return the method for this request.  Can be one of the values in {@link Method}.
     */
    public int getMethod() {
        return mMethod;
    }

    /**
     * Set a tag on this request. Can be used to cancel all requests with this
     * tag by {@link RequestQueue#cancelAll(Object)}.
     *
     * 为了方便从网络请求队列里面取消request，可以通过打tag的方式
     * @return This Request object to allow for chaining.
     */
    public Request<?> setTag(Object tag) {
        mTag = tag;
        return this;
    }

    /**
     * Returns this request's tag.
     * @see Request#setTag(Object)
     */
    public Object getTag() {
        return mTag;
    }

    /**
     * @return this request's {@link com.android.volley.Response.ErrorListener}.
     */
    public Response.ErrorListener getErrorListener() {
        return mErrorListener;
    }

    /**
     * @return A tag for use with {@link TrafficStats#setThreadStatsTag(int)}
     */
    public int getTrafficStatsTag() {
        return mDefaultTrafficStatsTag;
    }

    /**
     * @return The hashcode of the URL's host component, or 0 if there is none.
     * 返回了request的url中的host的hashcode，暂时不知道有什么用。
     */
    private static int findDefaultTrafficStatsTag(String url) {
        if (!TextUtils.isEmpty(url)) {
            Uri uri = Uri.parse(url);
            if (uri != null) {
                String host = uri.getHost();
                if (host != null) {
                    return host.hashCode();
                }
            }
        }
        return 0;
    }

    /**
     * Sets the retry policy for this request.
     * 给request设置重试策略
     * @return This Request object to allow for chaining.
     */
    public Request<?> setRetryPolicy(RetryPolicy retryPolicy) {
        mRetryPolicy = retryPolicy;
        return this;
    }

    /**
     * Adds an event to this request's event log; for debugging.
     */
    public void addMarker(String tag) {
        if (MarkerLog.ENABLED) {
            mEventLog.add(tag, Thread.currentThread().getId());
        } else if (mRequestBirthTime == 0) {
            mRequestBirthTime = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Notifies the request queue that this request has finished (successfully or with error).
     * 该函数用来告诉request队列，当前的request已经完成了(包括成功和失败)
     * <p>Also dumps all events from this request's event log; for debugging.</p>
     */
    void finish(final String tag) {

        /**
         * 告诉RequestQueue，这个tag对应的request已经结束了
         * ReuqestQueue会将这个request移出队列
         * 并将具有相同cacheKey的等待中reuqest全部移除
         */
        if (mRequestQueue != null) {
            mRequestQueue.finish(this);
        }

        /**
         * 如果允许打出log
         * 则log提示这个request已经结束了
         */
        if (MarkerLog.ENABLED) {
            final long threadId = Thread.currentThread().getId();
            if (Looper.myLooper() != Looper.getMainLooper()) {
                // If we finish marking off of the main thread, we need to
                // actually do it on the main thread to ensure correct ordering.
                Handler mainThread = new Handler(Looper.getMainLooper());
                mainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        mEventLog.add(tag, threadId);
                        mEventLog.finish(this.toString());
                    }
                });
                return;
            }

            mEventLog.add(tag, threadId);
            mEventLog.finish(this.toString());
        } else {
            long requestTime = SystemClock.elapsedRealtime() - mRequestBirthTime;
            if (requestTime >= SLOW_REQUEST_THRESHOLD_MS) {
                VolleyLog.d("%d ms: %s", requestTime, this.toString());
            }
        }
    }

    /**
     * Associates this request with the given queue. The request queue will be notified when this
     * request has finished.
     * 
     * 将和request相关的那个RequestQueue与request关联起来
     * 持有一个对象的引用
     * 在request结束的时候好通知RequestQueue
     * 
     * @return This Request object to allow for chaining.
     */
    public Request<?> setRequestQueue(RequestQueue requestQueue) {
        mRequestQueue = requestQueue;
        return this;
    }

    /**
     * Sets the sequence number of this request.  Used by {@link RequestQueue}.
     * 在RequestQueue中调用，request加入到RequestQueue的时候
     * 需要开始排队等待处理
     * 这个函数的作用就是发号码牌给每个request(排队专用，想的还比较周到= =)
     * @return This Request object to allow for chaining.
     */
    public final Request<?> setSequence(int sequence) {
        mSequence = sequence;
        return this;
    }

    /**
     * Returns the sequence number of this request.
     */
    public final int getSequence() {
        if (mSequence == null) {
            throw new IllegalStateException("getSequence called before setSequence");
        }
        return mSequence;
    }

    /**
     * Returns the URL of this request.
     * 返回真实访问的url,如果有重定向出现
     * 则真实url是重定向后的url
     * 否则是原始的url
     */
    public String getUrl() {
        return (mRedirectUrl != null) ? mRedirectUrl : mUrl;
    }
    
    /**
     * Returns the URL of the request before any redirects have occurred.
     * 返回最原始的url,在任何重定向发生之前
     */
    public String getOriginUrl() {
    	return mUrl;
    }

    /**
     * Returns the identifier of the request.
     */
    public String getIdentifier() {
        return mIdentifier;
    }
    
    /**
     * Sets the redirect url to handle 3xx http responses.
     * 发生重定向之后可以通过该函数来设置重定向后的url
     */
    public void setRedirectUrl(String redirectUrl) {
    	mRedirectUrl = redirectUrl;
    }

    /**
     * Returns the cache key for this request.  
     * By default, this is the URL.
     * 默认使用url来作为cacheKey
     */
    public String getCacheKey() {
        return getUrl();
    }

    /**
     * Annotates this request with an entry retrieved for it from cache.
     * Used for cache coherency support.
     * 
     * @return This Request object to allow for chaining.
     */
    public Request<?> setCacheEntry(Cache.Entry entry) {
        mCacheEntry = entry;
        return this;
    }

    /**
     * Returns the annotated cache entry, or null if there isn't one.
     */
    public Cache.Entry getCacheEntry() {
        return mCacheEntry;
    }

    /**
     * Mark this request as canceled.  No callback will be delivered.
     */
    public void cancel() {
        mCanceled = true;
    }

    /**
     * Returns true if this request has been canceled.
     */
    public boolean isCanceled() {
        return mCanceled;
    }

    /**
     * Returns a list of extra HTTP headers to go along with this request. Can
     * throw {@link AuthFailureError} as authentication may be required to
     * provide these values.
     * 返回在Request中的HTTPheader，这个里面存放了一些关于Request的基本信息
     * 例如请求方式，cookie等东西
     * @throws AuthFailureError In the event of auth failure
     */
    public Map<String, String> getHeaders() throws AuthFailureError {
        return Collections.emptyMap();
    }

    /**
     * Returns a Map of POST parameters to be used for this request, or null if
     * a simple GET should be used.  Can throw {@link AuthFailureError} as
     * authentication may be required to provide these values.
     * 返回request中用于POST请求的一些参数
     * 这些参数以键值对的形式存在，如果是GET方法，则传回Null
     *　
     * <p>Note that only one of getPostParams() and getPostBody() can return a non-null
     * value.</p>
     * @throws AuthFailureError In the event of auth failure
     *
     * @deprecated Use {@link #getParams()} instead.
     */
    @Deprecated
    protected Map<String, String> getPostParams() throws AuthFailureError {
        return getParams();
    }

    /**
     * Returns which encoding should be used when converting POST parameters returned by
     * {@link #getPostParams()} into a raw POST body.
     * 
     * 
     * <p>This controls both encodings:
     * <ol>
     *     <li>The string encoding used when converting parameter names and values into bytes prior
     *         to URL encoding them.</li>
     *     <li>The string encoding used when converting the URL encoded parameters into a raw
     *         byte array.</li>
     * </ol>
     *
     * @deprecated Use {@link #getParamsEncoding()} instead.
     */
    @Deprecated
    protected String getPostParamsEncoding() {
        return getParamsEncoding();
    }

    /**
     * @deprecated Use {@link #getBodyContentType()} instead.
     * 返回请求体的内容种类
     * 包括application/json等内容
     */
    @Deprecated
    public String getPostBodyContentType() {
        return getBodyContentType();
    }

    /**
     * Returns the raw POST body to be sent.
     * 该函数被建议不要使用了，用getBody代替
     * 
     * @throws AuthFailureError In the event of auth failure
     *
     * @deprecated Use {@link #getBody()} instead.
     */
    @Deprecated
    public byte[] getPostBody() throws AuthFailureError {
        // Note: For compatibility with legacy clients of volley, this implementation must remain
        // here instead of simply calling the getBody() function because this function must
        // call getPostParams() and getPostParamsEncoding() since legacy clients would have
        // overridden these two member functions for POST requests.
        Map<String, String> postParams = getPostParams();
        if (postParams != null && postParams.size() > 0) {
            return encodeParameters(postParams, getPostParamsEncoding());
        }
        return null;
    }

    /**
     * Returns a Map of parameters to be used for a POST or PUT request.  Can throw
     * {@link AuthFailureError} as authentication may be required to provide these values.
     *
     * <p>Note that you can directly override {@link #getBody()} for custom data.</p>
     *
     * @throws AuthFailureError in the event of auth failure
     */
    protected Map<String, String> getParams() throws AuthFailureError {
        return null;
    }

    /**
     * Returns which encoding should be used when converting POST or PUT parameters returned by
     * {@link #getParams()} into a raw POST or PUT body.
     *
     * <p>This controls both encodings:
     * <ol>
     *     <li>The string encoding used when converting parameter names and values into bytes prior
     *         to URL encoding them.</li>
     *     <li>The string encoding used when converting the URL encoded parameters into a raw
     *         byte array.</li>
     * </ol>
     */
    protected String getParamsEncoding() {
        return DEFAULT_PARAMS_ENCODING;
    }

    /**
     * Returns the content type of the POST or PUT body.
     * 返回请求体的内容种类
     */
    public String getBodyContentType() {
        return "application/x-www-form-urlencoded; charset=" + getParamsEncoding();
    }

    /**
     * Returns the raw POST or PUT body to be sent.
     * 返回将要发送的request的POST主体
     * 
     * <p>By default, the body consists of the request parameters in
     * application/x-www-form-urlencoded format. When overriding this method, consider overriding
     * {@link #getBodyContentType()} as well to match the new body format.
     *
     * @throws AuthFailureError in the event of auth failure
     */
    public byte[] getBody() throws AuthFailureError {
        Map<String, String> params = getParams();
        if (params != null && params.size() > 0) {
            return encodeParameters(params, getParamsEncoding());
        }
        return null;
    }

    /**
     * Converts <code>params</code> into an application/x-www-form-urlencoded encoded string.
     * 将请求里面包含的参数转码
     */
    private byte[] encodeParameters(Map<String, String> params, String paramsEncoding) {
        StringBuilder encodedParams = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                encodedParams.append(URLEncoder.encode(entry.getKey(), paramsEncoding));
                encodedParams.append('=');
                encodedParams.append(URLEncoder.encode(entry.getValue(), paramsEncoding));
                encodedParams.append('&');
            }
            return encodedParams.toString().getBytes(paramsEncoding);
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("Encoding not supported: " + paramsEncoding, uee);
        }
    }

    /**
     * Set whether or not responses to this request should be cached.
     *
     * @return This Request object to allow for chaining.
     */
    public final Request<?> setShouldCache(boolean shouldCache) {
        mShouldCache = shouldCache;
        return this;
    }

    /**
     * Returns true if responses to this request should be cached.
     */
    public final boolean shouldCache() {
        return mShouldCache;
    }

    /**
     * Priority values.  Requests will be processed from higher priorities to
     * lower priorities, in FIFO order.
     * request将按照优先级从高到低，先进先出的顺序被处理
     */
    public enum Priority {
        LOW,
        NORMAL,
        HIGH,
        IMMEDIATE
    }

    /**
     * Returns the {@link Priority} of this request; {@link Priority#NORMAL} by default.
     * 返回request的优先级
     * 默认为normal
     */
    public Priority getPriority() {
        return Priority.NORMAL;
    }

    /**
     * Returns the socket timeout in milliseconds per retry attempt. (This value can be changed
     * per retry attempt if a backoff is specified via backoffTimeout()). If there are no retry
     * attempts remaining, this will cause delivery of a {@link TimeoutError} error.
     * 返回每次超时请求时间阀值
     * 每次retry如果返回的是请求超时的结果，则timeout会逐渐变大
     * 如果
     */
    public final int getTimeoutMs() {
        return mRetryPolicy.getCurrentTimeout();
    }

    /**
     * Returns the retry policy that should be used  for this request.
     */
    public RetryPolicy getRetryPolicy() {
        return mRetryPolicy;
    }

    /**
     * Mark this request as having a response delivered on it.  This can be used
     * later in the request's lifetime for suppressing identical responses.
     */
    public void markDelivered() {
        mResponseDelivered = true;
    }

    /**
     * Returns true if this request has had a response delivered for it.
     */
    public boolean hasHadResponseDelivered() {
        return mResponseDelivered;
    }

    /**
     * Subclasses must implement this to parse the raw network response
     * and return an appropriate response type. This method will be
     * called from a worker thread.  The response will not be delivered
     * if you return null.
     * 子类必须要实现这个方法来解析network response并返回一个合适的返回类型
     * 
     * @param response Response from the network
     * @return The parsed response, or null in the case of an error
     */
    abstract protected Response<T> parseNetworkResponse(NetworkResponse response);

    /**
     * Subclasses can override this method to parse 'networkError' and return a more specific error.
     * 子类重写这个方法，来解析networkError
     * <p>The default implementation just returns the passed 'networkError'.</p>
     *
     * @param volleyError the error retrieved from the network
     * @return an NetworkError augmented with additional information
     */
    protected VolleyError parseNetworkError(VolleyError volleyError) {
        return volleyError;
    }

    /**
     * Subclasses must implement this to perform delivery of the parsed
     * response to their listeners.  The given response is guaranteed to
     * be non-null; responses that fail to parse are not delivered.
     *
     * 子类必须实现这个方法来传递一个解析好了的response
     *
     * @param response The parsed response returned by
     * {@link #parseNetworkResponse(NetworkResponse)}
     */
    abstract protected void deliverResponse(T response);

    /**
     * Delivers error message to the ErrorListener that the Request was
     * initialized with.
     *
     * @param error Error details
     */
    public void deliverError(VolleyError error) {
        if (mErrorListener != null) {
            mErrorListener.onErrorResponse(error);
        }
    }

    /**
     * Our comparator sorts from high to low priority, and secondarily by
     * sequence number to provide FIFO ordering.
     * Request类实现了Comparable类
     * 需要重写compareTo()方法
     * 来达到能够将两个request相互比较的目的
     * 这里面的比较策略是通过看两request的优先级大小
     * 高优先级的排在前面，相等的优先级就按照排队时候发放的序列号来比较
     * (在RequestQueue.java中的add()函数里会给每个加入到队列中的request发放一个sequence)
     */
    @Override
    public int compareTo(Request<T> other) {
        Priority left = this.getPriority();
        Priority right = other.getPriority();

        // High-priority requests are "lesser" so they are sorted to the front.
        // Equal priorities are sorted by sequence number to provide FIFO ordering.
        return left == right ?
                this.mSequence - other.mSequence :
                right.ordinal() - left.ordinal();
    }

    /**
     * 重写toString()方法
     * 提供在打印request的时候的一些数据
     * 也方便序列化
     */
    @Override
    public String toString() {
        String trafficStatsTag = "0x" + Integer.toHexString(getTrafficStatsTag());
        return (mCanceled ? "[X] " : "[ ] ") + getUrl() + " " + trafficStatsTag + " "
                + getPriority() + " " + mSequence;
    }

    private static long sCounter;
    /**
     *  sha1(Request:method:url:timestamp:counter)
     * 
     * @param method http method
     * @param url               http request url
     * @return sha1 hash string
     */
    private static String createIdentifier(final int method, final String url) {
        return InternalUtils.sha1Hash("Request:" + method + ":" + url +
                ":" + System.currentTimeMillis() + ":" + (sCounter++));
    }
}
