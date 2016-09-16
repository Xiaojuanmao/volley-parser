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

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing network dispatch from a queue of requests.
 * 提供一个线程专门用来从请求队列(NetworkQueue)里面调度网络请求
 * 
 * Requests added to the specified queue are processed from the network via a
 * specified {@link Network} interface. Responses are committed to cache, if
 * eligible, using a specified {@link Cache} interface. Valid responses and
 * errors are posted back to the caller via a {@link ResponseDelivery}.
 *
 * 被加入到RequestQueue中的request会被NetWork的接口进一步加工处理.
 * 如果从网络返回的response是符合条件的，则会被添加到缓存中去。
 * 有效的response将通过ResponseDelivery返回给调用者
 */
public class NetworkDispatcher extends Thread {
    /** 
     * The queue of requests to service. 
     * 这个queue就是RequestQueue.java中的mNetworkQueue
     */
    private final BlockingQueue<Request<?>> mQueue;

    /** 
     * The network interface for processing requests. 
     * 处理request的接口，其中的方法是performRequest()
     */
    private final Network mNetwork;     

    /** 
     * The cache to write to. 
     * 处理缓存的接口
     */
    private final Cache mCache;

    /** 
     * For posting responses and errors. 
     * 用来传递response和error的deliver.
     */
    private final ResponseDelivery mDelivery;

    /** 
     * Used for telling us to die. 、
     * 这里使用到了volatile变量
     * 这个volatile类似于final之类的修饰词
     * 是用来保证每次mQuit被读取的时候都是最新的
     * 避免了读取的值和实际变量的值不同的情况
     * 可以参考这篇博客，讲解的比较详细：
     * http://www.cnblogs.com/aigongsi/archive/2012/04/01/2429166.html
     */
    private volatile boolean mQuit = false;

    /**
     * Creates a new network dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     * 构造器，用于创建一个新的网络调度线程，必须要调用call来开始处理request
     * 
     * @param queue Queue of incoming requests for triage
     * 等待处理的request队列
     * @param network Network interface to use for performing requests
     * @param cache Cache interface to use for writing responses to cache
     * @param delivery Delivery interface to use for posting responses
     */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
            Network network, Cache cache,
            ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
        mCache = cache;
        mDelivery = delivery;
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     * 强制调度器立刻退出，不再调度request。
     * 
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    /**
     * 这里涉及到了TrafficStats类，官方解释如下：
     * Class that provides network traffic statistics. 
     * 这个类提供网络流量统计的服务。
     * These statistics include bytes transmitted and received and network packets transmitted and received, 
     * over all interfaces, over the mobile interface, and on a per-UID basis.
     * 这些被统计的流量包括传输的字节数和收到的字节数以及网络数据包
     * These statistics may not be available on all platforms. 
     * If the statistics are not supported by this device, UNSUPPORTED will be returned.
     * 这些数据并不是在所有的平台上都可以用
     * 如果不可用，则会返回UNSPPORTED
     * 貌似是每个request都统计一下网络流量= =
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void addTrafficStatsTag(Request<?> request) {
        // Tag the request (if API >= 14)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            TrafficStats.setThreadStatsTag(request.getTrafficStatsTag());
        }
    }

    /**
     * 由于NetworkDispatcher继承自Thread，重写了run()方法
     * 里面的内容都会在另启动一个线程来执行
     * 在CacheDispatcher中有很多相似的地方
     */
    @Override
    public void run() {
        /**
         * 给自己设置了线程的优先级
         * THREAD_PRIORITY_BACKGROUND的优先级是0x0000000a(也就是10)
         * 还有其他的很多种优先级，该优先级处于较高的位置
         */
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        Request<?> request;

        /**
         * 进入了一个死循环状态
         * 开始不停的工作
         */
        while (true) {

            /**
             * elapsedRealtime()函数返回的是线程从启动到现在的总时间
             * 也包括线程睡眠时间在内
             * 单看这一句看不出什么门道，结合在后面的异常处理时会用到startTimeMs
             * 这里是记录一个request开始的时刻点，到后面再次调用elapsedRealtime()
             * 两个变量相减得到了request花费了多长的时间
             */
            long startTimeMs = SystemClock.elapsedRealtime();
            /**
             * release previous request object to avoid leaking request object when mQueue is drained.
             * 释放前面的一个Request对象，以免因为Request对象不停的申请而导致内存泄漏
             */
            request = null;

            /**
             * 尝试着从RequestQueue中取出一个Request，对其进行处理
             * 可能会因为某些原因(可能是队列中没有元素了)会抛出异常
             * 这个时候就捕捉异常并检验是否要退出了，需要退出则return
             * 不需要退出则继续下一次循环，看有没有Request可以拿到
             */
            try {
                // Take a request from the queue.
                request = mQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }

            /**
             * 到这一步的时候，request应该是指向了一个Request
             * 下面开始向服务器发送这个Request
             */

            try {
                request.addMarker("network-queue-take");

                // If the request was cancelled already, do not perform the
                // network request.
                if (request.isCanceled()) {
                    request.finish("network-discard-cancelled");
                    continue;
                }

                addTrafficStatsTag(request);

                /**
                 * Perform the network request.
                 * 直接调用mNetwork的接口，发送request并获得NetworkResponse
                 */
                NetworkResponse networkResponse = mNetwork.performRequest(request);
                request.addMarker("network-http-complete");

                // If the server returned 304 AND we delivered a response already,
                // we're done -- don't deliver a second identical response.
                if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                    request.finish("not-modified");
                    continue;
                }

                /**
                 * Parse the response here on the worker thread.
                 * 在工作线程上面直接解析结果
                 * 并且封装成一个Response对象
                 */
                Response<?> response = request.parseNetworkResponse(networkResponse);
                request.addMarker("network-parse-complete");

                /** Write to cache if applicable.
                 *  如果符合要求，能写入缓存的话，就写到缓存里面
                 */
                // TODO: Only update cache metadata instead of entire record for 304s.

                /**
                 * 还能改进的地方就是在出现了返回码是
                 * 304的情况时，只更新缓存中的元数据(也就是response的主体)
                 * 而不是整个cache的记录下来,有些重复的数据可以不用理会.
                 */
                if (request.shouldCache() && response.cacheEntry != null) {
                    mCache.put(request.getCacheKey(), response.cacheEntry);
                    request.addMarker("network-cache-written");
                }

                /**
                 * 将Request.java中的变量mResponseDelivered置成true
                 * 标志着这个request的结果已经传回给了caller
                 */

                request.markDelivered();

                /**
                 * 通过ResponseDelivery的接口将包装好了的Response返回给调用者
                 */
                mDelivery.postResponse(request, response);

            } catch (VolleyError volleyError) {
                /**
                 * 设置了request从队列中取出到服务器出现异常反应
                 * 所花费的时间
                 */
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);

                /**
                 * 将网络请求的错误通过ResponseDelivery传递给调用者
                 * 告诉它这.....不幸的一切
                 */
                parseAndDeliverNetworkError(request, volleyError);

            } catch (Exception e) {
                VolleyLog.e(e, "Unhandled exception %s", e.toString());
                VolleyError volleyError = new VolleyError(e);
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                mDelivery.postError(request, volleyError);
            }
        }
    }

    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        error = request.parseNetworkError(error);
        mDelivery.postError(request, error);
    }
}
