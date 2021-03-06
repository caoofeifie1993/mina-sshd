/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.common.util.threads;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.DefaultCloseFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.util.closeable.AbstractCloseable;
import org.apache.sshd.common.util.logging.AbstractLoggingBean;

/**
 * Utility class for thread pools.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public final class ThreadUtils {

    private ThreadUtils() {
        throw new UnsupportedOperationException("No instance");
    }

    /**
     * Wraps an {@link ExecutorService} in such a way as to &quot;protect&quot;
     * it for calls to the {@link ExecutorService#shutdown()} or
     * {@link ExecutorService#shutdownNow()}. All other calls are delegated as-is
     * to the original service. <B>Note:</B> the exposed wrapped proxy will
     * answer correctly the {@link ExecutorService#isShutdown()} query if indeed
     * one of the {@code shutdown} methods was invoked.
     *
     * @param executorService The original service - ignored if {@code null}
     * @param shutdownOnExit  If {@code true} then it is OK to shutdown the executor
     *                        so no wrapping takes place.
     * @return Either the original service or a wrapped one - depending on the
     * value of the <tt>shutdownOnExit</tt> parameter
     */
    public static ExecutorService protectExecutorServiceShutdown(final ExecutorService executorService, boolean shutdownOnExit) {
        if (executorService == null || shutdownOnExit || executorService instanceof NoCloseExecutor) {
            return executorService;
        } else {
            return new NoCloseExecutor(executorService);
        }
    }

    public static ExecutorService noClose(ExecutorService executorService) {
        return protectExecutorServiceShutdown(executorService, false);
    }

    public static ClassLoader resolveDefaultClassLoader(Object anchor) {
        return resolveDefaultClassLoader(anchor == null ? null : anchor.getClass());
    }

    public static Iterable<ClassLoader> resolveDefaultClassLoaders(Object anchor) {
        return resolveDefaultClassLoaders(anchor == null ? null : anchor.getClass());
    }

    public static <T> T createDefaultInstance(Class<?> anchor, Class<T> targetType, String className)
            throws ReflectiveOperationException {
        return createDefaultInstance(resolveDefaultClassLoaders(anchor), targetType, className);
    }

    public static <T> T createDefaultInstance(ClassLoader cl, Class<T> targetType, String className)
            throws ReflectiveOperationException {
        Class<?> instanceType = cl.loadClass(className);
        Object instance = instanceType.newInstance();
        return targetType.cast(instance);
    }

    public static <T> T createDefaultInstance(Iterable<ClassLoader> cls, Class<T> targetType, String className)
            throws ReflectiveOperationException {
        for (ClassLoader cl : cls) {
            try {
                return createDefaultInstance(cl, targetType, className);
            } catch (ClassNotFoundException e) {
                // Ignore
            }
        }
        throw new ClassNotFoundException(className);
    }

    /**
     * <P>Attempts to find the most suitable {@link ClassLoader} as follows:</P>
     * <UL>
     * <LI><P>
     * Check the {@link Thread#getContextClassLoader()} value
     * </P></LI>
     *
     * <LI><P>
     * If no thread context class loader then check the anchor
     * class (if given) for its class loader
     * </P></LI>
     *
     * <LI><P>
     * If still no loader available, then use {@link ClassLoader#getSystemClassLoader()}
     * </P></LI>
     * </UL>
     *
     * @param anchor The anchor {@link Class} to use if no current thread
     *               - ignored if {@code null}
     *               context class loader
     * @return The resolver {@link ClassLoader}
     */
    public static ClassLoader resolveDefaultClassLoader(Class<?> anchor) {
        Thread thread = Thread.currentThread();
        ClassLoader cl = thread.getContextClassLoader();
        if (cl != null) {
            return cl;
        }

        if (anchor != null) {
            cl = anchor.getClassLoader();
        }

        if (cl == null) {   // can happen for core Java classes
            cl = ClassLoader.getSystemClassLoader();
        }

        return cl;
    }

    public static Iterable<ClassLoader> resolveDefaultClassLoaders(Class<?> anchor) {
        Set<ClassLoader> cls = new LinkedHashSet<>();
        Thread thread = Thread.currentThread();
        ClassLoader cl = thread.getContextClassLoader();
        if (cl != null) {
            cls.add(cl);
        }
        if (anchor != null) {
            cls.add(anchor.getClassLoader());
        }
        cls.add(ClassLoader.getSystemClassLoader());
        return cls;
    }

    public static ExecutorService newFixedThreadPoolIf(ExecutorService executorService, String poolName, int nThreads) {
        return executorService == null ? newFixedThreadPool(poolName, nThreads) : executorService;
    }

    public static ExecutorService newFixedThreadPool(String poolName, int nThreads) {
        return new ThreadPoolExecutor(
                nThreads, nThreads,
                0L, TimeUnit.MILLISECONDS, // TODO make this configurable
                new LinkedBlockingQueue<>(),
                new SshdThreadFactory(poolName),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public static ExecutorService newCachedThreadPoolIf(ExecutorService executorService, String poolName) {
        return executorService == null ? newCachedThreadPool(poolName) : executorService;
    }

    public static ExecutorService newCachedThreadPool(String poolName) {
        return new ThreadPoolExecutor(
                0, Integer.MAX_VALUE, // TODO make this configurable
                60L, TimeUnit.SECONDS, // TODO make this configurable
                new SynchronousQueue<>(),
                new SshdThreadFactory(poolName),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(String poolName) {
        return new ScheduledThreadPoolExecutor(1, new SshdThreadFactory(poolName));
    }

    public static ExecutorService newSingleThreadExecutor(String poolName) {
        return newFixedThreadPool(poolName, 1);
    }

    public static class SshdThreadFactory extends AbstractLoggingBean implements ThreadFactory {
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        public SshdThreadFactory(String name) {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            String effectiveName = name.replace(' ', '-');
            namePrefix = "sshd-" + effectiveName + "-thread-";
        }

        @Override
        public Thread newThread(final Runnable r) {
            Thread t;
            try {
                // see SSHD-668
                if (System.getSecurityManager() != null) {
                    t = AccessController.doPrivileged((PrivilegedExceptionAction<Thread>) () ->
                            new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0));
                } else {
                    t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
                }
            } catch (PrivilegedActionException e) {
                Exception err = e.getException();
                if (err instanceof RuntimeException) {
                    throw (RuntimeException) err;
                } else {
                    throw new RuntimeException(err);
                }
            }

            if (!t.isDaemon()) {
                t.setDaemon(true);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            if (log.isTraceEnabled()) {
                log.trace("newThread({})[{}] runnable={}", group, t.getName(), r);
            }
            return t;
        }
    }

    public static class NoCloseExecutor implements ExecutorService {

        protected final ExecutorService executor;
        protected final CloseFuture closeFuture;

        public NoCloseExecutor(ExecutorService executor) {
            this.executor = executor;
            closeFuture = new DefaultCloseFuture(null, null);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return executor.submit(task);
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return executor.submit(task, result);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return executor.submit(task);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return executor.invokeAll(tasks);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            return executor.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
            return executor.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return executor.invokeAny(tasks, timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            executor.execute(command);
        }

        @Override
        public void shutdown() {
            close(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            close(true);
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return isClosed();
        }

        @Override
        public boolean isTerminated() {
            return isClosed();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                return closeFuture.await(timeout, unit);
            } catch (IOException e) {
                throw (InterruptedException) new InterruptedException().initCause(e);
            }
        }

        @Override
        public CloseFuture close(boolean immediately) {
            closeFuture.setClosed();
            return closeFuture;
        }

        @Override
        public void addCloseFutureListener(SshFutureListener<CloseFuture> listener) {
            closeFuture.addListener(listener);
        }

        @Override
        public void removeCloseFutureListener(SshFutureListener<CloseFuture> listener) {
            closeFuture.removeListener(listener);
        }

        @Override
        public boolean isClosed() {
            return closeFuture.isClosed();
        }

        @Override
        public boolean isClosing() {
            return isClosed();
        }

    }

    public static class ThreadPoolExecutor extends java.util.concurrent.ThreadPoolExecutor implements ExecutorService {

        final DelegateCloseable closeable = new DelegateCloseable();

        class DelegateCloseable extends AbstractCloseable {
            DelegateCloseable() {
            }

            @Override
            protected CloseFuture doCloseGracefully() {
                shutdown();
                return closeFuture;
            }

            @Override
            protected void doCloseImmediately() {
                shutdownNow();
                super.doCloseImmediately();
            }

            void setClosed() {
                closeFuture.setClosed();
            }
        }

        public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
        }

        public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
        }

        public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, handler);
        }

        public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                                  long keepAliveTime, TimeUnit unit,
                                  BlockingQueue<Runnable> workQueue,
                                  ThreadFactory threadFactory,
                                  RejectedExecutionHandler handler) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
        }

        @Override
        protected void terminated() {
            closeable.doCloseImmediately();
        }

        @Override
        public void shutdown() {
            super.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return super.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return super.isShutdown();
        }

        @Override
        public boolean isTerminating() {
            return super.isTerminating();
        }

        @Override
        public boolean isTerminated() {
            return super.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return super.awaitTermination(timeout, unit);
        }

        @Override
        public CloseFuture close(boolean immediately) {
            return closeable.close(immediately);
        }

        @Override
        public void addCloseFutureListener(SshFutureListener<CloseFuture> listener) {
            closeable.addCloseFutureListener(listener);
        }

        @Override
        public void removeCloseFutureListener(SshFutureListener<CloseFuture> listener) {
            closeable.removeCloseFutureListener(listener);
        }

        @Override
        public boolean isClosed() {
            return closeable.isClosed();
        }

        @Override
        public boolean isClosing() {
            return closeable.isClosing();
        }
    }
}
