package com.nirmata.workflow;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.nirmata.workflow.admin.AllRunReports;
import com.nirmata.workflow.admin.Cleaner;
import com.nirmata.workflow.admin.RunReport;
import com.nirmata.workflow.admin.Stopper;
import com.nirmata.workflow.details.Scheduler;
import com.nirmata.workflow.details.WorkflowStatus;
import com.nirmata.workflow.details.ZooKeeperConstants;
import com.nirmata.workflow.details.internalmodels.DenormalizedWorkflowModel;
import com.nirmata.workflow.models.ExecutableTaskModel;
import com.nirmata.workflow.models.RunId;
import com.nirmata.workflow.models.ScheduleModel;
import com.nirmata.workflow.models.TaskId;
import com.nirmata.workflow.spi.StorageBridge;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.BaseClassForTests;
import org.apache.curator.test.Timing;
import org.apache.curator.utils.CloseableUtils;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class TestNormal extends BaseClassForTests
{
    private CuratorFramework curator;

    @BeforeMethod
    public void setup() throws Exception
    {
        super.setup();

        curator = CuratorFrameworkFactory.builder().connectString(server.getConnectString()).namespace("test").retryPolicy(new RetryOneTime(1)).build();
        curator.start();
    }

    @AfterMethod
    public void teardown() throws Exception
    {
        CloseableUtils.closeQuietly(curator);

        super.teardown();
    }

    @Test
    public void testNormal_1x() throws Exception
    {
        StorageBridge storageBridge = new MockStorageBridge("schedule_1x.json", "tasks.json", "workflows.json", "task_containers.json");

        Timing timing = new Timing();
        TestTaskExecutor taskExecutor = new TestTaskExecutor(6);
        WorkflowRunner workflow = new WorkflowRunner(storageBridge, taskExecutor, new CountDownLatch(1));
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(workflow);
        try
        {
            Assert.assertTrue(timing.awaitLatch(taskExecutor.getLatch()));

            List<Set<TaskId>> sets = taskExecutor.getChecker().getSets();
            List<Set<TaskId>> expectedSets = Arrays.<Set<TaskId>>asList
                (
                    Sets.newHashSet(new TaskId("task1"), new TaskId("task2")),
                    Sets.newHashSet(new TaskId("task3"), new TaskId("task4"), new TaskId("task5")),
                    Sets.newHashSet(new TaskId("task6"))
                );
            Assert.assertEquals(sets, expectedSets);

            taskExecutor.getChecker().assertNoDuplicates();
        }
        finally
        {
            executorService.shutdownNow();
            CloseableUtils.closeQuietly(workflow.getWorkflowManager());
        }
    }

    private class WorkflowRunner implements Runnable
    {
        private final WorkflowManager workflowManager;

        private WorkflowRunner(StorageBridge storageBridge, TestTaskExecutor taskExecutor, CountDownLatch scheduleLatch)
        {
            WorkflowManagerConfiguration configuration = new WorkflowManagerConfigurationImpl(1000, 1000, 10, 10);
            workflowManager = new WorkflowManager(curator, configuration, taskExecutor, storageBridge)
            {
                @Override
                protected Scheduler makeScheduler()
                {
                    return new Scheduler(this)
                    {
                        @Override
                        protected void logWorkflowStarted(ScheduleModel schedule)
                        {
                            super.logWorkflowStarted(schedule);
                            scheduleLatch.countDown();
                        }
                    };
                }
            };
        }

        public WorkflowManager getWorkflowManager()
        {
            return workflowManager;
        }

        @Override
        public void run()
        {
            workflowManager.start();
            try
            {
                Thread.currentThread().join();
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void testNormalMultiClient_1x() throws Exception
    {
        final int CLIENT_QTY = 10;

        StorageBridge storageBridge = new MockStorageBridge("schedule_1x.json", "tasks.json", "workflows.json", "task_containers.json");

        Timing timing = new Timing();
        TestTaskExecutor taskExecutor = new TestTaskExecutor(6);
        ExecutorService executorService = Executors.newFixedThreadPool(CLIENT_QTY);
        List<WorkflowRunner> workflows = Lists.newArrayList();
        for ( int i = 0; i < CLIENT_QTY; ++i )
        {
            workflows.add(new WorkflowRunner(storageBridge, taskExecutor, new CountDownLatch(1)));
        }
        workflows.forEach(executorService::submit);
        try
        {
            Assert.assertTrue(timing.awaitLatch(taskExecutor.getLatch()));

            List<Set<TaskId>> sets = taskExecutor.getChecker().getSets();
            List<Set<TaskId>> expectedSets = Arrays.<Set<TaskId>>asList
                (
                    Sets.newHashSet(new TaskId("task1"), new TaskId("task2")),
                    Sets.newHashSet(new TaskId("task3"), new TaskId("task4"), new TaskId("task5")),
                    Sets.newHashSet(new TaskId("task6"))
                );
            Assert.assertEquals(sets, expectedSets);

            taskExecutor.getChecker().assertNoDuplicates();
        }
        finally
        {
            executorService.shutdownNow();
            workflows.forEach(w -> CloseableUtils.closeQuietly(w.getWorkflowManager()));
        }
    }

    @Test
    public void testNormal_2x() throws Exception
    {
        StorageBridge storageBridge = new MockStorageBridge("schedule_2x.json", "tasks.json", "workflows.json", "task_containers.json");

        Timing timing = new Timing();
        TestTaskExecutor taskExecutor = new TestTaskExecutor(6);
        final CountDownLatch scheduleLatch = new CountDownLatch(2);
        WorkflowRunner workflow = new WorkflowRunner(storageBridge, taskExecutor, scheduleLatch);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(workflow);
        try
        {
            Assert.assertTrue(timing.awaitLatch(taskExecutor.getLatch()));

            List<Set<TaskId>> sets = taskExecutor.getChecker().getSets();
            List<Set<TaskId>> expectedSets = Arrays.<Set<TaskId>>asList
                (
                    Sets.newHashSet(new TaskId("task1"), new TaskId("task2")),
                    Sets.newHashSet(new TaskId("task3"), new TaskId("task4"), new TaskId("task5")),
                    Sets.newHashSet(new TaskId("task6"))
                );
            Assert.assertEquals(sets, expectedSets);
            taskExecutor.getChecker().assertNoDuplicates();
            taskExecutor.reset();

            Assert.assertTrue(timing.awaitLatch(scheduleLatch));
            Assert.assertTrue(timing.awaitLatch(taskExecutor.getLatch()));

            sets = taskExecutor.getChecker().getSets();
            Assert.assertEquals(sets, expectedSets);
            taskExecutor.getChecker().assertNoDuplicates();
        }
        finally
        {
            executorService.shutdownNow();
            CloseableUtils.closeQuietly(workflow.getWorkflowManager());
        }
    }

    @Test
    public void testNormalMultiClient_2x() throws Exception
    {
        final int CLIENT_QTY = 10;

        StorageBridge storageBridge = new MockStorageBridge("schedule_2x.json", "tasks.json", "workflows.json", "task_containers.json");

        Timing timing = new Timing();
        TestTaskExecutor taskExecutor = new TestTaskExecutor(6);
        final CountDownLatch scheduleLatch = new CountDownLatch(2);
        ExecutorService executorService = Executors.newFixedThreadPool(CLIENT_QTY);
        List<WorkflowRunner> workflows = Lists.newArrayList();
        for ( int i = 0; i < CLIENT_QTY; ++i )
        {
            workflows.add(new WorkflowRunner(storageBridge, taskExecutor, scheduleLatch));
        }
        workflows.forEach(executorService::submit);
        try
        {
            Assert.assertTrue(timing.awaitLatch(taskExecutor.getLatch()));

            List<Set<TaskId>> sets = taskExecutor.getChecker().getSets();
            List<Set<TaskId>> expectedSets = Arrays.<Set<TaskId>>asList
                (
                    Sets.newHashSet(new TaskId("task1"), new TaskId("task2")),
                    Sets.newHashSet(new TaskId("task3"), new TaskId("task4"), new TaskId("task5")),
                    Sets.newHashSet(new TaskId("task6"))
                );
            Assert.assertEquals(sets, expectedSets);
            taskExecutor.getChecker().assertNoDuplicates();
            taskExecutor.reset();

            Assert.assertTrue(timing.awaitLatch(scheduleLatch));
            Assert.assertTrue(timing.awaitLatch(taskExecutor.getLatch()));

            sets = taskExecutor.getChecker().getSets();
            Assert.assertEquals(sets, expectedSets);
            taskExecutor.getChecker().assertNoDuplicates();
        }
        finally
        {
            executorService.shutdownNow();
            workflows.forEach(w -> CloseableUtils.closeQuietly(w.getWorkflowManager()));
        }
    }

    @Test
    public void testCleaner() throws Exception
    {
        StorageBridge storageBridge = new MockStorageBridge("schedule_2x.json", "tasks.json", "workflows.json", "task_containers.json");

        Timing timing = new Timing();
        WorkflowManagerConfiguration configuration = new WorkflowManagerConfigurationImpl(1000, 1000, 10, 10);
        TestTaskExecutor taskExecutor = new TestTaskExecutor(6);
        final CountDownLatch scheduleLatch = new CountDownLatch(2);
        WorkflowManager workflowManager = new WorkflowManager(curator, configuration, taskExecutor, storageBridge)
        {
            @Override
            protected Scheduler makeScheduler()
            {
                return new Scheduler(this)
                {
                    @Override
                    protected void logWorkflowCompleted(DenormalizedWorkflowModel workflow)
                    {
                        super.logWorkflowCompleted(workflow);
                        scheduleLatch.countDown();
                    }
                };
            }
        };
        workflowManager.start();
        Assert.assertTrue(timing.awaitLatch(scheduleLatch));

        AllRunReports allRunReports = new AllRunReports(curator);
        Assert.assertEquals(allRunReports.getReports().size(), 2);

        Cleaner cleaner = new Cleaner(curator);
        List<RunId> runIds = Lists.newArrayList(allRunReports.getReports().keySet());
        cleaner.clean(runIds.get(0));
        Assert.assertTrue(curator.checkExists().forPath(ZooKeeperConstants.getRunsParentPath()).getNumChildren() == 0); // all schedules are complete
        Assert.assertTrue(curator.checkExists().forPath(ZooKeeperConstants.getCompletedRunParentPath()).getNumChildren() > 0);
        Assert.assertTrue(curator.checkExists().forPath(ZooKeeperConstants.getStartedTasksParentPath()).getNumChildren() > 0);
        Assert.assertTrue(curator.checkExists().forPath(ZooKeeperConstants.getCompletedTasksParentPath()).getNumChildren() > 0);

        cleaner.clean(runIds.get(1));
        Assert.assertEquals(curator.checkExists().forPath(ZooKeeperConstants.getRunsParentPath()).getNumChildren(), 0);
        Assert.assertEquals(curator.checkExists().forPath(ZooKeeperConstants.getCompletedRunParentPath()).getNumChildren(), 0);
        Assert.assertEquals(curator.checkExists().forPath(ZooKeeperConstants.getStartedTasksParentPath()).getNumChildren(), 0);
        Assert.assertEquals(curator.checkExists().forPath(ZooKeeperConstants.getCompletedTasksParentPath()).getNumChildren(), 0);
    }

    @Test
    public void testRunReport() throws Exception
    {
        StorageBridge storageBridge = new MockStorageBridge("schedule_1x.json", "tasks.json", "workflows.json", "task_containers.json");

        Timing timing = new Timing();
        WorkflowManagerConfiguration configuration = new WorkflowManagerConfigurationImpl(1000, 1000, 10, 10);
        AtomicInteger counter = new AtomicInteger(0);
        AtomicReference<RunReport> runReport = new AtomicReference<>();
        TestTaskExecutor taskExecutor = new TestTaskExecutor(6)
        {
            @Override
            protected void doRun(ExecutableTaskModel task) throws InterruptedException
            {
                if ( counter.incrementAndGet() == 3 )
                {
                    runReport.set(new RunReport(curator, task.getRunId()));
                }
                super.doRun(task);
            }
        };
        WorkflowManager workflowManager = new WorkflowManager(curator, configuration, taskExecutor, storageBridge);
        workflowManager.start();
        try
        {
            Assert.assertTrue(timing.awaitLatch(taskExecutor.getLatch()));

            RunReport report = runReport.get();
            Assert.assertTrue(report.isValid());
            Assert.assertTrue(report.getStatus() == WorkflowStatus.RUNNING);
            Assert.assertTrue(report.getCompletedTasks().containsKey(new TaskId("task1")));
            Assert.assertTrue(report.getCompletedTasks().containsKey(new TaskId("task2")));
            Assert.assertTrue(report.getRunningTasks().containsKey(new TaskId("task3")));
            Assert.assertTrue(!report.getRunningTasks().containsKey(new TaskId("task1")));
            Assert.assertTrue(!report.getRunningTasks().containsKey(new TaskId("task2")));

            timing.sleepABit(); // allow workflow to finish

            report = new RunReport(curator, report.getRunId());
            Assert.assertTrue(report.isValid());
            Assert.assertTrue(report.getStatus() == WorkflowStatus.COMPLETED);
            Assert.assertEquals(report.getRunningTasks().size(), 0);
            Assert.assertEquals(report.getCompletedTasks().size(), 6);

            AllRunReports allRunReports = new AllRunReports(curator);
            Assert.assertEquals(allRunReports.getReports().size(), 1);
            Assert.assertEquals(allRunReports.getReports().values().iterator().next(), report);
        }
        finally
        {
            CloseableUtils.closeQuietly(workflowManager);
        }
    }

    @Test
    public void testStopper() throws Exception
    {
        StorageBridge storageBridge = new MockStorageBridge("schedule_1x.json", "tasks.json", "workflows.json", "task_containers.json");

        Timing timing = new Timing();
        WorkflowManagerConfiguration configuration = new WorkflowManagerConfigurationImpl(1000, 1000, 10, 10);
        TestTaskExecutor taskExecutor = new TestTaskExecutor(1);
        CountDownLatch canceledLatch = new CountDownLatch(1);
        WorkflowManager workflowManager = new WorkflowManager(curator, configuration, taskExecutor, storageBridge)
        {
            @Override
            protected Scheduler makeScheduler()
            {
                return new Scheduler(this)
                {
                    @Override
                    protected void debugNoteCanceledWorkflow()
                    {
                        canceledLatch.countDown();
                    }
                };
            }
        };
        workflowManager.start();
        try
        {
            timing.awaitLatch(taskExecutor.getLatch());
            Stopper stopper = new Stopper(workflowManager);
            AllRunReports allRunReports = new AllRunReports(curator);
            Assert.assertEquals(allRunReports.getReports().size(), 1);
            RunId runId = allRunReports.getReports().keySet().iterator().next();
            Assert.assertTrue(stopper.stop(runId));

            Assert.assertTrue(timing.awaitLatch(canceledLatch));

            RunReport runReport = new RunReport(curator, runId);
            Assert.assertEquals(runReport.getStatus(), WorkflowStatus.FORCE_FAILED);
        }
        finally
        {
            CloseableUtils.closeQuietly(workflowManager);
        }
    }
}
