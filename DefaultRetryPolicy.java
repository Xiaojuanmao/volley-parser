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
 * Default retry policy for requests.
 */
public class DefaultRetryPolicy implements RetryPolicy {
    /** 
     * The current timeout in milliseconds. 
     * 请求超时的时间数
     */
    private int mCurrentTimeoutMs;

    /** 
     * The current retry count.
     * 当前请求的重试次数
     */
    private int mCurrentRetryCount;

    /** 
     * The maximum number of attempts. 
     * 重复尝试的最大次数
     **/
    private final int mMaxNumRetries;

    /** 
     * The backoff multiplier for the policy.
     * 暂时不明白这个有什么用
     * 根据后面的来看好像是一个用于放宽timeout时间限制的系数
     * 这个系数越大每次retry的时候放宽的程度也就越大
     */
    private final float mBackoffMultiplier;

    /** The default socket timeout in milliseconds */
    public static final int DEFAULT_TIMEOUT_MS = 2500;

    /** The default number of retries */
    public static final int DEFAULT_MAX_RETRIES = 0;

    /** The default backoff multiplier */
    public static final float DEFAULT_BACKOFF_MULT = 1f;


    /**
     * Constructs a new retry policy using the default timeouts.
     * 默认的构造器，2.5s的超时，
     * 最大重试数为0，也就是request请求超时了就不要再自动重试了
     */
    public DefaultRetryPolicy() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MULT);
    }

    /**
     * Constructs a new retry policy.
     * @param initialTimeoutMs The initial timeout for the policy.
     * @param maxNumRetries The maximum number of retries.
     * @param backoffMultiplier Backoff multiplier for the policy.
     */
    public DefaultRetryPolicy(int initialTimeoutMs, int maxNumRetries, float backoffMultiplier) {
        mCurrentTimeoutMs = initialTimeoutMs;
        mMaxNumRetries = maxNumRetries;
        mBackoffMultiplier = backoffMultiplier;
    }

    /**
     * Returns the current timeout.
     */
    @Override
    public int getCurrentTimeout() {
        return mCurrentTimeoutMs;
    }

    /**
     * Returns the current retry count.
     */
    @Override
    public int getCurrentRetryCount() {
        return mCurrentRetryCount;
    }

    /**
     * Returns the backoff multiplier for the policy.
     */
    public float getBackoffMultiplier() {
        return mBackoffMultiplier;
    }

    /**
     * Prepares for the next retry by applying a backoff to the timeout.
     * 该方法定义在RetryPolicy.java中。
     * 为下一次重试request做准备，好像是通过延长服务器响应时间的限制 = =
     * 每次对timeout的时间限制都放宽了mBackoffMultiplier个单位。让服务器多一点时间去响应
     * 
     * @param error The error code of the last attempt.
     * 最后一次request服务器给出的错误码
     */
    @Override
    public void retry(VolleyError error) throws VolleyError {
        mCurrentRetryCount++;
        mCurrentTimeoutMs += (mCurrentTimeoutMs * mBackoffMultiplier);
        if (!hasAttemptRemaining()) {
            throw error;
        }
    }

    /**
     * Returns true if this policy has attempts remaining, false otherwise.
     * 这个是用来判断是否还有retry机会的
     */
    protected boolean hasAttemptRemaining() {
        return mCurrentRetryCount <= mMaxNumRetries;
    }
}
