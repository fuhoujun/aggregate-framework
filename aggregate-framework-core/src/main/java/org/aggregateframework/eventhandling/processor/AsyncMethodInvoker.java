package org.aggregateframework.eventhandling.processor;

import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import org.aggregateframework.SystemException;
import org.aggregateframework.context.AsyncParameterConfig;
import org.aggregateframework.eventhandling.annotation.Retryable;
import org.aggregateframework.eventhandling.processor.async.AsyncEvent;
import org.aggregateframework.eventhandling.processor.async.AsyncEventFactory;
import org.aggregateframework.eventhandling.processor.async.RetryEvent;
import org.aggregateframework.eventhandling.processor.async.RetryEventFactory;
import org.aggregateframework.eventhandling.processor.retry.*;
import org.aggregateframework.utils.ReflectionUtils;
import org.aggregateframework.eventhandling.annotation.EventHandler;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Created by changmingxie on 12/2/15.
 */
public class AsyncMethodInvoker {

    static final Logger logger = LoggerFactory.getLogger(AsyncMethodInvoker.class);

    private Disruptor<AsyncEvent> disruptor;

    private Disruptor<RetryEvent> retryDisruptor;

    private static volatile AsyncMethodInvoker INSTANCE = null;

    public Disruptor<AsyncEvent> getDisruptor() {
        return disruptor;
    }

    public Disruptor<RetryEvent> getRetryDisruptor() {
        return retryDisruptor;
    }

    public static AsyncMethodInvoker getInstance() {

        if (INSTANCE == null) {

            synchronized (AsyncMethodInvoker.class) {

                if (INSTANCE == null) {
                    INSTANCE = new AsyncMethodInvoker();
                }
            }
        }

        return INSTANCE;
    }

    private AsyncMethodInvoker() {

        initializeAsyncRingBuffer();
        initializeRetryRingBuffer();
    }

    private void initializeAsyncRingBuffer() {
        Executor executor = AsyncParameterConfig.EXECUTOR;

        AsyncEventFactory factory = new AsyncEventFactory();

        disruptor = new Disruptor<AsyncEvent>(factory, AsyncParameterConfig.DISRUPTOR_RING_BUFFER_SIZE, executor);

        disruptor.handleExceptionsWith(new AsyncExceptionEventHandler());

        AsyncEventHandler asyncEventHandler = new AsyncEventHandler();

        AsyncEventHandler[] asyncEventHandlers = new AsyncEventHandler[AsyncParameterConfig.ASYNC_EVENT_HANDLER_WORK_POOL_SIZE];

        for (int i = 0; i < asyncEventHandlers.length; i++) {
            asyncEventHandlers[i] = asyncEventHandler;
        }

        disruptor.handleEventsWithWorkerPool(asyncEventHandlers);

        disruptor.start();
    }

    private void initializeRetryRingBuffer() {
        Executor executor = AsyncParameterConfig.RETRY_EXECUTOR;

        RetryEventFactory factory = new RetryEventFactory();

        retryDisruptor = new Disruptor<RetryEvent>(factory, AsyncParameterConfig.DISRUPTOR_RETRY_RING_BUFFER_SIZE, executor);

        retryDisruptor.handleExceptionsWith(new RetryExceptionEventHandler());

        RetryEventHandler retryEventHandler = new RetryEventHandler();

        RetryEventHandler[] retryEventHandlers = new RetryEventHandler[AsyncParameterConfig.RETRY_EVENT_HANDLER_WORK_POOL_SIZE];

        for (int i = 0; i < retryEventHandlers.length; i++) {
            retryEventHandlers[i] = retryEventHandler;
        }

        retryDisruptor.handleEventsWithWorkerPool(retryEventHandlers);

        retryDisruptor.start();

    }

    public void invoke(Method method, Object target, Object... params) {
        long sequence = disruptor.getRingBuffer().next();

        try {
            AsyncEvent event = disruptor.getRingBuffer().get(sequence);
            event.reset(method, target, params);
        } finally {
            disruptor.getRingBuffer().publish(sequence);
        }
    }

