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

import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing cache triage on a queue of requests.
 * 提供一个用来处理涉及到缓存的requests的线程
 * 
 * Requests added to the specified cache queue are resolved from cache.
 * Any deliverable response is posted back to the caller via a
 * {@link ResponseDelivery}.  Cache misses and responses that require
 * refresh are enqueued on the specified network queue for processing
 * by a {@link NetworkDispatcher}.
 * CacheDispatcher用来处理缓存队列里面(mCacheQueue)中的request
 * 任何符合delivery要求的response都会被通过ResponseDelivery的接口传递给caller
 * 有些cache丢失了或者是cache中的数据需要更新的，都将会交给NetworkDispatcher去处理
 * 交给NetworkDispatcher处理的方法就是直接放到mNetworkQueue中去
 * 因为NetworkDispatcher总是从mNetworkQueue中取出request来进行处理的
 */


public class CacheDispatcher extends Thread {

    private static final boolean DEBUG = VolleyLog.DEBUG;

    /** 
     * The queue of requests coming in for triage. 
     * 将要被处理的涉及到缓存的Request存放在这个阻塞队列里
     */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /** 
     * The queue of requests going out to the network. 
     * 这个阻塞队里里面存着的可是要去进行网络访问的request
     * 开始还不明白这里不应该是涉及到访问缓存的request
     * 怎么有个这东西出来了，其实看到后面了就会发现，缓存里面有两个过期时间
     * 在后面会介绍到Cache.java类，Cache.Entry类中涉及到了
     * ttl 和 softTtl这两个long型的数据，用来标识缓存是否已经过期了
     * 或者是否需要去检查是否要更新缓存的两个间隔时间
     */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /** 
     * The cache to read from. 
     * 用于读写缓存的接口
     * 这个接口也是在Volley中只有一个
     * mCacheDispatcher和mNetworkDispatcher公用的
     */
    private final Cache mCache;

    /** 
     * For posting responses. 
     * ResponseDelivery对象引用，用来将request的结果传递给caller
     * 在NetworkDispatcher里面也有出现
     * 这个也是从RequestQueue中传递过来的，公用
     */
    private final ResponseDelivery mDelivery;

    /** 
     * Used for telling us to die. 
     * 直译 ： 用来告诉我们去死= = (shit)
     * 然而 ： 这个变量用来标志这个dispatcher是否要继续工作下去
     * 如果为true就结束本线程中的死循环
     */
    private volatile boolean mQuit = false;

