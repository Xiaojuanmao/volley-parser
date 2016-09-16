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

import java.util.Collections;
import java.util.Map;

/**
 * An interface for a cache keyed by a String with a byte array as data.
 * 一个用于缓存的接口
 */
public interface Cache {
    /**
     * Retrieves an entry from the cache.
     * 用来获取缓存的入口，通过传入的key
     * 
     * @param key Cache key
     * 这个key应该是request对应其缓存的唯一key
     *
     * @return An {@link Entry} or null in the event of a cache miss
     */
    public Entry get(String key);

    /**
     * Adds or replaces an entry to the cache.
     * 添加或者给request更新缓存
     * @param key Cache key
     * @param entry Data to store and metadata for cache coherency, TTL, etc.
     */
    public void put(String key, Entry entry);

    /**
     * Performs any potentially long-running actions needed to initialize the cache;
     * will be called from a worker thread.
     */
    public void initialize();

    /**
     * Invalidates an entry in the cache.
     * 这个函数是将key对应的缓存置于过期
     * 分为fully expire和soft expire， 目前还不知道是什么意思 = =
     * 
     * @param key Cache key
     * @param fullExpire True to fully expire the entry, false to soft expire
     */
    public void invalidate(String key, boolean fullExpire);

    /**
     * Removes an entry from the cache.
     * 将key对应的缓存直接移除掉
     * 
     * @param key Cache key
     */
    public void remove(String key);

    /**
     * Empties the cache.
     * 清除所有的缓存
     */
    public void clear();

    /**
     * Data and metadata for an entry returned by the cache.
     */
    public static class Entry {
        /** The data returned from cache. */
        public byte[] data;

        /** ETag for cache coherency. */
        public String etag;

        /** Date of this response as reported by the server. */
        public long serverDate;

        /** The last modified date for the requested object. */
        public long lastModified;

        /** TTL for this record.
         *  根据后面的isExpired()函数来看
         *  该条数据的意思应该是缓存过期的时间 
         */
        public long ttl;

        /** Soft TTL for this record. 
         *  根据refreshNeeded()函数来看
         *  意思是需要更新缓存的时间点
         */
        public long softTtl;

        /** Immutable response headers as received from server; must be non-null. */
        public Map<String, String> responseHeaders = Collections.emptyMap();

        /** 
         * True if the entry is expired. 
         * 用来查看缓存是否过期了
         */
        public boolean isExpired() {
            return this.ttl < System.currentTimeMillis();
        }

        /** True if a refresh is needed from the original data source. */
        public boolean refreshNeeded() {
            return this.softTtl < System.currentTimeMillis();
        }
    }

}
