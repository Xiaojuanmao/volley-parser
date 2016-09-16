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

/**
 * Encapsulates a parsed response for delivery.
 * 为了方便deliver，封装了一个解析好了的response
 *
 * @param <T> Parsed type of this response
 * T 就是response中解析出来的类
 */
public class Response<T> {

    /** 
     * Callback interface for delivering parsed responses. 
     * 用于传递解析好了的response的接口
     * 没有出错就会回掉这个
     */
    public interface Listener<T> {
        /** Called when a response is received. */
        public void onResponse(T response);
    }

    /** 
     * Callback interface for delivering error responses.
     * 用于传递错误的response
     * 出错了会回掉这个
     */
    public interface ErrorListener {
        /**
         * Callback method that an error has been occurred with the
         * provided error code and optional user-readable message.
         */
        public void onErrorResponse(VolleyError error);
    }

    /** 
     * Returns a successful response containing the parsed result. 
     * 返回一个成功的response，里面会包含解析的结果
     */
    public static <T> Response<T> success(T result, Cache.Entry cacheEntry) {
        return new Response<T>(result, cacheEntry);
    }

    /**
     * Returns a failed response containing the given error code and an optional
     * localized message displayed to the user.
     */
    public static <T> Response<T> error(VolleyError error) {
        return new Response<T>(error);
    }

    /** 
     * Parsed response, or null in the case of error. 
     * 客户端这边期待的返回结果的引用
     */
    public final T result;

    /** 
     * Cache metadata for this response, or null in the case of error. 
     * 缓存response的元数据，如果response出现了error，这个引用则为null.
     * Cache.Entry在Cache.java中定义，里面包含了byte[]等数据，用来存储
     * 关于response的一些相关信息。
     */
    public final Cache.Entry cacheEntry;

    /** Detailed error information if <code>errorCode != OK</code>. */
    public final VolleyError error;

    /** 
     * True if this response was a soft-expired one and a second one MAY be coming. 
     * 如果这个response是"软过期"的话，intermediate则为true.
     */
    public boolean intermediate = false;

    /**
     * Returns whether this response is considered successful.
     */
    public boolean isSuccess() {
        return error == null;
    }


    private Response(T result, Cache.Entry cacheEntry) {
        this.result = result;
        this.cacheEntry = cacheEntry;
        this.error = null;
    }

    private Response(VolleyError error) {
        this.result = null;
        this.cacheEntry = null;
        this.error = error;
    }
}