    private void retryableInvoke(Throwable throwable, Method method, Object target, Object[] params) {
        long sequence = retryDisruptor.getRingBuffer().next();

        try {
            RetryEvent event = retryDisruptor.getRingBuffer().get(sequence);
            event.reset(throwable, method, target, params);
        } finally {
            retryDisruptor.getRingBuffer().publish(sequence);
        }
    }

    class AsyncEventHandler implements com.lmax.disruptor.WorkHandler<AsyncEvent> {

        @Override
        public void onEvent(AsyncEvent asyncEvent) throws Exception {
            asyncEvent.getMethod().invoke(asyncEvent.getTarget(), asyncEvent.getParams());
        }
    }

    class RetryEventHandler implements com.lmax.disruptor.WorkHandler<RetryEvent> {

        @Override
        public void onEvent(final RetryEvent retryEvent) throws Exception {

            final Retryable retryable = ReflectionUtils.getAnnotation(retryEvent.getMethod(), Retryable.class);

            final PolicyBuilder policyBuilder = new PolicyBuilder();
            RetryTemplate retryTemplate = new RetryTemplate();

            RetryPolicy retryPolicy = policyBuilder.getRetryPolicy(retryable);
            retryTemplate.setRetryPolicy(retryPolicy);
            retryTemplate.setBackOffPolicy(policyBuilder.getBackoffPolicy(retryable.backoff()));

            RetryContext retryContext = retryPolicy.requireRetryContext();

            retryContext.registerThrowable(retryEvent.getThrowable());

            final Method method = retryEvent.getMethod();
            final Object target = retryEvent.getTarget();
            final Object[] params = retryEvent.getParams();

            RetryCallback<Object, Exception> retryCallback = new RetryCallback<Object, Exception>() {
                @Override
                public Object doWithRetry(RetryContext context) throws Exception {
                    return method.invoke(target, params);
                }
            };

            RecoveryCallback<Object> recoveryCallback = null;

            if (StringUtils.isNotEmpty(retryable.recoverMethod())) {
                recoveryCallback = new RecoveryCallback<Object>() {

                    @Override
                    public Object recover(RetryContext context) {
                        try {
                            Method recoverMethod = target.getClass().getMethod(retryable.recoverMethod(), method.getParameterTypes());
                            return recoverMethod.invoke(target, params);
                        } catch (Exception ex) {
                            throw new SystemException(ex);
                        }
                    }
                };
            }

            retryTemplate.execute(retryContext, retryCallback, recoveryCallback);
        }

    }


    class AsyncExceptionEventHandler implements ExceptionHandler<AsyncEvent> {

        @Override
        public void handleEventException(Throwable throwable, long l, AsyncEvent asyncEvent) {

            Retryable retryable = ReflectionUtils.getAnnotation(asyncEvent.getMethod(), Retryable.class);

            if (retryable == null) {
                logger.error(String.format("method call failed. method:%s,target:%s", asyncEvent.getMethod().getName(), asyncEvent.getTarget().toString()), throwable);
                return;
            } else {
                retryableInvoke(throwable, asyncEvent.getMethod(), asyncEvent.getTarget(), asyncEvent.getParams());
            }
        }

        @Override
        public void handleOnStartException(Throwable throwable) {
            throw new Error(throwable);
        }

        @Override
        public void handleOnShutdownException(Throwable throwable) {
            throw new Error(throwable);
        }
    }

    class RetryExceptionEventHandler implements ExceptionHandler<RetryEvent> {
        
        @Override
        public void handleEventException(Throwable throwable, long l, RetryEvent retryEvent) {
            logger.error(String.format("method call failed. method:%s,target:%s", retryEvent.getMethod().getName(), retryEvent.getTarget().toString()), throwable);
            return;
        }

        @Override
        public void handleOnStartException(Throwable throwable) {
            throw new Error(throwable);
        }

        @Override
        public void handleOnShutdownException(Throwable throwable) {
            throw new Error(throwable);
        }
    }

}
