package org.camunda.tngp.client.task.impl;

import org.agrona.concurrent.Agent;

public class TaskExecutor implements Agent
{
    public static final String ROLE_NAME = "task-executor";

    protected final TaskSubscriptions subscriptions;

    public TaskExecutor(TaskSubscriptions subscriptions)
    {
        this.subscriptions = subscriptions;
    }

    @Override
    public int doWork() throws Exception
    {
        int workCount = 0;
        for (TaskSubscriptionImpl subscription : subscriptions.getManagedExecutionSubscriptions())
        {
            final int handledTasks = subscription.poll();
            if (handledTasks > 0)
            {
                subscription.fetchTasks();
            }

            workCount += handledTasks;
        }

        return workCount;
    }

    @Override
    public String roleName()
    {
        return ROLE_NAME;
    }

}
