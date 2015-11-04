/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tv.util;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.util.Range;

import com.android.tv.data.Channel;
import com.android.tv.data.Program;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AsyncTask} that defaults to executing on its own single threaded Executor Service.
 *
 * <p>Instances of this class should only be executed this using {@link
 * #executeOnDbThread(Object[])}.
 */
public abstract class AsyncDbTask<Params, Progress, Result>
        extends AsyncTask<Params, Progress, Result> {
    private static final String TAG = "AsyncDbTask";
    private static final boolean DEBUG = false;

    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger mCount = new AtomicInteger(0);
        private final ThreadFactory mDefaultThreadFactory;
        private final String mPrefix;

        public NamedThreadFactory(final String baseName) {
            mDefaultThreadFactory = Executors.defaultThreadFactory();
            mPrefix = baseName + "-";
        }

        @Override
        public Thread newThread(@NonNull final Runnable runnable) {
            final Thread thread = mDefaultThreadFactory.newThread(runnable);
            thread.setName(mPrefix + mCount.getAndIncrement());
            return thread;
        }

        public boolean namedWithPrefix(Thread thread) {
            return thread.getName().startsWith(mPrefix);
        }
    }

    public static final NamedThreadFactory THREAD_FACTORY = new NamedThreadFactory(
            AsyncDbTask.class.getSimpleName());
    private static final ExecutorService DB_EXECUTOR = Executors
            .newSingleThreadExecutor(THREAD_FACTORY);

    /**
     * Returns the single tread executor used for DbTasks.
     */
    public static ExecutorService getExecutor() {
        return DB_EXECUTOR;
    }

    /**
     * Executes the given command at some time in the future.
     *
     * <p>The command will be executed by {@link #getExecutor()}.
     *
     * @param command the runnable task
     * @throws RejectedExecutionException if this task cannot be
     *                                    accepted for execution
     * @throws NullPointerException       if command is null
     */
    public static void execute(Runnable command) {
        DB_EXECUTOR.execute(command);
    }

    /**
     * Returns the result of a {@link ContentResolver#query(Uri, String[], String, String[],
     * String)}.
     *
     * <p> {@link #doInBackground(Void...)} executes the query on call {@link #onQuery(Cursor)}
     * which is implemented by subclasses.
     *
     * @param <Result> The type of result returned by {@link #onQuery(Cursor)}
     */
    public abstract static class AsyncQueryTask<Result> extends AsyncDbTask<Void, Void, Result> {
        private final ContentResolver mContentResolver;
        private final Uri mUri;
        private final String[] mProjection;
        private final String mSelection;
        private final String[] mSelectionArgs;
        private final String mOrderBy;


        public AsyncQueryTask(ContentResolver contentResolver, Uri uri, String[] projection,
                String selection, String[] selectionArgs, String orderBy) {
            mContentResolver = contentResolver;
            mUri = uri;
            mProjection = projection;
            mSelection = selection;
            mSelectionArgs = selectionArgs;
            mOrderBy = orderBy;
        }

        @Override
        protected final Result doInBackground(Void... params) {
            if (!THREAD_FACTORY.namedWithPrefix(Thread.currentThread())) {
                IllegalStateException e = new IllegalStateException(
                        this + " should only be executed using executeOnDbThread, " +
                                "but it was called on thread " + Thread.currentThread());
                Log.w(TAG, e);
                if (DEBUG) {
                    throw e;
                }
            }

            if (isCancelled()) {
                // This is guaranteed to never call onPostExecute because the task is canceled.
                return null;
            }
            if (DEBUG) {
                Log.v(TAG, "Starting query for " + this);
            }
            try (Cursor c = mContentResolver
                    .query(mUri, mProjection, mSelection, mSelectionArgs, mOrderBy)) {
                if (c != null && !isCancelled()) {
                    Result result = onQuery(c);
                    if (DEBUG) {
                        Log.v(TAG, "Finished query for " + this);
                    }
                    return result;
                } else {
                    if (c == null) {
                        Log.e(TAG, "Unknown query error for " + this);
                    } else {
                        if (DEBUG) {
                            Log.d(TAG, "Canceled query for " + this);
                        }
                    }
                    return null;
                }
            } catch (SecurityException e) {
                Log.d(TAG, "Security exception during query", e);
                return null;
            }
        }

        /**
         * Return the result from the cursor.
         *
         * <p><b>Note</b> This is executed on the DB thread by {@link #doInBackground(Void...)}
         */
        @WorkerThread
        protected abstract Result onQuery(Cursor c);

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + "(" + mUri + ")";
        }
    }

    /**
     * Returns the result of a query as an {@link List} of {@code T}.
     *
     * <p>Subclasses must implement {@link #fromCursor(Cursor)}.
     */
    public static abstract class AsyncQueryListTask<T> extends AsyncQueryTask<List<T>> {

        public AsyncQueryListTask(ContentResolver contentResolver, Uri uri, String[] projection,
                String selection, String[] selectionArgs, String orderBy) {
            super(contentResolver, uri, projection, selection, selectionArgs, orderBy);
        }

        @Override
        protected final List<T> onQuery(Cursor c) {
            List<T> result = new ArrayList<>();
            while (c.moveToNext()) {
                if (isCancelled()) {
                    // This is guaranteed to never call onPostExecute because the task is canceled.
                    return null;
                }
                T t = fromCursor(c);
                result.add(t);
            }
            if (DEBUG) {
                Log.v(TAG, "Found " + result.size() + " for  " + this);
            }
            return result;
        }

        /**
         * Return a single instance of {@code T} from the cursor.
         *
         * <p><b>NOTE</b> Do not move the cursor or close it, that is handled by {@link
         * #onQuery(Cursor)}.
         *
         * <p><b>Note</b> This is executed on the DB thread by {@link #onQuery(Cursor)}
         *
         * @param c The cursor with the values to create T from.
         */
        @WorkerThread
        protected abstract T fromCursor(Cursor c);
    }

    /**
     * Gets an {@link List} of {@link Channel}s from {@link TvContract.Channels#CONTENT_URI}.
     */
    public static abstract class AsyncChannelQueryTask extends AsyncQueryListTask<Channel> {

        public AsyncChannelQueryTask(ContentResolver contentResolver) {
            super(contentResolver, TvContract.Channels.CONTENT_URI, Channel.PROJECTION,
                    null, null, null);
        }

        @Override
        protected final Channel fromCursor(Cursor c) {
            return Channel.fromCursor(c);
        }
    }

    /**
     * Execute the task on the {@link #DB_EXECUTOR} thread.
     */
    @SafeVarargs
    @MainThread
    public final void executeOnDbThread(Params... params) {
        executeOnExecutor(DB_EXECUTOR, params);
    }

    /**
     * Gets an {@link List} of {@link Program}s for a given channel and period {@link
     * TvContract#buildProgramsUriForChannel(long, long, long)}.
     */
    public static class LoadProgramsForChannelTask extends AsyncQueryListTask<Program> {
        protected final Range<Long> mPeriod;
        protected final long mChannelId;

        public LoadProgramsForChannelTask(ContentResolver contentResolver, long channelId,
                Range<Long> period) {
            super(contentResolver, TvContract
                    .buildProgramsUriForChannel(channelId, period.getLower(), period.getUpper()),
                    Program.PROJECTION, null, null, null);
            mPeriod = period;
            mChannelId = channelId;
        }

        @Override
        protected final Program fromCursor(Cursor c) {
            return Program.fromCursor(c);
        }

        public long getChannelId() {
            return mChannelId;
        }

        public final Range<Long> getPeriod() {
            return mPeriod;
        }
    }
}
