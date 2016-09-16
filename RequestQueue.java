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

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A request dispatch queue with a thread pool of dispatchers.
 * 
 *
 * Calling {@link #add(Request)} will enqueue the given Request for dispatch,
 * resolving from either cache or network on a worker thread, and then delivering
 * a parsed response on the main thread.
 * 调用mQueue.add(Request)函数将一个request放入请求调度队列中排队，将在工作线程中，
 * 从网络或者缓存两个方面对request进行分类并处理，将response返回给主线程中。
 */
public class RequestQueue {

    /** 
     * Callback interface for completed requests. 
     * request完成之后的回掉接口
     * 其中的T用到了java的泛型，是Request调用者所期待返回的数据类型
     * 例如String或者是Integer
     */
    public static interface RequestFinishedListener<T> {
        /**
         * Called when a request has finished processing. 
         * 当一个Request被处理完成时来调用
         * = =其实从方法的名字来看也能看出来
         */
        public void onRequestFinished(Request<T> request);
    }

    /**
     * Used for generating monotonically-increasing sequence numbers for requests. 
     * 用来为request生成单调递增的有序数字，刚才是不知道这里是干什么用的= =
     * 在这里纠结了一小段时间就继续看了下去，直到在add()函数里面看到了这个的用处
     * 在request被add()进来的时候会给每个request发一个类似于排队的序号一样的数字，就是用这个类来实现的
     * 
     * 
     * 官方的解释是：An int value that may be updated atomically. 
     * An AtomicInteger is used in applications such as atomically incremented counters, and cannot be used as a replacement for an Integer.
     * However, this class does extend Number to allow uniform access by tools and utilities that deal with numerically-based classes.
     * 这个类是在需要自动递增计数器的应用中使用的，但是不能作为一个Integer的替代品。
     * 但是这个类确实是继承自Number类的，其允许处理数字的一些工具来统一访问= =。。
     */
    private AtomicInteger mSequenceGenerator = new AtomicInteger();

    /**
     * Staging area for requests that already have a duplicate request in flight.
     * 用HashMap来形成一个筹备区域，这个筹备区域是为重复的request准备的。
     * 每个对应的cacheKey都有一个Queue来存储，因为相同的请求有时不止一个。
     * 这些重复的request已经有一个在被处理了，其他的不用重复处理，在这个HashMap里面等着拿结果就可以了
     * <ul>
     *     <li>containsKey(cacheKey) indicates that there is a request in flight for the given cache
     *          key.
     *         用containsKey(String cacheKey)可以判定一个已经发送出去的请求是否有重复的请求。
     *     </li>
     *     <li>get(cacheKey) returns waiting requests for the given cache key. The in flight request
     *          is <em>not</em> contained in that list. Is null if no requests are staged.</li>
     *         get()方法会返回一个queue，这个queue有可能是空的，也有可能里面存放着具有相同cacheKey的一系列request
     * </ul>
     */
    private final Map<String, Queue<Request<?>>> mWaitingRequests =
            new HashMap<String, Queue<Request<?>>>();

    /**
     * The set of all requests currently being processed by this RequestQueue. A Request
     * will be in this set if it is waiting in any queue or currently being processed by
     * any dispatcher.
     *
     * 一个容纳着所有request的HashSet。
     * 如果一个request正在被调度或者正处于等待状态，该request就在这个集合之中。
     * 这么说的话，RequestQueue里面主要存储request的集合就是这个了。
     * 在外面调用add(Request request)的时候，也就是加入到了这个HashSet之中。
     */
    private final Set<Request<?>> mCurrentRequests = new HashSet<Request<?>>();

    /** 
     * The cache triage queue. 
     * 运用到了优先队列
     * 也就是里面的每个元素都会有一个优先级，优先级高的比优先级低的要先调度。
     * 这个队列里面存放着需要访问缓存的一些Request，等待着调度器(dispatcher)的处理
     * 后面慢慢的会介绍到dispatcher
     */
    private final PriorityBlockingQueue<Request<?>> mCacheQueue =
        new PriorityBlockingQueue<Request<?>>();

    /** 
     * The queue of requests that are actually going out to the network.
     * 网络请求队列
     * 要通过网络在服务器上请求数据的request
     * 还包括一些缓存出了点小问题的request也会被加入到这里
     * 在后面的代码中能够看到
     */
    private final PriorityBlockingQueue<Request<?>> mNetworkQueue =
        new PriorityBlockingQueue<Request<?>>();

    /** 
     * Number of network request dispatcher threads to start. 
     * 网络请求调度线程池中线程的默认数量。
     */
    private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

    /** 
     * Cache interface for retrieving and storing responses. 
     * 缓存的接口，用来从缓存中取出response或者存储response到缓存中。
     */
    private final Cache mCache;

    /** 
     * Network interface for performing requests. 
     * 网络接口，用来进行网络请求。
     */
    private final Network mNetwork;

    /**
     * Response delivery mechanism. 
     * 响应交付机制
     * 请求最后的结果(Response.java实例)通过mDelivery中的方法传回
     * 这个过程需要在工作线程中才能看到，也就是在介绍dispatcher里面能看到
     */
    private final ResponseDelivery mDelivery;

    /**
     * The network dispatchers. 
     * 网络调度线程池
     * 因为是涉及到网络的一个框架，工作的效率不能低
     * 多开几个网络调度器线程来一起工作
     */
    private NetworkDispatcher[] mDispatchers;

    /** 
     * The cache dispatcher. 
     * 缓存调度线程(和上面的差不多吧= =，但是不是线程池了)
     * 处理了涉及到缓存的request
     */
    private CacheDispatcher mCacheDispatcher;

    /**
     * 这个貌似是和listener差不多的用处
     * 每个request结束之后，就会通知所有已经注册过的listener(所谓注册无非就是实现了RequestFinishedListener.java这个接口
     * 然后再将自己传入，加入到这个ArrayList里面来)
     * 在{@link #finish()}里面会用到这个ArrayList
     */
    private List<RequestFinishedListener> mFinishedListeners =
            new ArrayList<RequestFinishedListener>();

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     * 创建工作线程，在start()调用之后开始不停的工作
     *
     * @param cache A Cache to use for persisting responses to disk
     * 涉及到内存访问的接口
     * @param network A Network interface for performing HTTP requests
     * 用来进行HTTP请求的网络接口
     * @param threadPoolSize Number of network dispatcher threads to create
     * 网络请求线程池，里面放着很多个线程，可以同时处理多个需要网络访问的request
     * @param delivery A ResponseDelivery interface for posting responses and errors
     * 一个用来传递resposne和error的接口
     */
    public RequestQueue(Cache cache, Network network, int threadPoolSize,
            ResponseDelivery delivery) {
        mCache = cache;
        mNetwork = network;
        mDispatchers = new NetworkDispatcher[threadPoolSize];
        mDelivery = delivery;
    }

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     */
    public RequestQueue(Cache cache, Network network, int threadPoolSize) {
        this(cache, network, threadPoolSize,
                new ExecutorDelivery(new Handler(Looper.getMainLooper())));
    }

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     */
    public RequestQueue(Cache cache, Network network) {
        this(cache, network, DEFAULT_NETWORK_THREAD_POOL_SIZE);
    }

    /**
     * Starts the dispatchers in this queue.
     * 先将所有的调度线程都停止
     * 再重新创建并启动
     * 将mNetworkQueue和mCacheQueue传入到dispatcher中
     * 方便从queue中取出request来进行处理
     * 将mDelivery接口传入，方便将请求结果返回
     * 
     * cacheDispatcher创建一个就够了，networkDispatcher创建了多个
     * network花费时间比较长，需要开多个线程来工作
     */
    public void start() {
        stop();  // Make sure any currently running dispatchers are stopped.
        // Create the cache dispatcher and start it.
        mCacheDispatcher = new CacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
        mCacheDispatcher.start();

        // Create network dispatchers (and corresponding threads) up to the pool size.
        for (int i = 0; i < mDispatchers.length; i++) {
            NetworkDispatcher networkDispatcher = new NetworkDispatcher(mNetworkQueue, mNetwork,
                    mCache, mDelivery);
            mDispatchers[i] = networkDispatcher;
            networkDispatcher.start();
        }
    }

    /**
     * Stops the cache and network dispatchers.
     * 将所有正在工作状态的dispatcher挨个退出
     */
    public void stop() {
        if (mCacheDispatcher != null) {
            mCacheDispatcher.quit();
        }
        for (int i = 0; i < mDispatchers.length; i++) {
            if (mDispatchers[i] != null) {
                mDispatchers[i].quit();
            }
        }
    }

    /**
     * Gets a sequence number.
     *
     * incrementAndGet() : Atomically increments by one the current value.
     * 自动向上涨一个单位然后返回当前值
     * 在后面的{@link RequestQueue#add(Request)}函数中能看到这个的作用
     * 用到了在前面提到过的AtomicInteger类
     */
    public int getSequenceNumber() {
        return mSequenceGenerator.incrementAndGet();
    }

    /**
     * Gets the {@link Cache} instance being used.
     */
    public Cache getCache() {
        return mCache;
    }

    /**
     * A simple predicate or filter interface for Requests, for use by
     * {@link RequestQueue#cancelAll(RequestFilter)}.
     * 一个request的过滤器
     * 上面说是给cancelAll用的，应该是设置一个RequestFilter之后
     * 将一类的request全都取消掉，至于具体的规则就需要重写里面的函数
     * 定义规则了
     */
    public interface RequestFilter {
        public boolean apply(Request<?> request);
    }

    /**
     * Cancels all requests in this queue for which the given filter applies.
     * 从外面传入一个RequestFilter
     * 按照传入的规则取消所有符合规则的request
     * @param filter The filtering function to use
     */
    public void cancelAll(RequestFilter filter) {
        synchronized (mCurrentRequests) {
            for (Request<?> request : mCurrentRequests) {
                if (filter.apply(request)) {
                    request.cancel();
                }
            }
        }
    }

    /**
     * Cancels all requests in this queue with the given tag. Tag must be non-null
     * 依据request上面的tag来取消
     * and equality is by identity.
     */
    public void cancelAll(final Object tag) {
        if (tag == null) {
            throw new IllegalArgumentException("Cannot cancelAll with a null tag");
        }
        cancelAll(new RequestFilter() {
            @Override
            public boolean apply(Request<?> request) {
                return request.getTag() == tag;
            }
        });
    }

    /**
     * Adds a Request to the dispatch queue.
     * 将新的request加入到总的等待队列中去
     * 一个request被处理之前都要待的地方
     * mCurrentRequests里面存放着所有的request 
     *
     * @param request The request to service
     * 被传入的request，等待被处理
     * @return The passed-in request
     * 将加入的request返回回去
     */
    public <T> Request<T> add(Request<T> request) {
        // Tag the request as belonging to this queue and add it to the set of current requests.
        request.setRequestQueue(this);

        /**
         * 在向mCurrentRequest中添加request的时候
         * 锁住不允许其他的线程进行访问操作
         * 对于synchronized:可用来给对象和方法或者代码块加锁，
         * 当它锁定一个方法或者一个代码块的时候，同一时刻最多只有一个线程执行这段代码。
         * 当两个并发线程访问同一个对象object中的这个加锁同步代码块时，一个时间内只能有一个线程得到执行。
         * 另一个线程必须等待当前线程执行完这个代码块以后才能执行该代码块。
         * 
         */
        synchronized (mCurrentRequests) {
            mCurrentRequests.add(request);
        }

        /**
         * Process requests in the order they are added.
         * 在加入到mCurrentQueue中排队的时候
         * 就像我们排队一样会给我们一个对应的号码牌
         * 只是这里用了getSequenceNumber()函数来自动的发放号码牌
         */
        request.setSequence(getSequenceNumber());
        request.addMarker("add-to-queue");

        /** 
         * If the request is uncacheable, skip the cache queue and go straight to the network.
         * 检查这个request是否是不可缓存的
         * 也就是这个request所返回的response是否需要缓存下来
         */
        if (!request.shouldCache()) {

            /**
             * 如果不需要缓存的话
             * 直接将这个request加入到网络队列中去
             * 并且返回该request
             */
            mNetworkQueue.add(request);
            return request;
        }

        /**
         * Insert request into stage if there's already a request with the same cache key in flight.
         * = =尼玛我这是什么记性，看见这个mWaittingRequests居然不认识了
         * 向前翻到变量声明的地方，清清楚楚的写着专门存放重复请求的地方
         * 根据需要缓存的request生成的特殊标记cacheKey
         * 当然不涉及到缓存的request在上面几行代码被过滤处理了
         */
        synchronized (mWaitingRequests) {

            /**
             * 先获取到这个request的cacheKey
             * 看看有没有和它相同的request已经处于天上飞的状态了
             * (我觉得这里的in flight应该说的是已经发送过了的)
             * 在后面会说明
             */
            String cacheKey = request.getCacheKey();

            if (mWaitingRequests.containsKey(cacheKey)) {

                /**
                 * There is already a request in flight. Queue up.
                 * 如果在等待的队列里面存在着cacheKey对应的一个Queue
                 * 则说明在这个request之前，已经有相同的request发送出去过了
                 * 那么现在需要做的就是将这个request加入到cacheKey对应的Queue存起来
                 * 如果对应的Queue是null,就自己创建一个新的，再把request放入
                 * 
                 * 这个request就不再会被放入到mCacheQueue中去了
                 * 就是坐等数据的意思= =
                 */
                Queue<Request<?>> stagedRequests = mWaitingRequests.get(cacheKey);
                if (stagedRequests == null) {
                    stagedRequests = new LinkedList<Request<?>>();
                }
                stagedRequests.add(request);
                mWaitingRequests.put(cacheKey, stagedRequests);
                if (VolleyLog.DEBUG) {
                    VolleyLog.v("Request for cacheKey=%s is in flight, putting on hold.", cacheKey);
                }
            } else {
                /**
                 * Insert 'null' queue for this cacheKey, indicating there is now a request in flight.
                 * 如果这个涉及到cache的request在它之前根本就没有和他相同的request
                 * 直接以这个cacheKey为key，放一个null进去
                 */
                mWaitingRequests.put(cacheKey, null);
                mCacheQueue.add(request);
            }
            return request;
        }
    }

    /**
     * Called from {@link Request#finish(String)}, indicating that processing of the given request
     * has finished.
     * 从Request中的finish()方法调用开始，预示着给出的request已经结束
     * <p>Releases waiting requests for <code>request.getCacheKey()</code> if
     *      <code>request.shouldCache()</code>.</p>
     * 将处于mWaittingQueue中具有相同cacheKey的一组request全部释放
     * 也就是把上面那些坐等数据的request全部取出来，response发送回去
     */
    <T> void finish(Request<T> request) {
        // Remove from the set of requests currently being processed.
        /**
         * 将mCurrentRequests锁住
         * 一个时间段内只有一个线程可以访问该对象
         * 将已经结束的request从队列中移除
         */
        synchronized (mCurrentRequests) {
            mCurrentRequests.remove(request);
        }

        /**
         * 通知所有注册过的监听器
         * 告诉它们，request已经finish了
         */
        synchronized (mFinishedListeners) {
          for (RequestFinishedListener<T> listener : mFinishedListeners) {
            listener.onRequestFinished(request);
          }
        }

        /**
         * 如果该request涉及到需要缓存
         * 则将mWaitingRequests中具有相同cacheKey的request
         * 全部remove
         */
        if (request.shouldCache()) {
            synchronized (mWaitingRequests) {
                String cacheKey = request.getCacheKey();
                Queue<Request<?>> waitingRequests = mWaitingRequests.remove(cacheKey);
                if (waitingRequests != null) {
                    if (VolleyLog.DEBUG) {
                        VolleyLog.v("Releasing %d waiting requests for cacheKey=%s.",
                                waitingRequests.size(), cacheKey);
                    }
                    // Process all queued up requests. They won't be considered as in flight, but
                    // that's not a problem as the cache has been primed by 'request'.
                    mCacheQueue.addAll(waitingRequests);
                }
            }
        }
    }

    /**
     * 下面两个方法就是所谓注册监听器和取消注册的函数
     */
    public  <T> void addRequestFinishedListener(RequestFinishedListener<T> listener) {
      synchronized (mFinishedListeners) {
        mFinishedListeners.add(listener);
      }
    }

    /**
     * Remove a RequestFinishedListener. Has no effect if listener was not previously added.
     */
    public  <T> void removeRequestFinishedListener(RequestFinishedListener<T> listener) {
      synchronized (mFinishedListeners) {
        mFinishedListeners.remove(listener);
      }
    }
}
