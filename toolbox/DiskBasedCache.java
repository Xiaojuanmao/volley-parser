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

import com.android.volley.Cache;
import com.android.volley.VolleyLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache implementation that caches files directly onto the hard disk in the specified
 * directory. The default disk usage size is 5MB, but is configurable.
 * 实现了Cache接口
 * 专门用于和本地文件交互的一个类
 * 存入缓存和取出缓存等功能
 */
public class DiskBasedCache implements Cache {

    /** 
     * Map of the Key, CacheHeader pairs 
     * CacheHeader.java为本类中的一个static类
     * 里面存放着一些
     */
    private final Map<String, CacheHeader> mEntries =
            new LinkedHashMap<String, CacheHeader>(16, .75f, true);

    /** 
     * Total amount of space currently used by the cache in bytes. 
     * 当前缓存的总大小
     */
    private long mTotalSize = 0;

    /** 
     * The root directory to use for the cache. 
     * 缓存的根目录
     */
    private final File mRootDirectory;

    /** 
     * The maximum size of the cache in bytes. 
     * 缓存能接受的最大字节数
     */
    private final int mMaxCacheSizeInBytes;

    /** 
     * Default maximum disk usage in bytes.
     * 默认缓存能使用的最大空间
     */
    private static final int DEFAULT_DISK_USAGE_BYTES = 5 * 1024 * 1024;

    /** 
     * High water mark percentage for the cache
     * 类似于水位警戒线一样的标识
     */
    private static final float HYSTERESIS_FACTOR = 0.9f;

    /** 
     * Magic number for current version of cache file format. 
     * 
     */
    private static final int CACHE_MAGIC = 0x20150306;

    /**
     * Constructs an instance of the DiskBasedCache at the specified directory.
     * 在指定的目录下面创建一个DiskBasedCache
     *
     * @param rootDirectory The root directory of the cache.
     * @param maxCacheSizeInBytes The maximum size of the cache in bytes.
     */
    public DiskBasedCache(File rootDirectory, int maxCacheSizeInBytes) {
        mRootDirectory = rootDirectory;
        mMaxCacheSizeInBytes = maxCacheSizeInBytes;
    }

    /**
     * Constructs an instance of the DiskBasedCache at the specified directory using
     * the default maximum cache size of 5MB.
     * @param rootDirectory The root directory of the cache.
     */
    public DiskBasedCache(File rootDirectory) {
        this(rootDirectory, DEFAULT_DISK_USAGE_BYTES);
    }

    /**
     * Clears the cache. Deletes all cached files from disk.
     * 清除当前目录下的缓存，删除所有缓存文件 
     */
    @Override
    public synchronized void clear() {
        File[] files = mRootDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        mEntries.clear();
        mTotalSize = 0;
        VolleyLog.d("Cache cleared.");
    }

    /**
     * Returns the cache entry with the specified key if it exists, null otherwise.
     * 通过特殊的key，来获取与缓存交流的接口(entry)
     * 如果没有的话则返回null
     */
    @Override
    public synchronized Entry get(String key) {

        CacheHeader entry = mEntries.get(key);
        // if the entry does not exist, return.
        if (entry == null) {
            return null;
        }

        //依据key获取缓存的文件，如果不存在则创建一个
        File file = getFileForKey(key);

        CountingInputStream cis = null;

        try {

            cis = new CountingInputStream(new BufferedInputStream(new FileInputStream(file)));
            CacheHeader.readHeader(cis); // eat header
            byte[] data = streamToBytes(cis, (int) (file.length() - cis.bytesRead));
            return entry.toCacheEntry(data);

        } catch (IOException e) {
            VolleyLog.d("%s: %s", file.getAbsolutePath(), e.toString());
            remove(key);
            return null;
        }  catch (NegativeArraySizeException e) {
            VolleyLog.d("%s: %s", file.getAbsolutePath(), e.toString());
            remove(key);
            return null;
        } finally {
            if (cis != null) {
                try {
                    cis.close();
                } catch (IOException ioe) {
                    return null;
                }
            }
        }
    }

