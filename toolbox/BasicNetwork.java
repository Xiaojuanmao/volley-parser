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

import android.os.SystemClock;

import com.android.volley.AuthFailureError;
import com.android.volley.Cache;
import com.android.volley.Cache.Entry;
import com.android.volley.Network;
import com.android.volley.NetworkError;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.RetryPolicy;
import com.android.volley.ServerError;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.cookie.DateUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A network performing Volley requests over an {@link HttpStack}.
 * 一个用来执行Volley中request的类
 * 在HttpStack的基础之上
 * 因为主要还是调用HttpStack接口的performRequest
 * 在这个里面的performRequest主要还是做一些整理工作
 * 比如将｛@link HttpStack#performRequest()｝方法返回的HttpResponse
 * 解析成Volley自己实现的NetworkResponse.java
 */

public class BasicNetwork implements Network {

    //是否允许打lo的boolean常变量
    protected static final boolean DEBUG = VolleyLog.DEBUG;

    /**
     * 这是一个阀值，用来判断一个request是否请求响应过慢了= =
     * 在后面的作用就是，如果响应时间超过了这个阀值
     * 打出log说明这个request有些慢，为了更好的反应request当前状态
     */
    private static int SLOW_REQUEST_THRESHOLD_MS = 3000;

    /**
     * 默认ByteArrayPool的大小
     * 现在只需要知道ByteArrayPool.java是Volley用来从输入流中读取数据并将其转换成字节数组的工具即可
     * 在这篇博客后面会介绍，表担心~= =
     */
    private static int DEFAULT_POOL_SIZE = 4096;

    /**
     * 网络请求的真正接口
     * 为什么这么说咧，BasicNetwork里面的performRequest()函数
     * 调用了HttpStack里的performRequest()，真正的网络请求还是通过HttpStack里面的方法实现的
     * 在Volley中实现了HttpStack接口的类有两个 HurlStack.java和HttpClientStack.java
     * 针对了不同Android系统版本，用不同的方法实现了请求。
     */
    protected final HttpStack mHttpStack;

    //表急，后面会介绍到的，现在知道是一个用于数据转换的工具类就好了
    protected final ByteArrayPool mPool;

    /**
     * @param httpStack HTTP stack to be used
     * 传入的HttpStack实现类引用
     * 整个网络请求的较核心部分就在HttpStack实现类上面咯
     */
    public BasicNetwork(HttpStack httpStack) {
        // If a pool isn't passed in, then build a small default pool that will give us a lot of
        // benefit and not use too much memory.
        this(httpStack, new ByteArrayPool(DEFAULT_POOL_SIZE));
    }

    /**
     * @param httpStack HTTP stack to be used
     * @param pool a buffer pool that improves GC performance in copy operations
     * Volley接口分离的很明显，而且在构造函数里面也提供了很多种
     * 可以定制出适合自己的ByteArrayPool衍生类
     * 当然也可以自己来实现HttpStack的衍生类
     */
    public BasicNetwork(HttpStack httpStack, ByteArrayPool pool) {
        mHttpStack = httpStack;
        mPool = pool;
    }

