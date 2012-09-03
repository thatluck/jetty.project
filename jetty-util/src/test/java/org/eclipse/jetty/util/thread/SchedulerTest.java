//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.thread;

import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.BenchmarkHelper;
import org.eclipse.jetty.util.statistic.SampleStatistic;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;


@RunWith(value = Parameterized.class)
public class SchedulerTest
{
    private static final BenchmarkHelper benchmark = new BenchmarkHelper();
    private static final Executor executor = Executors.newFixedThreadPool(256);
    
    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        Object[][] data = new Object[][]{
            {new TimerScheduler()},
            {new ScheduledExecutionServiceScheduler()},
            {new ConcurrentScheduler(0)},
            {new ConcurrentScheduler(1500)},
            {new ConcurrentScheduler(executor,1500)}
        };
        return Arrays.asList(data);
    }
    
    // Scheduler _scheduler=new SimpleScheduler();
    Scheduler _scheduler;
    
    public SchedulerTest(Scheduler scheduler)
    {
        _scheduler=scheduler;
    }
    
    @Before
    public void before() throws Exception
    {
        _scheduler.start();
    }
    
    @After
    public void after() throws Exception
    {
        _scheduler.stop();
    }
    
    @Test
    public void testExecution() throws Exception
    {
        final AtomicLong executed = new AtomicLong();
        long expected=System.currentTimeMillis()+3000;
        Scheduler.Task task=_scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        },3000,TimeUnit.MILLISECONDS);
        
        Thread.sleep(4000);
        Assert.assertFalse(task.cancel());
        Assert.assertThat(executed.get(),Matchers.greaterThanOrEqualTo(expected));
        Assert.assertThat(expected-executed.get(),Matchers.lessThan(1000L));
        
    }
    
    @Test
    public void testQuickCancel() throws Exception
    {
        final AtomicLong executed = new AtomicLong();
        Scheduler.Task task=_scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        },3000,TimeUnit.MILLISECONDS);
        
        Thread.sleep(100);
        Assert.assertTrue(task.cancel());
        Thread.sleep(3500);
        Assert.assertEquals(0,executed.get());
    }
    
    @Test
    public void testLongCancel() throws Exception
    {
        final AtomicLong executed = new AtomicLong();
        Scheduler.Task task=_scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                executed.set(System.currentTimeMillis());
            }
        },3000,TimeUnit.MILLISECONDS);
        
        Thread.sleep(2100);
        Assert.assertTrue(task.cancel());
        Thread.sleep(1500);
        Assert.assertEquals(0,executed.get());
    }

    @Test
    @Slow
    public void testManySchedulesAndCancels() throws Exception
    {
        schedule(500,5000,2000,50);
    }
    
    @Test
    @Slow
    @Ignore
    public void testBenchmark() throws Exception
    {
        schedule(2000,10000,2000,50);
        benchmark.startStatistics();
        System.err.println(_scheduler);
        schedule(2000,30000,2000,50);
        benchmark.stopStatistics();
    }
    
    private void schedule(int threads,final int duration, final int delay, final int interval) throws Exception
    {
        final Random random = new Random(1);
        Thread[] test = new Thread[threads]; 
        
        final AtomicInteger schedules = new AtomicInteger();
        final SampleStatistic executions = new SampleStatistic();
        final SampleStatistic cancellations = new SampleStatistic();
        
        for (int i=test.length;i-->0;)
        {
            test[i]=new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        long now = System.currentTimeMillis();
                        long start=now;
                        long end=start+duration;
                        
                        while (now+interval<end)
                        {
                            final long expected=now+delay;
                            int cancel=random.nextInt(interval);
                            boolean expected_to_execute=false;
                            if (cancel==0)
                            {
                                expected_to_execute=true;
                                cancel=delay+1000;
                            }

                            schedules.incrementAndGet();
                            Scheduler.Task task=_scheduler.schedule(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    long lateness=System.currentTimeMillis()-expected;
                                    executions.set(lateness);                                    
                                }
                            },delay,TimeUnit.MILLISECONDS);
                            
                            Thread.sleep(cancel);
                            now = System.currentTimeMillis();
                            if (task.cancel())
                            {
                                if (expected_to_execute)
                                    cancellations.set(now-expected);
                                else
                                    cancellations.set(0);
                            }
                            else if (!expected_to_execute)
                            {
                                cancellations.set(9999); // flags failure
                            }

                            Thread.yield();
                        }
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                    
                }
            };
        }
        
        for (Thread thread : test)
            thread.start();
        
        for (Thread thread : test)
            thread.join();
        
        // System.err.println(schedules);
        // System.err.println(executions);
        // System.err.println(cancellations);

        // there were some executions and cancellations
        Assert.assertThat(executions.getCount(),Matchers.greaterThan(0L));
        Assert.assertThat(cancellations.getCount(),Matchers.greaterThan(0L));

        // All executed or cancelled
        // Not that SimpleScheduler can execute and cancel an event!
        Assert.assertThat(0L+schedules.get(),Matchers.lessThanOrEqualTo(executions.getCount()+cancellations.getCount()));
        
        // No really late executions
        Assert.assertThat(executions.getMax(),Matchers.lessThan(500L));

        // Executions on average are close to the expected time
        Assert.assertThat(executions.getMean(),Matchers.lessThan(500.0));
        
        // No cancellations long after expected executions
        Assert.assertThat(cancellations.getMax(),Matchers.lessThan(500L));
    }
    
    
    
}
