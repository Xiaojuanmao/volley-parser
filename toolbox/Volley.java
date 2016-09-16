/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.http.AndroidHttpClient;
import android.os.Build;

import com.android.volley.Network;
import com.android.volley.RequestQueue;

import java.io.File;

public class Volley {

    /** Default on-disk cache directory. */
    private static final String DEFAULT_CACHE_DIR = "volley";

    /**
     * Creates a default instance of the worker pool and calls {@link RequestQueue#start()} on it.
     * You may set a maximum size of the disk cache in bytes.
     * 创建一个默认的线程池，并将其启动
     * 还能通过构造函数来设置缓存的最大容量，默认的是5*1024*1024个字节
     *
     * @param context A {@link Context} to use for creating the cache dir.
     * 用于创建缓存目录的context
     * @param stack An {@link HttpStack} to use for the network, or null for default.
     * HttpStack可以通过外面自定义之后传入，也可以不管直接用默认的
     * @param maxDiskCacheBytes the maximum size of the disk cache, in bytes. Use -1 for default size.
     * 最大缓存的字节数
     * @return A started {@link RequestQueue} instance.
     */
    public static RequestQueue newRequestQueue(Context context, HttpStack stack, int maxDiskCacheBytes) {

    	//通过context，创建用于缓存文件的目录
        File cacheDir = new File(context.getCacheDir(), DEFAULT_CACHE_DIR);

        String userAgent = "volley/0";
        
        try {
            String packageName = context.getPackageName();

            /**
             * 关于PackageInfo，官方文档的解释如下：
             * Overall information about the contents of a package.
             * This corresponds to all of the information collected from AndroidManifest.xml.
             * 该类作为Package信息的基类，还有很多子类例如：ApplicationInfo、 ComponentInfo等。
             * 这些类包含了一些关于安装包的信息，icon,label等
             */
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            //获取到了Package的版本号
            userAgent = packageName + "/" + info.versionCode;

        } catch (NameNotFoundException e) {
        }

        /**
         * HttpStack是一个用于网络请求的接口
         * 如果传入的stack为空，则根据当前系统的版本号，来选择不同的实现了HttpStack(Volley自己的一个接口)的类对象
         * 高于android2.3就用HurlStack(基于HttpsURLConnection)
         * 低于android2.3就用HttpClientStack(基于HttpClient)
         */
        if (stack == null) {
            if (Build.VERSION.SDK_INT >= 9) {
                stack = new HurlStack();
            } else {
                // Prior to Gingerbread, HttpUrlConnection was unreliable.
                // See: http://android-developers.blogspot.com/2011/09/androids-http-clients.html
                stack = new HttpClientStack(AndroidHttpClient.newInstance(userAgent));
            }
        }

        /**
         * 创建了一个用于发送特定请求的Network类对象
         * 该接口中有一个与HttpStack接口中同名的方法(performRequest)
         * 但是参数的内容不同， 返回的类型也有所区别
         * Network的返回类型是自定义的一个NetworkResponse类
         * 而HttpStack返回的是HttpResponse
         * (HttpResponse是java.apache.http中的一个类，里面包含了服务器返回的一些数据)
         * 
         * 将stack传入到了已经实现了Network接口的一个BasicNetwork类中
         * 在后面发送Request请求的时候会调用Network.performRequest()
         * 然后在Network.performRequest()函数中会继续调用HttpStack.performRequest()
         * 真正的网络请求发出是在HttpStack.performRequest()中进行的
         */

        Network network = new BasicNetwork(stack);
        
        /**
         * 创建一个RequestQueue引用
         * RequestQueue是volley实现的一个请求调度队列
         * 用来分发处理request
         * 后面会分析RequestQueue.java
         */
        RequestQueue queue;

        /**
         * 根据是否设置了最大缓存字节数
         * 来用不同的构造器生成RequestQueue对象
         * 其中第一个构造参数为一个实现了Cache.java接口的默认缓存读写类DiskBasedCache.java
         * 现在只需要知道它是用来专门处理缓存的就可以了，后面也会对源码做出分析
         * 第二个参数是接口Network.java类的引用，在上面两排不远处可以看到BasicNetwork.java
         * 它是用来实现网络请求的一个类。
         */        
        if (maxDiskCacheBytes <= -1)
        {
        	// No maximum size specified
        	queue = new RequestQueue(new DiskBasedCache(cacheDir), network);
        }
        else
        {
        	// Disk cache size specified
        	queue = new RequestQueue(new DiskBasedCache(cacheDir, maxDiskCacheBytes), network);
        }

        //启动了创建的RequestQueue对象，里面的各种工作线程开始工作
        queue.start();

        return queue;
    }
    
    /**
     * 下面的三个构造器最后都是调用了第一个构造器
     * 不用做进一步的解释了吧
     */

    /**
     * Creates a default instance of the worker pool and calls {@link RequestQueue#start()} on it.
     * You may set a maximum size of the disk cache in bytes.
     *
     * @param context A {@link Context} to use for creating the cache dir.
     * @param maxDiskCacheBytes the maximum size of the disk cache, in bytes. Use -1 for default size.
     * @return A started {@link RequestQueue} instance.
     */
    public static RequestQueue newRequestQueue(Context context, int maxDiskCacheBytes) {
        return newRequestQueue(context, null, maxDiskCacheBytes);
    }
    
    /**
     * Creates a default instance of the worker pool and calls {@link RequestQueue#start()} on it.
     *
     * @param context A {@link Context} to use for creating the cache dir.
     * @param stack An {@link HttpStack} to use for the network, or null for default.
     * @return A started {@link RequestQueue} instance.
     */
    public static RequestQueue newRequestQueue(Context context, HttpStack stack)
    {
    	return newRequestQueue(context, stack, -1);
    }
    
    /**
     * Creates a default instance of the worker pool and calls {@link RequestQueue#start()} on it.
     *
     * @param context A {@link Context} to use for creating the cache dir.
     * @return A started {@link RequestQueue} instance.
     */
    public static RequestQueue newRequestQueue(Context context) {
        return newRequestQueue(context, null);
    }

}

