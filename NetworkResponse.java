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

import org.apache.http.HttpStatus;

import java.util.Collections;
import java.util.Map;

/**
 * Data and headers returned from {@link Network#performRequest(Request)}.
 */
public class NetworkResponse {
    /**
     * Creates a new network response.
     * 不用说了，构造函数
     *
     * @param statusCode the HTTP status code
     * HTTP请求的状态码，就是404什么之类的
     * 可以很直观反映出服务器对请求做出的相应
     *
     * @param data Response body
     * 返回的响应主体，基本上需要的信息在这个里面
     *
     * @param headers Headers returned with this response, or null for none
     * 返回回来的header, 要么有一些cookie之类的东西，要么就是什么都没有
     *
     * @param notModified True if the server returned a 304 and the data was already in cache
     * 该参数的字面意思就是“没有修改”， 当请求发送之后服务器给返回304，
     * 并且数据还存在本地的时候，则为true
     * 
     * 服务器返回304的情况：如果客户端发送的是一个条件验证(Conditional Validation)请求,
     * 则web服务器可能会返回HTTP/304响应,这就表明了客户端中所请求资源的缓存仍然是有效的,
     * 也就是说该资源从上次缓存到现在并没有被修改过.
     * 条件请求可以在确保客户端的资源是最新的同时避免因每次都请求完整资源给服务器带来的性能问题.
     * 
     * @param networkTimeMs Round-trip network time to receive network response
     * 从发送请求开始到收到服务器回复所花费的时间， 单位是毫秒
     */
    public NetworkResponse(int statusCode, byte[] data, Map<String, String> headers,
            boolean notModified, long networkTimeMs) {
        this.statusCode = statusCode;
        this.data = data;
        this.headers = headers;
        this.notModified = notModified;
        this.networkTimeMs = networkTimeMs;
    }

    public NetworkResponse(int statusCode, byte[] data, Map<String, String> headers,
            boolean notModified) {
        this(statusCode, data, headers, notModified, 0);
    }

    public NetworkResponse(byte[] data) {
        this(HttpStatus.SC_OK, data, Collections.<String, String>emptyMap(), false, 0);
    }

    public NetworkResponse(byte[] data, Map<String, String> headers) {
        this(HttpStatus.SC_OK, data, headers, false, 0);
    }

    /** The HTTP status code. */
    public final int statusCode;

    /** Raw data from this response. */
    public final byte[] data;

    /** Response headers. */
    public final Map<String, String> headers;

    /** True if the server returned a 304 (Not Modified). */
    public final boolean notModified;

    /** Network roundtrip time in milliseconds. */
    public final long networkTimeMs;
}