    /**
     * 这个方法重写的是Network的方法
     * 在这个里面再调用HttpStack里面的performRequest方法
     */
    @Override
    public NetworkResponse performRequest(Request<?> request) throws VolleyError {

        /**
         * Returns milliseconds since boot, including time spent in sleep.
         * 为了方便计算每个request所用的时间
         * 在处理每个request之前都记下此刻unix时间戳
         */
        long requestStart = SystemClock.elapsedRealtime();

        /**
         * 进入死循环= =
         * 还没弄清楚为什么要死循环
         */
        while (true) {

            /**
             * 指向HttpResponse实例的引用
             * 是调用HttpStack方法performRequest()之后返回的结果
             */
            HttpResponse httpResponse = null;

            /**
             * 返回的HttpResponse还需要经过处理
             * 并不是返回回来就是能直接使用的数据
             * 需要通过上面的ByteArrayPool将Entity转换成byte[]
             * 这个就是指向解析后的byte[]的
             */
            byte[] responseContents = null;

            //用来存放response里面header的信息，包含了状态码等
            Map<String, String> responseHeaders = Collections.emptyMap();


            try {
                /**
                 * Gather headers.
                 * 设置header
                 * 从缓存中收集上次相同request的信息
                 */
                Map<String, String> headers = new HashMap<String, String>();

                /**
                 * 将缓存的信息加入到headers中
                 * headers会跟随request一起发送给服务器
                 * 在函数的定义处会讲解
                 */
                addCacheHeaders(headers, request.getCacheEntry());

                /**
                 * 通过调用HttpStack接口的performRequest()方法
                 * 获取服务器返回的HttpResponse
                 */
                httpResponse = mHttpStack.performRequest(request, headers);

                /**
                 * The first line of a Response message is the Status-Line, 
                 * consisting of the protocol version followed by a numeric status code and its associated textual phrase
                 * with each element separated by SP characters. 
                 * No CR or LF is allowed except in the final CRLF sequence.
                 * 请求返回的response第一行就是包含了状态码的一行
                 */
                StatusLine statusLine = httpResponse.getStatusLine();
                int statusCode = statusLine.getStatusCode();

                /**
                 * 将头部解析成键值对的形式再返回
                 */
                responseHeaders = convertHeaders(httpResponse.getAllHeaders());

                /**
                 * Handle cache validation.
                 * 处理缓存信息
                 * 如果返回的状态码是304(HttpStatus.SC_NOT_MODIFIED)
                 * 则进行如下的处理
                 */
                if (statusCode == HttpStatus.SC_NOT_MODIFIED) {

                    /**
                     * 如果缓存为空的话
                     * 那就说明该请求的返回的response的body就是null
                     * 直接构造一个NetworkResponse返回
                     */
                    Entry entry = request.getCacheEntry();
                    if (entry == null) {
                        return new NetworkResponse(HttpStatus.SC_NOT_MODIFIED, null,
                                responseHeaders, true,
                                SystemClock.elapsedRealtime() - requestStart);
                    }

                    // A HTTP 304 response does not have all header fields. We
                    // have to use the header fields from the cache entry plus
                    // the new ones from the response.
                    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
                    /**
                     * 一个返回码为304的HttpResponse的header缺少一些信息
                     * 需要我们将cache中的一些header信息加入到其中
                     * 这样组成一个完整的NetworkResponse返回
                     */
                    entry.responseHeaders.putAll(responseHeaders);
                    return new NetworkResponse(HttpStatus.SC_NOT_MODIFIED, entry.data,
                            entry.responseHeaders, true,
                            SystemClock.elapsedRealtime() - requestStart);
                }
                
                /**
                 * Handle moved resources
                 * 处理了重定向的问题
                 * 并将request的mRedirectUrl设定成了新的url
                 */
                if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
                	String newUrl = responseHeaders.get("Location");
                	request.setRedirectUrl(newUrl);
                }

                /**
                 * Some responses such as 204s do not have content.  We must check.
                 * 204(无内容)服务器成功处理了请求，但没有返回任何内容。
                 * 
                 */
                if (httpResponse.getEntity() != null) {
                    /**
                     * 如果entity不为Null
                     * 将其转换成byte数组
                     * 利用之前提到过的ByteArrayPool.java类
                     */
                  responseContents = entityToBytes(httpResponse.getEntity());
                } else {
                  // Add 0 byte response as a way of honestly representing a
                  // no-content request.
                  responseContents = new byte[0];
                }

                /**
                 * if the request is slow, log it.
                 * 获取request已经占用的时间(requestLifetime)
                 * 判断是否需要打出request的超时状态
                 */
                long requestLifetime = SystemClock.elapsedRealtime() - requestStart;
                logSlowRequests(requestLifetime, request, responseContents, statusLine);

                /**
                 * 如果状态码位于200之下或者是299之上(200-299 用于表示请求成功)
                 * 则抛出IOException异常= =为什么非要抛出这个异常
                 * 在前面过滤掉了(304等情况)
                 */
                if (statusCode < 200 || statusCode > 299) {
                    throw new IOException();
                }

                /**
                 * 经过上面的层层过滤
                 * 最后留下了200~299之间的请求成功response
                 * 通过HttpResponse里面的信息构造出一个volley自己封装的NetworkResponse对象
                 */
                return new NetworkResponse(statusCode, responseContents, responseHeaders, false,
                        SystemClock.elapsedRealtime() - requestStart);

                /**
                 * 抛出了异常之后，会用attemptREtryOnException()方法来尝试retry
                 * 主要做的工作就是看是否还有retry的机会，如果有则不停通过这个死循环
                 * 进行请求，直到请求成功或者请求的机会用完为止
                 */
            } catch (SocketTimeoutException e) {
                attemptRetryOnException("socket", request, new TimeoutError());
            } catch (ConnectTimeoutException e) {·
                attemptRetryOnException("connection", request, new TimeoutError());
            } catch (MalformedURLException e) {
                throw new RuntimeException("Bad URL " + request.getUrl(), e);
            } catch (IOException e) {
                /**
                 * 状态码在0~200以及299之上的response
                 * 处理的套路
                 */
                int statusCode = 0;
                NetworkResponse networkResponse = null;
                if (httpResponse != null) {
                    statusCode = httpResponse.getStatusLine().getStatusCode();
                } else {
                    //如果状态码为0，则抛出NoConnectionError
                    throw new NoConnectionError(e);
                }
                /**
                 * 如果有重定向的情况发生
                 * 用log打出
                 */
                if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || 
                		statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
                	VolleyLog.e("Request at %s has been redirected to %s", request.getOriginUrl(), request.getUrl());
                } else {
                	VolleyLog.e("Unexpected response code %d for %s", statusCode, request.getUrl());
                }

                /**
                 * 如果返回的content内容不为Null
                 * 则构造出一个NetworkResponse
                 * 否则抛出NetworkError
                 */
                if (responseContents != null) {

                    networkResponse = new NetworkResponse(statusCode, responseContents,
                            responseHeaders, false, SystemClock.elapsedRealtime() - requestStart);

                    /**
                     * 抛出了异常之后，会用attemptREtryOnException()方法来尝试retry
                     * 主要做的工作就是看是否还有retry的机会，如果有则不停通过这个死循环
                     * 进行请求，直到请求成功或者请求的机会用完为止
                     */
                    if (statusCode == HttpStatus.SC_UNAUTHORIZED ||
                            statusCode == HttpStatus.SC_FORBIDDEN) {
                        attemptRetryOnException("auth",
                                request, new AuthFailureError(networkResponse));
                    } else if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || 
                    			statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
                        attemptRetryOnException("redirect",
                                request, new AuthFailureError(networkResponse));
                    } else {
                        // TODO: Only throw ServerError for 5xx status codes.
                        throw new ServerError(networkResponse);
                    }
                } else {
                    throw new NetworkError(networkResponse);
                }
            }
        }
    }

    /**
     * Logs requests that took over SLOW_REQUEST_THRESHOLD_MS to complete.
     * 如果request用时超出了预先设定的阀值
     * 则打出log用于debug时候的提示
     */
    private void logSlowRequests(long requestLifetime, Request<?> request,
            byte[] responseContents, StatusLine statusLine) {
        if (DEBUG || requestLifetime > SLOW_REQUEST_THRESHOLD_MS) {
            VolleyLog.d("HTTP response for request=<%s> [lifetime=%d], [size=%s], " +
                    "[rc=%d], [retryCount=%s]", request, requestLifetime,
                    responseContents != null ? responseContents.length : "null",
                    statusLine.getStatusCode(), request.getRetryPolicy().getCurrentRetryCount());
        }
    }

    /**
     * Attempts to prepare the request for a retry. If there are no more attempts remaining in the
     * request's retry policy, a timeout exception is thrown.
     * 每次尝试都会使retry机会减少1，如果机会没有了，则抛出请求超时的exception
     *
     * @param request The request to use.
     */
    private static void attemptRetryOnException(String logPrefix, Request<?> request,
            VolleyError exception) throws VolleyError {
        RetryPolicy retryPolicy = request.getRetryPolicy();
        int oldTimeout = request.getTimeoutMs();

        try {
            retryPolicy.retry(exception);
        } catch (VolleyError e) {
            request.addMarker(
                    String.format("%s-timeout-giveup [timeout=%s]", logPrefix, oldTimeout));
            throw e;
        }
        request.addMarker(String.format("%s-retry [timeout=%s]", logPrefix, oldTimeout));
    }



    /**
     * 添加上缓存的header
     * 如果有之前的缓存的信息
     * 将里面的信息取出放入header中
     * 
     * 这里面涉及到了一个条件请求
     * 如果有缓存的话，header上面会带上一个If-Modified-Since关键字
     * 服务器会先比较信息modified的时间，如果服务端的数据没有发生变化就返回304(也就是上面的 HttpStatus.SC_NOT_MODIFIED)
     * 如果服务器的数据发生了变化，则会返回状态码200以及请求需要的数据(意思就是本地的数据需要刷新了，缓存不管用了)
     */
    private void addCacheHeaders(Map<String, String> headers, Cache.Entry entry) {
        // If there's no cache entry, we're done.
        if (entry == null) {
            return;
        }

        if (entry.etag != null) {
            headers.put("If-None-Match", entry.etag);
        }

        if (entry.lastModified > 0) {
            Date refTime = new Date(entry.lastModified);
            headers.put("If-Modified-Since", DateUtils.formatDate(refTime));
        }
    }


    protected void logError(String what, String url, long start) {
        long now = SystemClock.elapsedRealtime();
        VolleyLog.v("HTTP ERROR(%s) %d ms to fetch %s", what, (now - start), url);
    }

    /** 
     * Reads the contents of HttpEntity into a byte[].
     * 从HttpEntity中读取数据，并通过ByteArrayPool将其转换成byte[]
     * 暂时不用管太多= =，等后面介绍到ByteArrayPool.java的时候就会明白
     */
    private byte[] entityToBytes(HttpEntity entity) throws IOException, ServerError {

        PoolingByteArrayOutputStream bytes =
                new PoolingByteArrayOutputStream(mPool, (int) entity.getContentLength());
        
        byte[] buffer = null;
        
        try {
            InputStream in = entity.getContent();
            if (in == null) {
                throw new ServerError();
            }

            /**
             * 获取一个大小为1024的缓冲区
             */
            buffer = mPool.getBuf(1024);

            int count;
            //将content的内容通过流每次最大读出1024个byte, 全部读出并写入bytes
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } finally {
            try {
                // Close the InputStream and release the resources by "consuming the content".
                entity.consumeContent();
            } catch (IOException e) {
                // This can happen if there was an exception above that left the entity in
                // an invalid state.
                VolleyLog.v("Error occured when calling consumingContent");
            }
            /**
             * 在所有工作完成之后
             * 需要将从mPool中拿出的buffer缓冲区回收
             */
            mPool.d(buffer);
            bytes.close();
        }
    }

    /**
     * Converts Headers[] to Map<String, String>.
     * 将返回的response里面的header[]
     * 全部转换成Map里面的键值对形式
     */
    protected static Map<String, String> convertHeaders(Header[] headers) {
        Map<String, String> result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < headers.length; i++) {
            result.put(headers[i].getName(), headers[i].getValue());
        }
        return result;
    }
}