    /**
     * Initializes the DiskBasedCache by scanning for all files currently in the
     * specified root directory. Creates the root directory if necessary.
     * 对缓存目录的初始化工作，检查目录是否存在
     * 如果不存在就给重新创建一个
     */
    @Override
    public synchronized void initialize() {
        if (!mRootDirectory.exists()) {
            if (!mRootDirectory.mkdirs()) {
                VolleyLog.e("Unable to create cache dir %s", mRootDirectory.getAbsolutePath());
            }
            return;
        }

        /**
         * 如果缓存目录已经存在了
         * 则将缓存目录下面的文件都扫描一遍
         * 将关于缓存文件的部分信息加载到内存中来
         * 方便后面对缓存的查询等工作
         */

        File[] files = mRootDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            BufferedInputStream fis = null;
            try {
                fis = new BufferedInputStream(new FileInputStream(file));
                CacheHeader entry = CacheHeader.readHeader(fis);
                entry.size = file.length();
                putEntry(entry.key, entry);
            } catch (IOException e) {
                if (file != null) {
                   file.delete();
                }
            } finally {
                try {
                    if (fis != null) {
                        fis.close();
                    }
                } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Invalidates an entry in the cache.
     * 将key对应的缓存作废
     * 如果fullExpire为true，则将整个entry作废
     * 如果为false,则只是软作废，也就是将缓存置于需要刷新的状态
     *
     * @param key Cache key
     * @param fullExpire True to fully expire the entry, false to soft expire
     */
    @Override
    public synchronized void invalidate(String key, boolean fullExpire) {
        Entry entry = get(key);
        if (entry != null) {
            entry.softTtl = 0;
            if (fullExpire) {
                entry.ttl = 0;
            }
            put(key, entry);
        }

    }

    /**
     * Puts the entry with the specified key into the cache.
     * 将entry中包含的信息存放到key对应的缓存文件中去
     */
    @Override
    public synchronized void put(String key, Entry entry) {

        pruneIfNeeded(entry.data.length);

        File file = getFileForKey(key);
        try {
            BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(file));
            CacheHeader e = new CacheHeader(key, entry);
            boolean success = e.writeHeader(fos);
            if (!success) {
                fos.close();
                VolleyLog.d("Failed to write header for %s", file.getAbsolutePath());
                throw new IOException();
            }
            fos.write(entry.data);
            fos.close();
            putEntry(key, e);
            return;
        } catch (IOException e) {
        }
        boolean deleted = file.delete();
        if (!deleted) {
            VolleyLog.d("Could not clean up file %s", file.getAbsolutePath());
        }
    }

    /**
     * Removes the specified key from the cache if it exists.
     */
    @Override
    public synchronized void remove(String key) {
        boolean deleted = getFileForKey(key).delete();
        removeEntry(key);
        if (!deleted) {
            VolleyLog.d("Could not delete cache entry for key=%s, filename=%s",
                    key, getFilenameForKey(key));
        }
    }

    /**
     * Creates a pseudo-unique filename for the specified cache key.
     * 通过给定的key，前半段的hashCode和后半段的hashCode连接起来
     * 作为一个独一无二的文件名
     * @param key The key to generate a file name for.
     * @return A pseudo-unique filename.
     */
    private String getFilenameForKey(String key) {
        int firstHalfLength = key.length() / 2;
        String localFilename = String.valueOf(key.substring(0, firstHalfLength).hashCode());
        localFilename += String.valueOf(key.substring(firstHalfLength).hashCode());
        return localFilename;
    }

    /**
     * Returns a file object for the given cache key.
     * 通过调用getFilenameForKey()方法来获取相对路径
     */
    public File getFileForKey(String key) {
        return new File(mRootDirectory, getFilenameForKey(key));
    }

    /**
     * Prunes the cache to fit the amount of bytes specified.
     * 从已有的缓存中清除数据
     * 直到扫出了一片neededSapce大小的空地为止
     * @param neededSpace The amount of bytes we are trying to fit into the cache.
     */
    private void pruneIfNeeded(int neededSpace) {
        if ((mTotalSize + neededSpace) < mMaxCacheSizeInBytes) {
            return;
        }
        if (VolleyLog.DEBUG) {
            VolleyLog.v("Pruning old cache entries.");
        }

        long before = mTotalSize;
        int prunedFiles = 0;
        long startTime = SystemClock.elapsedRealtime();

        Iterator<Map.Entry<String, CacheHeader>> iterator = mEntries.entrySet().iterator();

        while (iterator.hasNext()) {

            Map.Entry<String, CacheHeader> entry = iterator.next();

            CacheHeader e = entry.getValue();

            boolean deleted = getFileForKey(e.key).delete();

            if (deleted) {
                mTotalSize -= e.size;
            } else {
               VolleyLog.d("Could not delete cache entry for key=%s, filename=%s",
                       e.key, getFilenameForKey(e.key));
            }
            iterator.remove();
            prunedFiles++;

            /**
             * 一直清除缓存
             * 直到存入这个neededSapce之后还有一小部分空余的地方
             */
            if ((mTotalSize + neededSpace) < mMaxCacheSizeInBytes * HYSTERESIS_FACTOR) {
                break;
            }
        }

        if (VolleyLog.DEBUG) {
            VolleyLog.v("pruned %d files, %d bytes, %d ms",
                    prunedFiles, (mTotalSize - before), SystemClock.elapsedRealtime() - startTime);
        }
    }

    /**
     * Puts the entry with the specified key into the cache.
     * 将目录下指定的缓存加载到mEntries中去
     * 为了方便之后对缓存的读写操作
     * 全部读写一遍放在内存里面，对查询什么的都会方便很多
     *
     * @param key The key to identify the entry by.
     * @param entry The entry to cache.
     */
    private void putEntry(String key, CacheHeader entry) {
        if (!mEntries.containsKey(key)) {
            mTotalSize += entry.size;
        } else {
            CacheHeader oldEntry = mEntries.get(key);
            mTotalSize += (entry.size - oldEntry.size);
        }
        mEntries.put(key, entry);
    }

    /**
     * Removes the entry identified by 'key' from the cache.
     */
    private void removeEntry(String key) {
        CacheHeader entry = mEntries.get(key);
        if (entry != null) {
            mTotalSize -= entry.size;
            mEntries.remove(key);
        }
    }

    /**
     * Reads the contents of an InputStream into a byte[].
     * 从InputStream中读取指定长度的数据
     * 
     */
    private static byte[] streamToBytes(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int count;
        int pos = 0;
        while (pos < length && ((count = in.read(bytes, pos, length - pos)) != -1)) {
            pos += count;
        }
        if (pos != length) {
            throw new IOException("Expected " + length + " bytes, read " + pos + " bytes");
        }
        return bytes;
    }

    /**
     * Handles holding onto the cache headers for an entry.
     */
    // Visible for testing.
    static class CacheHeader {
        /** 
         * The size of the data identified by this CacheHeader. (This is not
         * serialized to disk.
         * 
         * CacheHeader所表示的数据段的大小 
         */
        public long size;

        /** 
         * The key that identifies the cache entry. 
         * 这个key应该是request对应其缓存的唯一key
         */
        public String key;

        /** 
         * ETag for cache coherence.
         *
         */
        public String etag;

        /** 
         * Date of this response as reported by the server. 
         * 缓存起来的数据返回的日期
         */
        public long serverDate;

        /** 
         * The last modified date for the requested object. 
         * 最后一次更改的时间
         */
        public long lastModified;

        /** 
         * TTL for this record. 
         * ping时候返回的TTL=128的概念如下
         * TTL：生存时间
         * 指定数据报被路由器丢弃之前允许通过的网段数量。
         * TTL 是由发送主机设置的，以防止数据包不断在 IP 互联网络上永不终止地循环。转发 IP 数据包时，要求路由器至少将 TTL 减小 1。
         *  
         * 但是= =，注意这里的和上面的那种不是一个概念，这里只是模拟了上面的概念，但也是用来标志缓存存活时间的。
         */
        public long ttl;

        /** 
         * Soft TTL for this record. 
         * 
         * 根据refreshNeeded()函数来看
         * 意思是需要更新缓存的时间点
         */
        public long softTtl;

        /** 
         * Headers from the response resulting in this cache entry. 
         * 用来指向上一次response的header
         */
        public Map<String, String> responseHeaders;

        private CacheHeader() { }

        /**
         * Instantiates a new CacheHeader object
         * @param key The key that identifies the cache entry
         * @param entry The cache entry.
         */
        public CacheHeader(String key, Entry entry) {
            this.key = key;
            this.size = entry.data.length;
            this.etag = entry.etag;
            this.serverDate = entry.serverDate;
            this.lastModified = entry.lastModified;
            this.ttl = entry.ttl;
            this.softTtl = entry.softTtl;
            this.responseHeaders = entry.responseHeaders;
        }

        /**
         * Reads the header off of an InputStream and returns a CacheHeader object.
         * 从InputStream中读取数据并组建一个CacheHeader对象实例
         * @param is The InputStream to read from.
         * @throws IOException
         */
        public static CacheHeader readHeader(InputStream is) throws IOException {
            CacheHeader entry = new CacheHeader();
            int magic = readInt(is);
            if (magic != CACHE_MAGIC) {
                // don't bother deleting, it'll get pruned eventually
                throw new IOException();
            }
            entry.key = readString(is);
            entry.etag = readString(is);
            if (entry.etag.equals("")) {
                entry.etag = null;
            }
            entry.serverDate = readLong(is);
            entry.lastModified = readLong(is);
            entry.ttl = readLong(is);
            entry.softTtl = readLong(is);
            entry.responseHeaders = readStringStringMap(is);

            return entry;
        }

        /**
         * Creates a cache entry for the specified data.
         * 从CacheHeader转换成Entry类的实例
         */
        public Entry toCacheEntry(byte[] data) {
            Entry e = new Entry();
            e.data = data;
            e.etag = etag;
            e.serverDate = serverDate;
            e.lastModified = lastModified;
            e.ttl = ttl;
            e.softTtl = softTtl;
            e.responseHeaders = responseHeaders;
            return e;
        }


        /**
         * Writes the contents of this CacheHeader to the specified OutputStream.
         * 将CacheHeader里面的数据写入指定的OutputStream中
         */
        public boolean writeHeader(OutputStream os) {
            try {
                writeInt(os, CACHE_MAGIC);
                writeString(os, key);
                writeString(os, etag == null ? "" : etag);
                writeLong(os, serverDate);
                writeLong(os, lastModified);
                writeLong(os, ttl);
                writeLong(os, softTtl);
                writeStringStringMap(responseHeaders, os);
                os.flush();
                return true;
            } catch (IOException e) {
                VolleyLog.d("%s", e.toString());
                return false;
            }
        }

    }

    /**
     * 继承了FilterInputStream
     * 没啥特别的= =
     */

    private static class CountingInputStream extends FilterInputStream {

        private int bytesRead = 0;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result != -1) {
                bytesRead++;
            }
            return result;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            int result = super.read(buffer, offset, count);
            if (result != -1) {
                bytesRead += result;
            }
            return result;
        }
    }

    /*
     * Homebrewed simple serialization system used for reading and writing cache
     * headers on disk. Once upon a time, this used the standard Java
     * Object{Input,Output}Stream, but the default implementation relies heavily
     * on reflection (even for standard types) and generates a ton of garbage.
     * 
     */

    /**
     * Simple wrapper around {@link InputStream#read()} that throws EOFException
     * instead of returning -1.
     * 如果文件读到了末尾直接抛出异常
     */
    private static int read(InputStream is) throws IOException {
        int b = is.read();
        if (b == -1) {
            throw new EOFException();
        }
        return b;
    }

    /**
     * 刚开始看到这里的时候没有明白是什么意思= =
     * 就不明白了，好好的一个int类型的数据
     * 为什么非要分段写入呢，一个字节一个字节的写入
     * 后来查了资料才发现，OutputStream及其子类的write()方法
     * 一次都只能写入一个byte，int类型有4个byte，分四次写入没什么问题咯
     */

    static void writeInt(OutputStream os, int n) throws IOException {
        os.write((n >> 0) & 0xff);
        os.write((n >> 8) & 0xff);
        os.write((n >> 16) & 0xff);
        os.write((n >> 24) & 0xff);
    }

    static int readInt(InputStream is) throws IOException {
        int n = 0;
        n |= (read(is) << 0);
        n |= (read(is) << 8);
        n |= (read(is) << 16);
        n |= (read(is) << 24);
        return n;
    }

    static void writeLong(OutputStream os, long n) throws IOException {
        os.write((byte)(n >>> 0));
        os.write((byte)(n >>> 8));
        os.write((byte)(n >>> 16));
        os.write((byte)(n >>> 24));
        os.write((byte)(n >>> 32));
        os.write((byte)(n >>> 40));
        os.write((byte)(n >>> 48));
        os.write((byte)(n >>> 56));
    }

    static long readLong(InputStream is) throws IOException {
        long n = 0;
        n |= ((read(is) & 0xFFL) << 0);
        n |= ((read(is) & 0xFFL) << 8);
        n |= ((read(is) & 0xFFL) << 16);
        n |= ((read(is) & 0xFFL) << 24);
        n |= ((read(is) & 0xFFL) << 32);
        n |= ((read(is) & 0xFFL) << 40);
        n |= ((read(is) & 0xFFL) << 48);
        n |= ((read(is) & 0xFFL) << 56);
        return n;
    }

    static void writeString(OutputStream os, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        writeLong(os, b.length);
        os.write(b, 0, b.length);
    }

    static String readString(InputStream is) throws IOException {
        int n = (int) readLong(is);
        byte[] b = streamToBytes(is, n);
        return new String(b, "UTF-8");
    }

    static void writeStringStringMap(Map<String, String> map, OutputStream os) throws IOException {
        if (map != null) {
            writeInt(os, map.size());
            for (Map.Entry<String, String> entry : map.entrySet()) {
                writeString(os, entry.getKey());
                writeString(os, entry.getValue());
            }
        } else {
            writeInt(os, 0);
        }
    }

    /**
     * 从InputStream中读取key类型为String，值类型也为String的Map
     */ 
    static Map<String, String> readStringStringMap(InputStream is) throws IOException {
        int size = readInt(is);
        Map<String, String> result = (size == 0)
                ? Collections.<String, String>emptyMap()
                : new HashMap<String, String>(size);
        for (int i = 0; i < size; i++) {

            //将读出来的byte[]转换成String

            String key = readString(is).intern();
            String value = readString(is).intern();
            result.put(key, value);
        }
        return result;
    }
}