    /**
     * Creates a new cache triage dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     * 构造函数咯，创建一个存放需要访问缓存的request的调度线程
     * 在创建之后需要将其用start()启动
     * 
     * @param cacheQueue Queue of incoming requests for triage
     * 存放request的缓存队列
     * @param networkQueue Queue to post requests that require network to
     * 存放涉及network的网络队列
     * @param cache Cache interface to use for resolution
     * 用来处理缓存读写问题的接口
     * @param delivery Delivery interface to use for posting responses
     * 用来反馈结果的接口
     */
    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
        mCache = cache;
        mDelivery = delivery;
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     * 将标志位mQuit置为true,在每次死循环的最后会判断该标志位
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    /**
     * 前面提到了CacheDispatcher继承了Thread类
     * 这里就重写了run()方法
     * 当外面调用了mCacheDispatcher.start()之后
     * run()里面的方法就开始执行了
     */
    @Override
    public void run() {

        if (DEBUG) VolleyLog.v("start new dispatcher");
        /**
         * 给自己设置了线程的优先级
         * THREAD_PRIORITY_BACKGROUND的优先级是0x0000000a(也就是10)
         * 还有其他的很多种优先级，该优先级处于较高的位置
         */
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        /**
         * Make a blocking call to initialize the cache.
         * 在读写缓存之前做一些初始化工作，例如扫描缓存目录是否存在等
         * 这个暂时先不用管里面的内容，等介绍到Cache.java的时候就会明白
         */
        mCache.initialize();


        /**
         * 从这里开始就进入了死循环的状态
         * 除非出现了什么没有catch的exception
         * 或者是mQuit标志位被置成了true
         * 这个死循环将一直进行下去= =
         */
        while (true) {
            /**
             * 和NetworkDispatcher里面的流程没有什么太大的变化
             * 还是一个死循环不停的从CacheQueue中取出Request
             */
            try {
                /**
                 * Get a request from the cache triage queue, blocking until
                 * at least one is available.
                 * 从缓存request队列里面取出等待处理的request
                 * 如果没有可取出的request，则会在这里阻塞
                 * 这个是{#link PriorityBlockingQueue#take()}函数的作用
                 * 
                 */
                final Request<?> request = mCacheQueue.take();

                /**
                 * 给每个request添加上一个打log的标志
                 * 为了debug的需要
                 */
                request.addMarker("cache-queue-take");

                /**
                 * If the request has been canceled, don't bother dispatching it.
                 * 如果正在处理的这个请求被取消了
                 * 中断对该request的处理，continue去处理下一个request的调度
                 * 调用{#link Request#finish()}方法，传入的参数是为了debug方便，打出request调度进度的log
                 */
                if (request.isCanceled()) {
                    request.finish("cache-discard-canceled");
                    continue;
                }

                /**
                 * 在这里NetworkDispatcher和CacheDispatcher出现了一点差异
                 * NetworkDispatcher.java在这一步就直接开始网络请求了
                 * 
                 * 由于是CacheDispatcher.java，肯定是主要以Cahce为主的
                 * CacheDispatcher在这里先看看有没有缓存
                 * 如果没有缓存则马上将这个request加入到NetworkQueue中
                 * (意思好像就是= =兄弟你排错队了)
                 * 然后继续喊下一个request来被处理
                 */
                // Attempt to retrieve this item from cache.
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {
                    request.addMarker("cache-miss");
                    // Cache miss; send off to the network dispatcher.
                    mNetworkQueue.put(request);
                    continue;
                }

                /**
                 * 能到这一步的request不简单了
                 * 肯定是被上面的mCache.get(cacheKey)查到了有缓存的(毕竟有靠山的伤不起)
                 * 有缓存还不能太大意= =，万一缓存尼玛是个过期的就惨了= =
                 * 先用entry.isExpired()函数检查一番
                 * 过期了照样还是给我滚到NetworkQueue中去排队
                 *
                 * 继续喊下一个request来
                 */
                // If it is completely expired, just send it to the network.
                if (entry.isExpired()) {
                    request.addMarker("cache-hit-expired");
                    request.setCacheEntry(entry);
                    mNetworkQueue.put(request);
                    continue;
                }

                /**
                 * 哎哟，能到这一步的request更加不简单了，不仅仅有缓存
                 * 而且还是能用的缓存，没有过期的诶，这才是有真的靠山= =
                 *
                 * 将缓存的信息都拿出来，组成一个NetworkResponse
                 * 就像是刚刚从网络上获取出来的一样，再形成一个Response.java对象
                 * NetworkResponse.java是apache包里面的，而Response.java则是Volley自定义的一个对象
                 * 但是不要着急把这个response直接传回caller，这个response还没确定是否需要refresh
                 */

                // We have a cache hit; parse its data for delivery back to the request.
                request.addMarker("cache-hit");

                /**
                 * 将一个由缓存中的数据创建的NetworkResponse.java对象
                 * 通过{#link Request#parseNetworkResponse()}方法
                 * 来解析成一个Response.java对象
                 */
                Response<?> response = request.parseNetworkResponse(
                        new NetworkResponse(entry.data, entry.responseHeaders));

                //为了方便debug，对request每一个时期的状态都需要添加不同的log信息
                request.addMarker("cache-hit-parsed");

                if (!entry.refreshNeeded()) {
                    // Completely unexpired cache hit. Just deliver the response.
                    /**
                     * 如果缓存不需要刷新的话，直接传回给caller
                     */
                    mDelivery.postResponse(request, response);
                } else {
                    // Soft-expired cache hit. We can deliver the cached response,
                    // but we need to also send the request to the network for
                    // refreshing.
                    /**
                     * 如果需要刷新的话，将这个response中的intermediate参数置为true
                     * 然后再传递给caller，
                     * 随后将请求发送到服务器进行刷新
                     */
                    request.addMarker("cache-hit-refresh-needed");
                    request.setCacheEntry(entry);

                    /**
                     * Mark the response as intermediate.
                     * 将这个response标记成中间产物，也就不是最终的response
                     * 
                     */
                    response.intermediate = true;

                    /** 
                     * Post the intermediate response back to the user and have
                     * the delivery then forward the request along to the network.
                     * poseResponse()方法中的Runnable是在response被传递给caller了之后
                     * 再执行的，在ResponseDelivery.java中有注释
                     * 
                     */
                    mDelivery.postResponse(request, response, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                //将request加入到网络请求队列中去
                                mNetworkQueue.put(request);
                            } catch (InterruptedException e) {
                                // Not much we can do about this.
                            }
                        }
                    });
                }

            } catch (InterruptedException e) {
                //当cacheQueue中没有request之后就会捕捉到异常
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }
        }
    }
}
